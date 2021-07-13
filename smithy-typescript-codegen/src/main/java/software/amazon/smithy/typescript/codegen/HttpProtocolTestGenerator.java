/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.typescript.codegen;

import static java.lang.String.format;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBinding.Location;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeVisitor;
import software.amazon.smithy.model.node.NullNode;
import software.amazon.smithy.model.node.NumberNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait;
import software.amazon.smithy.model.traits.IdempotencyTokenTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.protocoltests.traits.AppliesTo;
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait;
import software.amazon.smithy.typescript.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.typescript.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.Pair;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Generates HTTP protocol test cases to be run using Jest.
 *
 * <p>Protocol tests are defined for HTTP protocols using the
 * {@code smithy.test#httpRequestTests} and {@code smithy.test#httpResponseTests}
 * traits. When found on operations or errors attached to operations, a
 * protocol test case will be generated that asserts that the protocol
 * serialization and deserialization code creates the correct HTTP requests
 * and responses for a specific set of parameters.
 *
 * TODO: try/catch and if/else are still cumbersome with TypeScriptWriter.
 */
@SmithyInternalApi
public final class HttpProtocolTestGenerator implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(HttpProtocolTestGenerator.class.getName());
    private static final String TEST_CASE_FILE_TEMPLATE = "tests/functional/%s.spec.ts";

    private final TypeScriptSettings settings;
    private final Model model;
    private final ShapeId protocol;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;
    private final Symbol serviceSymbol;
    private final Set<String> additionalStubs = new TreeSet<>();
    private final ProtocolGenerator protocolGenerator;
    private final TestFilter testFilter;
    private final GenerationContext context;

    private TypeScriptWriter writer;

    public HttpProtocolTestGenerator(
            GenerationContext context,
            ProtocolGenerator protocolGenerator,
            TestFilter testFilter
    ) {
        this.settings = context.getSettings();
        this.model = context.getModel();
        this.protocol = protocolGenerator.getProtocol();
        this.service = settings.getService(model);
        this.symbolProvider = context.getSymbolProvider();
        this.protocolGenerator = protocolGenerator;
        serviceSymbol = symbolProvider.toSymbol(service);
        this.testFilter = testFilter;
        this.context = context;
    }

    public HttpProtocolTestGenerator(
            GenerationContext context,
            ProtocolGenerator protocolGenerator
    ) {
        this(context, protocolGenerator, (service, operation, testCase, typeScriptSettings) -> false);
    }

    @Override
    public void run() {
        OperationIndex operationIndex = OperationIndex.of(model);
        TopDownIndex topDownIndex = TopDownIndex.of(model);

        // Use a TreeSet to have a fixed ordering of tests.
        for (OperationShape operation : new TreeSet<>(topDownIndex.getContainedOperations(service))) {
            if (settings.generateClient()) {
                generateClientOperationTests(operation, operationIndex);
            }
            if (settings.generateServerSdk()) {
                generateServerOperationTests(operation, operationIndex);
            }
        }

        // Include any additional stubs required.
        for (String additionalStub : additionalStubs) {
            writer.write(IoUtils.readUtf8Resource(getClass(), additionalStub));
        }
    }

    private void generateClientOperationTests(OperationShape operation, OperationIndex operationIndex) {
        if (!operation.hasTag("server-only")) {
            // 1. Generate test cases for each request.
            operation.getTrait(HttpRequestTestsTrait.class).ifPresent(trait -> {
                for (HttpRequestTestCase testCase : trait.getTestCasesFor(AppliesTo.CLIENT)) {
                    onlyIfProtocolMatches(testCase, () -> generateClientRequestTest(operation, testCase));
                }
            });
            // 2. Generate test cases for each response.
            operation.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                for (HttpResponseTestCase testCase : trait.getTestCasesFor(AppliesTo.CLIENT)) {
                    onlyIfProtocolMatches(testCase, () -> generateResponseTest(operation, testCase));
                }
            });
            // 3. Generate test cases for each error on each operation.
            for (StructureShape error : operationIndex.getErrors(operation)) {
                if (!error.hasTag("server-only")) {
                    error.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                        for (HttpResponseTestCase testCase : trait.getTestCasesFor(AppliesTo.CLIENT)) {
                            onlyIfProtocolMatches(testCase,
                                    () -> generateErrorResponseTest(operation, error, testCase));
                        }
                    });
                }
            }
        }
    }

    private void generateServerOperationTests(OperationShape operation, OperationIndex operationIndex) {
        if (!operation.hasTag("client-only")) {
            // 1. Generate test cases for each request.
            operation.getTrait(HttpRequestTestsTrait.class).ifPresent(trait -> {
                for (HttpRequestTestCase testCase : trait.getTestCasesFor(AppliesTo.SERVER)) {
                    onlyIfProtocolMatches(testCase, () -> generateServerRequestTest(operation, testCase));
                }
            });
            // 2. Generate test cases for each response.
            operation.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                for (HttpResponseTestCase testCase : trait.getTestCasesFor(AppliesTo.SERVER)) {
                    onlyIfProtocolMatches(testCase, () -> generateServerResponseTest(operation, testCase));
                }
            });
            // 3. Generate test cases for each error on each operation.
            for (StructureShape error : operationIndex.getErrors(operation)) {
                if (!error.hasTag("client-only")) {
                    error.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                        for (HttpResponseTestCase testCase : trait.getTestCasesFor(AppliesTo.SERVER)) {
                            onlyIfProtocolMatches(testCase,
                                    () -> generateServerErrorResponseTest(operation, error, testCase));
                        }
                    });
                }
            }
        }
    }

    // Only generate test cases when its protocol matches the target protocol.
    private <T extends HttpMessageTestCase> void onlyIfProtocolMatches(T testCase, Runnable runnable) {
        if (testCase.getProtocol().equals(protocol)) {
            LOGGER.fine(() -> format("Generating protocol test case for %s.%s", service.getId(), testCase.getId()));
            initializeWriterIfNeeded();
            runnable.run();
        }
    }

    private void initializeWriterIfNeeded() {
        if (writer == null) {
            writer = context.getWriter();
            writer.addDependency(TypeScriptDependency.AWS_SDK_TYPES);
            writer.addDependency(TypeScriptDependency.AWS_SDK_PROTOCOL_HTTP);
            // Add the template to each generated test.
            writer.write(IoUtils.readUtf8Resource(getClass(), "protocol-test-stub.ts"));
        }
    }

    private String createTestCaseFilename() {
        String baseName = protocol.getName().toLowerCase(Locale.US)
                .replace("-", "_")
                .replace(".", "_");
        return TEST_CASE_FILE_TEMPLATE.replace("%s", baseName);
    }

    private void generateClientRequestTest(OperationShape operation, HttpRequestTestCase testCase) {
        Symbol operationSymbol = symbolProvider.toSymbol(operation);

        String testName = testCase.getId() + ":Request";
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        openTestBlock(operation, testCase, testName, () -> {
            // Create a client with a custom request handler that intercepts requests.
            writer.openBlock("const client = new $T({", "});\n", serviceSymbol, () -> {
                    writer.write("...clientParams,");
                    testCase.getHost().ifPresent(host -> {
                        writer.write("endpoint: \"https://$L\",", host);
                    });
                    writer.write("requestHandler: new RequestSerializationTestHandler(),");
            });

            // Run the parameters through a visitor to adjust for TS specific inputs.
            ObjectNode params = testCase.getParams();
            Optional<ShapeId> inputOptional = operation.getInput();
            if (inputOptional.isPresent()) {
                StructureShape inputShape = model.expectShape(inputOptional.get(), StructureShape.class);
                writer.write("const command = new $T(", operationSymbol)
                        .call(() -> params.accept(new CommandInputNodeVisitor(inputShape)))
                        .write(");");
            } else {
                writer.write("const command = new $T({});", operationSymbol);
            }

            // Send the request and look for the expected exception to then perform assertions.
            writer.write("try {\n"
                       + "  await client.send(command);\n"
                       + "  fail('Expected an EXPECTED_REQUEST_SERIALIZATION_ERROR to be thrown');\n"
                       + "  return;\n"
                       + "} catch (err) {\n"
                       + "  if (!(err instanceof EXPECTED_REQUEST_SERIALIZATION_ERROR)) {\n"
                       + "    fail(err);\n"
                       + "    return;\n"
                       + "  }\n"
                       + "  const r = err.request;")
                    .indent()
                    .call(() -> writeHttpRequestAssertions(testCase))
                    .dedent()
                    .write("}");
        });
    }

    private void generateServerRequestTest(OperationShape operation, HttpRequestTestCase testCase) {
        Symbol operationSymbol = symbolProvider.toSymbol(operation);

        // Lowercase all the headers we're expecting as this is what we'll get.
        Map<String, String> headers = testCase.getHeaders().entrySet().stream()
                .map(entry -> new Pair<>(entry.getKey().toLowerCase(Locale.US), entry.getValue()))
                .collect(MapUtils.toUnmodifiableMap(Pair::getLeft, Pair::getRight));
        String queryParameters = Node.prettyPrintJson(buildQueryBag(testCase));
        String headerParameters = Node.prettyPrintJson(ObjectNode.fromStringMap(headers));
        String body = testCase.getBody().orElse(null);

        String testName = testCase.getId() + ":ServerRequest";
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        openTestBlock(operation, testCase, testName, () -> {
            Symbol serviceSymbol = symbolProvider.toSymbol(service);
            Symbol handlerSymbol = serviceSymbol.expectProperty("handler", Symbol.class);

            // Create a mock function to set in place of the server operation function so we can capture
            // input and other information.
            writer.write("let testFunction = jest.fn();");
            writer.write("testFunction.mockReturnValue(Promise.resolve({}));");

            // We use a partial here so that we don't have to define the entire service, but still get the advantages
            // the type checker, including excess property checking. Later on we'll use `as` to cast this to the
            // full service so that we can actually use it.
            writer.openBlock("const testService: Partial<$T<{}>> = {", "};", serviceSymbol, () -> {
                writer.write("$L: testFunction as $T<{}>,", operationSymbol.getName(), operationSymbol);
            });

            String getHandlerName = "get" + handlerSymbol.getName();
            writer.addImport(getHandlerName, null, "./server/");
            writer.addImport("ValidationFailure", "__ValidationFailure", "@aws-smithy/server-common");

            // Cast the service as any so TS will ignore the fact that the type being passed in is incomplete.
            writer.openBlock(
                    "const handler = $L(testService as $T<{}>, (ctx: {}, failures: __ValidationFailure[]) => {",
                    "});", getHandlerName, serviceSymbol,
                    () -> writer.write("if (failures) { throw failures; } return undefined;")
            );

            // Construct a new http request according to the test case definition.
            writer.openBlock("const request = new HttpRequest({", "});", () -> {
                writer.write("method: $S,", testCase.getMethod());
                writer.write("hostname: $S,", testCase.getHost().orElse("foo.example.com"));
                writer.write("path: $S,", testCase.getUri());
                writer.write("query: $L,", queryParameters);
                writer.write("headers: $L,", headerParameters);
                if (body != null) {
                    writer.write("body: Readable.from([$S]),", body);
                }
            });
            writer.write("await handler.handle(request, {});").write("");

            // Assert that the function has been called exactly once.
            writer.write("expect(testFunction.mock.calls.length).toBe(1);");

            // Capture the input. We need to cast this to any so we can index into it.
            writer.write("let r: any = testFunction.mock.calls[0][0];").write("");
            writeRequestParamAssertions(operation, testCase);
        });
    }

    private ObjectNode buildQueryBag(HttpRequestTestCase testCase) {
        // The query params in the test definition is a list of strings that looks like
        // "Foo=Bar", so we need to split the keys from the values.
        Map<String, List<String>> query = testCase.getQueryParams().stream()
            .map(pair -> {
                String[] split = pair.split("=");
                String key;
                String value = "";
                try {
                    // The strings we're given are url encoded, so we need to decode them. In an actual implementation
                    // the request we're given will have already decoded these.
                    key = URLDecoder.decode(split[0], StandardCharsets.UTF_8.toString());
                    if (split.length > 1) {
                        value = URLDecoder.decode(split[1], StandardCharsets.UTF_8.toString());
                    }
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
                return Pair.of(key, value);
            })
            // Query lists/sets will just use the same key repeatedly, so here we collect all the values that
            // share a key.
            .collect(Collectors.groupingBy(Pair::getKey, Collectors.mapping(Pair::getValue, Collectors.toList())));

        ObjectNode.Builder nodeBuilder = ObjectNode.objectNodeBuilder();
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            // The value of the query bag can either be a single string or a list, so we need to ensure individual
            // values are written out as individual strings.
            if (entry.getValue().size() == 1) {
                nodeBuilder.withMember(entry.getKey(), StringNode.from(entry.getValue().get(0)));
            } else {
                nodeBuilder.withMember(entry.getKey(), ArrayNode.fromStrings(entry.getValue()));
            }
        }
        return nodeBuilder.build();
    }

    // Ensure that the serialized request matches the expected request.
    private void writeHttpRequestAssertions(HttpRequestTestCase testCase) {
        writer.write("expect(r.method).toBe($S);", testCase.getMethod());
        writer.write("expect(r.path).toBe($S);", testCase.getUri());

        writeHttpHeaderAssertions(testCase);
        writeHttpQueryAssertions(testCase);
        writeHttpBodyAssertions(testCase);
    }

    private void writeHttpResponseAssertions(HttpResponseTestCase testCase) {
        writer.write("expect(r.statusCode).toBe($L);", testCase.getCode());
        writeHttpHeaderAssertions(testCase);
        writeHttpBodyAssertions(testCase);
    }

    private void writeHttpQueryAssertions(HttpRequestTestCase testCase) {
        testCase.getRequireQueryParams().forEach(requiredQueryParam ->
                writer.write("expect(r.query[$S]).toBeDefined();", requiredQueryParam));
        writer.write("");

        testCase.getForbidQueryParams().forEach(forbidQueryParam ->
                writer.write("expect(r.query[$S]).toBeUndefined();", forbidQueryParam));
        writer.write("");

        List<String> explicitQueryValues = testCase.getQueryParams();
        if (!explicitQueryValues.isEmpty()) {
            // Use buildQueryString like the fetch handler will.
            writer.addDependency(TypeScriptDependency.AWS_SDK_QUERYSTRING_BUILDER);
            writer.addImport("buildQueryString", "buildQueryString", "@aws-sdk/querystring-builder");

            writer.write("const queryString = buildQueryString(r.query);");
            explicitQueryValues.forEach(explicitQueryValue ->
                    writer.write("expect(queryString).toContain($S);", explicitQueryValue));
        }
        writer.write("");
    }

    private void writeHttpHeaderAssertions(HttpMessageTestCase testCase) {
        testCase.getRequireHeaders().forEach(requiredHeader -> {
            writer.write("expect(r.headers[$S]).toBeDefined();", requiredHeader.toLowerCase());
        });
        writer.write("");

        testCase.getForbidHeaders().forEach(forbidHeader ->
                writer.write("expect(r.headers[$S]).toBeUndefined();", forbidHeader.toLowerCase()));
        writer.write("");

        testCase.getHeaders().forEach((header, value) -> {
            header = header.toLowerCase();
            writer.write("expect(r.headers[$S]).toBeDefined();", header);
            writer.write("expect(r.headers[$S]).toBe($S);", header, value);
        });
        writer.write("");
    }

    private void writeHttpBodyAssertions(HttpMessageTestCase testCase) {
        testCase.getBody().ifPresent(body -> {
            // If we expect an empty body, expect it to be falsy.
            if (body.isEmpty()) {
                writer.write("expect(r.body).toBeFalsy();");
                return;
            }

            // Fast fail if we don't have a body.
            writer.write("expect(r.body).toBeDefined();");

            // Otherwise load a media type specific comparator and do a comparison.
            String mediaType = testCase.getBodyMediaType().orElse("UNKNOWN");
            String comparatorInvoke = registerBodyComparatorStub(mediaType);

            // If this is a request case then we know we're generating a client test,
            // because a request case for servers would be comparing parsed objects. We
            // need to know which is which here to be able to grab the utf8Encoder from
            // the right place.
            if (testCase instanceof HttpRequestTestCase) {
                writer.write("const utf8Encoder = client.config.utf8Encoder;");
            } else {
                writer.addImport("toUtf8", "__utf8Encoder", "@aws-sdk/util-utf8-node");
                writer.write("const utf8Encoder = __utf8Encoder;");
            }

            // Handle escaping strings with quotes inside them.
            writer.write("const bodyString = `$L`;", body.replace("\"", "\\\""));
            writer.write("const unequalParts: any = $L;", comparatorInvoke);
            writer.write("expect(unequalParts).toBeUndefined();");
        });
    }

    private String registerBodyComparatorStub(String mediaType) {
        // Load an additional stub to handle body comparisons for the
        // set of bodyMediaType values we know of.
        switch (mediaType) {
            case "application/x-www-form-urlencoded":
                additionalStubs.add("protocol-test-form-urlencoded-stub.ts");
                return "compareEquivalentFormUrlencodedBodies(bodyString, r.body.toString())";
            case "application/json":
                additionalStubs.add("protocol-test-json-stub.ts");
                return "compareEquivalentJsonBodies(bodyString, r.body.toString())";
            case "application/xml":
                writer.addDependency(TypeScriptDependency.XML_PARSER);
                writer.addDependency(TypeScriptDependency.HTML_ENTITIES);
                writer.addImport("parse", "xmlParse", "fast-xml-parser");
                writer.addImport("decodeHTML", "decodeHTML", "entities");
                additionalStubs.add("protocol-test-xml-stub.ts");
                return "compareEquivalentXmlBodies(bodyString, r.body.toString())";
            case "application/octet-stream":
                writer.addImport("Encoder", "__Encoder", "@aws-sdk/types");
                additionalStubs.add("protocol-test-octet-stream-stub.ts");
                return "compareEquivalentOctetStreamBodies(utf8Encoder, bodyString, r.body)";
            case "text/plain":
                additionalStubs.add("protocol-test-text-stub.ts");
                return "compareEquivalentTextBodies(bodyString, r.body)";
            default:
                LOGGER.warning("Unable to compare bodies with unknown media type `" + mediaType
                        + "`, defaulting to direct comparison.");
                writer.addImport("Encoder", "__Encoder", "@aws-sdk/types");
                additionalStubs.add("protocol-test-unknown-type-stub.ts");
                return "compareEquivalentUnknownTypeBodies(utf8Encoder, bodyString, r.body)";
        }
    }

    public void generateServerResponseTest(OperationShape operation, HttpResponseTestCase testCase) {
        Symbol serviceSymbol = symbolProvider.toSymbol(service);
        Symbol operationSymbol = symbolProvider.toSymbol(operation);
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        String testName = testCase.getId() + ":ServerResponse";
        openTestBlock(operation, testCase, testName, () -> {
            Symbol outputType = operationSymbol.expectProperty("outputType", Symbol.class);
            writer.openBlock("class TestService implements Partial<$T<{}>> {", "}", serviceSymbol, () -> {
                writer.openBlock("$L(input: any, ctx: {}): Promise<$T> {", "}",
                        operationSymbol.getName(), outputType, () -> {
                    Optional<ShapeId> outputOptional = operation.getOutput();
                    if (outputOptional.isPresent()) {
                        StructureShape outputShape = model.expectShape(outputOptional.get(), StructureShape.class);
                        writer.writeInline("let response = ");
                        testCase.getParams().accept(new CommandInputNodeVisitor(outputShape, true));
                        writer.write("return Promise.resolve({ ...response, '$$metadata': {} });");
                    } else {
                        writer.write("return Promise.resolve({ '$$metadata': {} });");
                    }
                });
            });
            writeServerResponseTest(operation, testCase);
        });
    }

    private void generateResponseTest(OperationShape operation, HttpResponseTestCase testCase) {
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        String testName = testCase.getId() + ":Response";
        openTestBlock(operation, testCase, testName, () -> {
            writeResponseTestSetup(operation, testCase, true);

            // Invoke the handler and look for the expected response to then perform assertions.
            writer.write("let r: any;");
            writer.write("try {\n"
                       + "  r = await client.send(command);\n"
                       + "} catch (err) {\n"
                       + "  fail('Expected a valid response to be returned, got err.');\n"
                       + "  return;\n"
                       + "}");
            writeResponseAssertions(operation, testCase);
        });
    }

    private void generateServerErrorResponseTest(
            OperationShape operation,
            StructureShape error,
            HttpResponseTestCase testCase
    ) {
        Symbol serviceSymbol = symbolProvider.toSymbol(service);
        Symbol operationSymbol = symbolProvider.toSymbol(operation);
        Symbol outputType = operationSymbol.expectProperty("outputType", Symbol.class);
        Symbol errorSymbol = symbolProvider.toSymbol(error);
        ErrorTrait errorTrait = error.expectTrait(ErrorTrait.class);

        testCase.getDocumentation().ifPresent(writer::writeDocs);
        String testName = testCase.getId() + ":ServerErrorResponse";
        openTestBlock(operation, testCase, testName, () -> {
            // Generates a Partial implementation of the service type that only includes
            // the specific operation under test. Later we'll have to "cast" this with an "as",
            // but using the partial in the meantime will give us proper type checking on the
            // operation we want.
            writer.openBlock("class TestService implements Partial<$T<{}>> {", "}", serviceSymbol, () -> {
                writer.openBlock("$L(input: any, ctx: {}): Promise<$T> {", "}",
                    operationSymbol.getName(), outputType, () -> {
                        // Write out an object according to what's defined in the test case.
                        writer.writeInline("const response = ");
                        testCase.getParams().accept(new CommandInputNodeVisitor(error, true));

                        // Add in the necessary wrapping information to make the error satisfy its interface.
                        // TODO: having proper constructors for these errors would be really nice so we don't
                        // have to do this.
                        writer.openBlock("const error: $T = {", "};", errorSymbol, () -> {
                            writer.write("...response,");
                            writer.write("name: $S,", error.getId().getName());
                            writer.write("$$fault: $S,", errorTrait.isClientError() ? "client" : "server");
                            writer.write("$$metadata: {},");
                        });
                        writer.write("throw error;");
                    });
            });
            writeServerResponseTest(operation, testCase);
        });
    }

    private void writeServerResponseTest(OperationShape operation, HttpResponseTestCase testCase) {
        Symbol serviceSymbol = symbolProvider.toSymbol(service);
        Symbol operationSymbol = symbolProvider.toSymbol(operation);
        Symbol handlerSymbol = serviceSymbol.expectProperty("handler", Symbol.class);
        Symbol serializerSymbol = operationSymbol.expectProperty("serializerType", Symbol.class);
        Symbol serviceOperationsSymbol = serviceSymbol.expectProperty("operations", Symbol.class);
        writer.write("const service: any = new TestService()");

        // There's a lot of setup here, including creating our own mux, serializers list, and ultimately
        // our own service handler. This is largely in service of avoiding having to go through the
        // request deserializer
        writer.addImport("httpbinding", null, "@aws-smithy/server-common");
        writer.openBlock("const testMux = new httpbinding.HttpBindingMux<$S, keyof $T<{}>>([", "]);",
            service.getId().getName(), serviceSymbol, () -> {
                writer.openBlock("new httpbinding.UriSpec<$S, $S>('POST', [], [], {", "}),",
                    service.getId().getName(), operation.getId().getName(), () -> {
                        writer.write("service: $S,", service.getId().getName());
                        writer.write("operation: $S,", operation.getId().getName());
                    });
            });

        // Extend the existing serializer and replace the deserialize with a noop so we don't have to
        // worry about trying to construct something that matches.
        writer.openBlock("class TestSerializer extends $T {", "}", serializerSymbol, () -> {
            writer.openBlock("deserialize = (output: any, context: any): Promise<any> => {", "};", () -> {
                writer.write("return Promise.resolve({});");
            });
        });

        // Since we aren't going through the deserializer, we don't have to put much in the fake request.
        // Just enough to get it through our test mux.
        writer.write("const request = new HttpRequest({method: 'POST', hostname: 'example.com'});");

        // Create a new serializer factory that always returns our test serializer.
        writer.addImport("SmithyException", "__SmithyException", "@aws-sdk/types");
        writer.addImport("OperationSerializer", "__OperationSerializer", "@aws-smithy/server-common");
        writer.openBlock("const serFn: (op: $1T) => __OperationSerializer<$2T<{}>, $1T, __SmithyException> = (op) =>"
                        + " { return new TestSerializer(); };", serviceOperationsSymbol, serviceSymbol);

        writer.addImport("serializeFrameworkException", null,
                "./protocols/" + ProtocolGenerator.getSanitizedName(protocolGenerator.getName()));
        writer.addImport("ValidationFailure", "__ValidationFailure", "@aws-smithy/server-common");
        writer.write("const handler = new $T(service, testMux, serFn, serializeFrameworkException, "
                + "(ctx: {}, f: __ValidationFailure[]) => { if (f) { throw f; } return undefined;});", handlerSymbol);
        writer.write("let r = await handler.handle(request, {})").write("");
        writeHttpResponseAssertions(testCase);
    }

    private void generateErrorResponseTest(
            OperationShape operation,
            StructureShape error,
            HttpResponseTestCase testCase
    ) {
        Symbol errorSymbol = symbolProvider.toSymbol(error);

        // Use a compound test_case name so we generate unique tests
        // for each error on each operation safely. This is useful in validating
        // that operation parsers are all correctly identifying errors and that
        // we can test for any operation specific values properly.
        String testName = testCase.getId() + ":Error:" + operation.getId().getName();
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        openTestBlock(operation, testCase, testName, () -> {
            writeResponseTestSetup(operation, testCase, false);

            // Invoke the handler and look for the expected exception to then perform assertions.
            writer.write("try {\n"
                       + "  await client.send(command);\n"
                       + "} catch (err) {\n"
                       + "  if (err.name !== \"$T\") {\n"
                       + "    console.log(err);\n"
                       + "    fail(`Expected a $L to be thrown, got $${err.name} instead`);\n"
                       + "    return;\n"
                       + "  }\n"
                       + "  const r: any = err;", errorSymbol, error.getId().getName())
                    .indent()
                    .call(() -> writeResponseAssertions(error, testCase))
                    .write("return;")
                    .dedent()
                    .write("}");
            writer.write("fail('Expected an exception to be thrown from response');");
        });
    }

    private void writeResponseTestSetup(OperationShape operation, HttpResponseTestCase testCase, boolean isSuccess) {
        Symbol operationSymbol = symbolProvider.toSymbol(operation);

        // Lowercase all the headers we're expecting as this is what we'll get.
        Map<String, String> headers = testCase.getHeaders().entrySet().stream()
                .map(entry -> new Pair<>(entry.getKey().toLowerCase(Locale.US), entry.getValue()))
                .collect(MapUtils.toUnmodifiableMap(Pair::getLeft, Pair::getRight));
        String headerParameters = Node.prettyPrintJson(ObjectNode.fromStringMap(headers));
        String body = testCase.getBody().orElse(null);

        // Create a client with a custom request handler that intercepts requests.
        writer.openBlock("const client = new $T({", "});\n", serviceSymbol, () -> {
                writer.write("...clientParams,");
                writer.openBlock("requestHandler: new ResponseDeserializationTestHandler(", ")", () -> {
                    writer.write("$L,", isSuccess);
                    writer.write("$L,", testCase.getCode());
                    writer.write("$L,", headers.isEmpty() ? "undefined" : headerParameters);
                    if (body != null) {
                        writer.write("`$L`,", body);
                    }
                });
            });

        // Set the command's parameters to empty, using the any type to
        // trick TS in to letting us send this command through.
        writer.write("const params: any = {};");
        writer.write("const command = new $T(params);\n", operationSymbol);

    }

    // Ensure that the serialized response matches the expected response.
    private void writeResponseAssertions(Shape operationOrError, HttpResponseTestCase testCase) {
        writer.write("expect(r['$$metadata'].httpStatusCode).toBe($L);", testCase.getCode());

        writeReponseParamAssertions(operationOrError, testCase);
    }

    private void writeRequestParamAssertions(OperationShape operation, HttpRequestTestCase testCase) {
        ObjectNode params = testCase.getParams();
        if (!params.isEmpty()) {
            StructureShape testInputShape = model.expectShape(
                    operation.getInput().orElseThrow(() -> new CodegenException("Foo")),
                    StructureShape.class);

            // Use this trick wrapper to not need more complex trailing comma handling.
            writer.write("const paramsToValidate: any = [")
                    .call(() -> params.accept(new CommandOutputNodeVisitor(testInputShape)))
                    .write("][0];");

            // Extract a payload binding if present.
            Optional<HttpBinding> pb = Optional.empty();
            HttpBindingIndex index = HttpBindingIndex.of(model);
            List<HttpBinding> payloadBindings = index.getRequestBindings(operation, Location.PAYLOAD);
            if (!payloadBindings.isEmpty()) {
                pb = Optional.of(payloadBindings.get(0));
            }
            final Optional<HttpBinding> payloadBinding = pb;

            writeParamAssertions(writer, payloadBinding, () -> {
                // TODO: replace this with a collector from the server config once it's available
                writer.addImport("streamCollector", "__streamCollector", "@aws-sdk/node-http-handler");
                writer.write("const comparableBlob = await __streamCollector(r[$S]);",
                        payloadBinding.get().getMemberName());
            });
        }
    }

    private void writeReponseParamAssertions(Shape operationOrError, HttpResponseTestCase testCase) {
        ObjectNode params = testCase.getParams();
        if (!params.isEmpty()) {
            StructureShape testOutputShape;
            if (operationOrError.isStructureShape()) {
                testOutputShape = operationOrError.asStructureShape().get();
            } else {
                testOutputShape = model.expectShape(
                        operationOrError.asOperationShape().get().getOutput()
                                .orElseThrow(() -> new CodegenException("Foo")),
                        StructureShape.class);
            }

            // Use this trick wrapper to not need more complex trailing comma handling.
            writer.write("const paramsToValidate: any = [")
                    .call(() -> params.accept(new CommandOutputNodeVisitor(testOutputShape)))
                    .write("][0];");

            // Extract a payload binding if present.
            Optional<HttpBinding> payloadBinding = operationOrError.asOperationShape()
                    .map(operationShape -> {
                        HttpBindingIndex index = HttpBindingIndex.of(model);
                        List<HttpBinding> payloadBindings = index.getResponseBindings(operationOrError,
                                Location.PAYLOAD);
                        if (!payloadBindings.isEmpty()) {
                            return payloadBindings.get(0);
                        }
                        return null;
                    });

            writeParamAssertions(writer, payloadBinding, () -> {
                writer.write("const comparableBlob = await client.config.streamCollector(r[$S]);",
                        payloadBinding.get().getMemberName());
            });
        }
    }

    private void writeParamAssertions(
            TypeScriptWriter writer,
            Optional<HttpBinding> payloadBinding,
            Runnable writeComparableBlob
    ) {
        // If we have a streaming payload blob, we need to collect it to something that
        // can be compared with the test contents. This emulates the customer experience.
        boolean hasStreamingPayloadBlob = payloadBinding
                .map(binding ->
                        model.getShape(binding.getMember().getTarget())
                                .filter(Shape::isBlobShape)
                                .filter(s -> s.hasTrait(StreamingTrait.ID))
                                .isPresent())
                .orElse(false);

        if (hasStreamingPayloadBlob) {
            writeComparableBlob.run();
        }

        // Perform parameter comparisons.
        writer.openBlock("Object.keys(paramsToValidate).forEach(param => {", "});", () -> {
            writer.write("expect(r[param]).toBeDefined();");
            if (hasStreamingPayloadBlob) {
                writer.openBlock("if (param === $S) {", "} else {", payloadBinding.get().getMemberName(), () ->
                        writer.write("expect(equivalentContents(comparableBlob, "
                                + "paramsToValidate[param])).toBe(true);"));
                writer.indent();
            }

            writer.write("expect(equivalentContents(r[param], paramsToValidate[param])).toBe(true);");

            if (hasStreamingPayloadBlob) {
                writer.dedent();
                writer.write("}");
            }
        });
    }

    private void openTestBlock(
            OperationShape operation,
            HttpMessageTestCase testCase,
            String testName,
            Runnable f
    ) {
        // Skipped tests are still generated, just not run.
        if (testFilter.skip(service, operation, testCase, settings)) {
            writer.openBlock("it.skip($S, async() => {", "});\n", testName, f);
        } else {
            writer.openBlock("it($S, async () => {", "});\n", testName, f);
        }
    }

    /**
     * Supports writing out TS specific input types in the generated code
     * through visiting the target shape at the same time as the node. If
     * instead we just printed out the node, many values would not match on
     * type signatures.
     *
     * This handles properly generating Set types for Set shapes, Date types
     * for numbers that are Timestamp shapes, "undefined" for nulls, boolean
     * values, and auto-filling idempotency token Structure members.
     */
    private final class CommandInputNodeVisitor implements NodeVisitor<Void> {
        private final StructureShape inputShape;
        private Shape workingShape;
        private boolean appendSemicolon;

        private CommandInputNodeVisitor(StructureShape inputShape) {
            this(inputShape, false);
        }

        private CommandInputNodeVisitor(StructureShape inputShape, boolean appendSemicolon) {
            this.inputShape = inputShape;
            this.workingShape = inputShape;
            this.appendSemicolon = appendSemicolon;
        }

        @Override
        public Void arrayNode(ArrayNode node) {
            String openElement = "[";
            String closeElement = "]";

            // Write the value out directly.
            writer.openBlock("$L\n", closeElement + ",\n", openElement, () -> {
                Shape wrapperShape = this.workingShape;
                node.getElements().forEach(element -> {
                    // Swap the working shape to the member of the collection.
                    // This isn't necessary if the shape is a document.
                    if (wrapperShape instanceof CollectionShape) {
                        this.workingShape = model.expectShape(((CollectionShape) wrapperShape).getMember().getTarget());
                    }
                    writer.call(() -> element.accept(this)).write("\n");
                });
                this.workingShape = wrapperShape;
            });
            return null;
        }

        @Override
        public Void booleanNode(BooleanNode node) {
            // Handle needing to write the boolean's value properly.
            writer.write(node.getValue() ? "true," : "false,");
            return null;
        }

        @Override
        public Void nullNode(NullNode node) {
            writer.write("null,");
            return null;
        }

        @Override
        public Void numberNode(NumberNode node) {
            // Handle timestamps needing to be converted from numbers to their input type of Date.
            // Also handle that a Date in TS takes milliseconds, so add 000 to the end.
            if (workingShape.isTimestampShape()) {
                writer.write("new Date($L000),", node.getValue());
            } else {
                writer.write("$L,", node.getValue().toString());
            }
            return null;
        }

        @Override
        public Void objectNode(ObjectNode node) {
            // Short circuit document types, as the direct value is what we want.
            if (workingShape.isDocumentShape()) {
                writer.writeInline(Node.prettyPrintJson(node));
                return null;
            }

            // Both objects and maps can use a majority of the same logic.
            // Use "as any" to have TS complain less about undefined entries.
            String suffix = "} as any";

            // When generating a server response test, we need the top level structure to have a semicolon
            // rather than a comma.
            if (appendSemicolon) {
                suffix += ";";
                appendSemicolon = false;
            } else {
                suffix += ",\n";
            }

            writer.openBlock("{", suffix, () -> {
                Shape wrapperShape = this.workingShape;
                node.getMembers().forEach((keyNode, valueNode) -> {
                    if (keyNode.getValue().matches("[^\\w]+")) {
                        writer.writeInline("$L: ", keyNode.getValue());
                    } else {
                        writer.writeInline("$S: ", keyNode.getValue());
                    }

                    // Grab the correct member related to the node member we have.
                    MemberShape memberShape;
                    if (wrapperShape.isStructureShape()) {
                        memberShape = wrapperShape.asStructureShape().get().getMember(keyNode.getValue()).get();
                    } else if (wrapperShape.isUnionShape()) {
                        memberShape = wrapperShape.asUnionShape().get().getMember(keyNode.getValue()).get();
                    } else if (wrapperShape.isMapShape()) {
                        memberShape = wrapperShape.asMapShape().get().getValue();
                    } else {
                        throw new CodegenException("Unknown shape type for object node when "
                                + "generating protocol test input: " + wrapperShape.getType());
                    }

                    // Handle auto-filling idempotency token values to the explicit value.
                    if (isIdempotencyTokenWithoutValue(memberShape, valueNode)) {
                        writer.write("\"00000000-0000-4000-8000-000000000000\",");
                    } else {
                        this.workingShape = model.expectShape(memberShape.getTarget());
                        writer.call(() -> valueNode.accept(this));
                    }
                    writer.write("\n");
                });
                // Check for setting a potentially unspecified member value for the
                // idempotency token.
                if (node.getMembers().isEmpty() && wrapperShape.isStructureShape()) {
                    StructureShape structureShape = wrapperShape.asStructureShape().get();
                    for (Map.Entry<String, MemberShape> entry : structureShape.getAllMembers().entrySet()) {
                        if (entry.getValue().hasTrait(IdempotencyTokenTrait.class)) {
                            writer.write("$L: \"00000000-0000-4000-8000-000000000000\",", entry.getKey());
                        }
                    }
                }
                this.workingShape = wrapperShape;
            });
            return null;
        }

        private boolean isIdempotencyTokenWithoutValue(MemberShape memberShape, Node valueNode) {
            // Short circuit non-tokens.
            if (!memberShape.hasTrait(IdempotencyTokenTrait.class)) {
                return false;
            }

            // Return if the token has a test-specific value.
            return valueNode.expectStringNode().getValue().isEmpty();
        }

        @Override
        public Void stringNode(StringNode node) {
            // Handle blobs needing to be converted from strings to their input type of UInt8Array.
            if (workingShape.isBlobShape()) {
                writer.write("Uint8Array.from($S, c => c.charCodeAt(0)),", node.getValue());
            } else if (workingShape.isFloatShape() || workingShape.isDoubleShape()) {
                switch (node.getValue()) {
                    case "NaN":
                        writer.write("NaN,");
                        break;
                    case "Infinity":
                        writer.write("Infinity,");
                        break;
                    case "-Infinity":
                        writer.write("-Infinity,");
                        break;
                    default:
                        throw new CodegenException(String.format(
                                "Unexpected string value for `%s`: \"%s\"", workingShape.getId(), node.getValue()));
                }
            } else {
                writer.write("$S,", node.getValue());
            }
            return null;
        }
    }

    /**
     * Functional interface for skipping tests.
     */
    @FunctionalInterface
    public interface TestFilter {
        /**
         * A function that determines whether or not to skip a test.
         *
         * <p>A test might be temporarily skipped if it's a known failure that
         * will be addressed later, or if the test in question asserts a
         * serialized message that can have multiple valid forms.
         *
         * @param service The service for which tests are being generated.
         * @param operation The operation for which tests are being generated.
         * @param testCase The test case in question.
         * @param settings The settings being used to generate the test service.
         * @return True if the test should be skipped, false otherwise.
         */
        boolean skip(
                ServiceShape service,
                OperationShape operation,
                HttpMessageTestCase testCase,
                TypeScriptSettings settings
        );
    }

    /**
     * Supports writing out TS specific output types in the generated code
     * through visiting the target shape at the same time as the node. If
     * instead we just printed out the node, many values would not match on
     * type signatures.
     *
     * This handles properly generating Date types for numbers that are
     * Timestamp shapes, downcasing prefix headers, boolean values, Uint8Array
     * types for blobs, and error Message field standardization.
     */
    private final class CommandOutputNodeVisitor implements NodeVisitor<Void> {
        private final StructureShape outputShape;
        private Shape workingShape;

        private CommandOutputNodeVisitor(StructureShape outputShape) {
            this.outputShape = outputShape;
            this.workingShape = outputShape;
        }

        @Override
        public Void arrayNode(ArrayNode node) {
            String openElement = "[";
            String closeElement = "]";

            // Write the value out directly.
            writer.openBlock("$L\n", closeElement + ",\n", openElement, () -> {
                Shape wrapperShape = this.workingShape;
                node.getElements().forEach(element -> {
                    // Swap the working shape to the member of the collection.
                    // This isn't necessary if the shape is a document.
                    if (wrapperShape instanceof CollectionShape) {
                        this.workingShape = model.expectShape(((CollectionShape) wrapperShape).getMember().getTarget());
                    }
                    writer.call(() -> element.accept(this)).write("\n");
                });
                this.workingShape = wrapperShape;
            });
            return null;
        }

        @Override
        public Void booleanNode(BooleanNode node) {
            // Handle needing to write the boolean's value properly.
            writer.write(node.getValue() ? "true," : "false,");
            return null;
        }

        @Override
        public Void nullNode(NullNode node) {
            // Nulls on the wire are nulls in parsed content.
            writer.write("null,");
            return null;
        }

        @Override
        public Void numberNode(NumberNode node) {
            // Handle timestamps needing to be converted from numbers to their input type of Date.
            // Also handle that a Date in TS takes milliseconds, so add 000 to the end.
            if (workingShape.isTimestampShape()) {
                writer.write("new Date($L000),", node.getValue());
            } else {
                writer.write("$L,", node.getValue().toString());
            }
            return null;
        }

        @Override
        public Void objectNode(ObjectNode node) {
            // Short circuit document types, as the direct value is what we want.
            if (workingShape.isDocumentShape()) {
                writer.writeInline(Node.prettyPrintJson(node));
                return null;
            }

            // Both objects and maps can use a majority of the same logic.
            // Use "as any" to have TS complain less about undefined entries.
            writer.openBlock("{", "},\n", () -> {
                Shape wrapperShape = this.workingShape;
                node.getMembers().forEach((keyNode, valueNode) -> {
                    // Grab the correct member related to the node member we have.
                    MemberShape memberShape;
                    if (wrapperShape.isStructureShape()) {
                        memberShape = wrapperShape.asStructureShape().get().getMember(keyNode.getValue()).get();
                    } else if (wrapperShape.isUnionShape()) {
                        memberShape = wrapperShape.asUnionShape().get().getMember(keyNode.getValue()).get();
                    } else if (wrapperShape.isMapShape()) {
                        memberShape = wrapperShape.asMapShape().get().getValue();
                    } else {
                        throw new CodegenException("Unknown shape type for object node when "
                                + "generating protocol test output: " + wrapperShape.getType());
                    }

                    // Handle error standardization to the down-cased "message".
                    String validationName = keyNode.getValue();
                    if (wrapperShape.hasTrait(ErrorTrait.class) && validationName.equals("Message")) {
                        validationName = "message";
                    }
                    writer.write("$S: ", validationName);

                    this.workingShape = model.expectShape(memberShape.getTarget());
                    // Alter valueNode to downcase keys if it's a map for prefixHeaders.
                    // This is an enforced behavior of the fetch handler.
                    Node renderNode = memberShape.hasTrait(HttpPrefixHeadersTrait.class)
                            ? downcaseNodeKeys(valueNode.expectObjectNode())
                            : valueNode;
                    writer.call(() -> renderNode.accept(this));
                    writer.write("\n");
                });
                this.workingShape = wrapperShape;
            });
            return null;
        }

        private ObjectNode downcaseNodeKeys(ObjectNode startingNode) {
            ObjectNode downcasedNode = Node.objectNode();
            for (Map.Entry<StringNode, Node> entry : startingNode.getMembers().entrySet()) {
                downcasedNode = downcasedNode.withMember(entry.getKey().getValue().toLowerCase(Locale.US),
                        entry.getValue());
            }
            return downcasedNode;
        }

        @Override
        public Void stringNode(StringNode node) {
            // Handle blobs needing to be converted from strings to their input type of UInt8Array.
            if (workingShape.isBlobShape()) {
                writer.write("Uint8Array.from($S, c => c.charCodeAt(0)),", node.getValue());
            } else if (workingShape.isFloatShape() || workingShape.isDoubleShape()) {
                switch (node.getValue()) {
                    case "NaN":
                        writer.write("NaN,");
                        break;
                    case "Infinity":
                        writer.write("Infinity,");
                        break;
                    case "-Infinity":
                        writer.write("-Infinity,");
                        break;
                    default:
                        throw new CodegenException(String.format(
                                "Unexpected string value for `%s`: \"%s\"", workingShape.getId(), node.getValue()));
                }
            } else {
                writer.write("$S,", node.getValue());
            }
            return null;
        }
    }
}
