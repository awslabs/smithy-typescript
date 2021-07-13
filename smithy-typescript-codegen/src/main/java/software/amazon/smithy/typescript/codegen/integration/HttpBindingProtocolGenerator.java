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

package software.amazon.smithy.typescript.codegen.integration;

import static software.amazon.smithy.model.knowledge.HttpBinding.Location;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.pattern.SmithyPattern.Segment;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.NumberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EndpointTrait;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.EventHeaderTrait;
import software.amazon.smithy.model.traits.EventPayloadTrait;
import software.amazon.smithy.model.traits.HostLabelTrait;
import software.amazon.smithy.model.traits.HttpErrorTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.typescript.codegen.ApplicationProtocol;
import software.amazon.smithy.typescript.codegen.CodegenUtils;
import software.amazon.smithy.typescript.codegen.FrameworkErrorModel;
import software.amazon.smithy.typescript.codegen.TypeScriptDependency;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.OptionalUtils;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Abstract implementation useful for all protocols that use HTTP bindings.
 */
@SmithyUnstableApi
public abstract class HttpBindingProtocolGenerator implements ProtocolGenerator {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private final Set<Shape> serializingDocumentShapes = new TreeSet<>();
    private final Set<Shape> deserializingDocumentShapes = new TreeSet<>();
    private final Set<StructureShape> serializingErrorShapes = new TreeSet<>();
    private final Set<StructureShape> deserializingErrorShapes = new TreeSet<>();
    private final Set<StructureShape> serializeEventShapes = new TreeSet<>();
    private final Set<StructureShape> deserializingEventShapes = new TreeSet<>();
    private final Set<UnionShape> serializeEventUnions = new TreeSet<>();
    private final Set<UnionShape> deserializeEventUnions = new TreeSet<>();
    private final boolean isErrorCodeInBody;

    /**
     * Creates a Http binding protocol generator.
     *
     * @param isErrorCodeInBody A boolean that indicates if the error code for the implementing protocol is located in
     *   the error response body, meaning this generator will parse the body before attempting to load an error code.
     */
    public HttpBindingProtocolGenerator(boolean isErrorCodeInBody) {
        this.isErrorCodeInBody = isErrorCodeInBody;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    /**
     * Gets the default serde format for timestamps.
     *
     * @return Returns the default format.
     */
    protected abstract Format getDocumentTimestampFormat();

    /**
     * Gets the default content-type when a document is synthesized in the body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@link HttpBindingProtocolGenerator#serializeInputDocumentBody}. The {@link DocumentShapeSerVisitor} and
     * {@link DocumentMemberSerVisitor} are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate serialization for.
     */
    protected abstract void generateDocumentBodyShapeSerializers(GenerationContext context, Set<Shape> shapes);

    /**
     * Generates deserialization functions for shapes in the passed set. These functions
     * should return a value that can then be deserialized by the implementation of
     * {@link HttpBindingProtocolGenerator#deserializeInputDocumentBody}. The {@link DocumentShapeDeserVisitor} and
     * {@link DocumentMemberDeserVisitor} are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentBodyShapeDeserializers(GenerationContext context, Set<Shape> shapes);

    @Override
    public void generateSharedComponents(GenerationContext context) {
        serializeEventUnions.forEach(eventUnion -> generateSerializingEventUnion(context, eventUnion));
        deserializeEventUnions.forEach(eventUnion -> generateDeserializingEventUnion(context, eventUnion));
        serializeEventShapes.forEach(event -> generateEventSerializer(context, event));
        deserializingEventShapes.forEach(event -> generateEventDeserializer(context, event));
        deserializingErrorShapes.forEach(error -> generateErrorDeserializer(context, error));
        serializingErrorShapes.forEach(error -> generateErrorSerializer(context, error));
        generateDocumentBodyShapeSerializers(context, serializingDocumentShapes);
        generateDocumentBodyShapeDeserializers(context, deserializingDocumentShapes);
        HttpProtocolGeneratorUtils.generateMetadataDeserializer(context, getApplicationProtocol().getResponseType());
        HttpProtocolGeneratorUtils.generateCollectBody(context);
        HttpProtocolGeneratorUtils.generateCollectBodyString(context);
        HttpProtocolGeneratorUtils.generateHttpBindingUtils(context);
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        TopDownIndex topDownIndex = TopDownIndex.of(context.getModel());

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationRequestSerializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol request bindings for %s because it does not have an "
                            + "http binding trait", getName(), operation.getId())));
        }
    }

    @Override
    public void generateRequestDeserializers(GenerationContext context) {
        TopDownIndex topDownIndex = TopDownIndex.of(context.getModel());

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationRequestDeserializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol request bindings for %s because it does not have an "
                            + "http binding trait", getName(), operation.getId())));
        }

    }

    @Override
    public void generateResponseSerializers(GenerationContext context) {
        TopDownIndex topDownIndex = TopDownIndex.of(context.getModel());

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationResponseSerializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol response bindings for %s because it does not have an "
                            + "http binding trait", getName(), operation.getId())));
        }
    }

    @Override
    public void generateFrameworkErrorSerializer(GenerationContext inputContext) {
        final GenerationContext context = inputContext.copy();
        context.setModel(FrameworkErrorModel.INSTANCE.getModel());

        SymbolReference responseType = getApplicationProtocol().getResponseType();
        HttpBindingIndex bindingIndex = HttpBindingIndex.of(context.getModel());
        TypeScriptWriter writer = context.getWriter();

        writer.addImport("SmithyFrameworkException", "__SmithyFrameworkException", "@aws-smithy/server-common");
        writer.addUseImports(responseType);
        writer.addImport("ServerSerdeContext", null, "@aws-smithy/server-common");

        writer.openBlock("export const serializeFrameworkException = async(\n"
                + "  input: __SmithyFrameworkException,\n"
                + "  ctx: ServerSerdeContext\n"
                + "): Promise<$T> => {", "}", responseType, () -> {

            writeEmptyEndpoint(context);

            writer.openBlock("switch (input.name) {", "}", () -> {
                for (final Shape shape : new TreeSet<>(context.getModel().getShapesWithTrait(HttpErrorTrait.class))) {
                    StructureShape errorShape = shape.asStructureShape().orElseThrow(IllegalArgumentException::new);
                    writer.openBlock("case $S: {", "}", errorShape.getId().getName(), () -> {
                        generateErrorSerializationImplementation(context, errorShape, responseType, bindingIndex);
                    });
                }
            });
        });
        writer.write("");
    }

    private void generateServiceMux(GenerationContext context) {
        TopDownIndex topDownIndex = TopDownIndex.of(context.getModel());
        TypeScriptWriter writer = context.getWriter();

        writer.addImport("httpbinding", null, "@aws-smithy/server-common");

        Symbol serviceSymbol = context.getSymbolProvider().toSymbol(context.getService());

        writer.openBlock("const mux = new httpbinding.HttpBindingMux<$S, keyof $T<Context>>([", "]);",
                context.getService().getId().getName(),
                serviceSymbol,
                () -> {
                    for (OperationShape operation : topDownIndex.getContainedOperations(context.getService())) {
                        OptionalUtils.ifPresentOrElse(
                                operation.getTrait(HttpTrait.class),
                                httpTrait -> generateUriSpec(context, operation, httpTrait),
                                () -> LOGGER.warning(String.format(
                                        "Unable to generate %s uri spec for %s because it does not have an "
                                                + "http binding trait", getName(), operation.getId())));

                    }
               }
       );
    }

    private void generateOperationMux(GenerationContext context, OperationShape operation) {
        TypeScriptWriter writer = context.getWriter();

        writer.addImport("httpbinding", null, "@aws-smithy/server-common");

        writer.openBlock("const mux = new httpbinding.HttpBindingMux<$S, $S>([", "]);",
                context.getService().getId().getName(),
                operation.getId().getName(),
                () -> {
                    HttpTrait httpTrait = operation.expectTrait(HttpTrait.class);
                    generateUriSpec(context, operation, httpTrait);
                }
        );
    }

    private void generateUriSpec(GenerationContext context,
                                 OperationShape operation,
                                 HttpTrait httpTrait) {
        TypeScriptWriter writer = context.getWriter();

        String serviceName = context.getService().getId().getName();

        writer.openBlock("new httpbinding.UriSpec<$S, $S>(", "),",
                serviceName,
                operation.getId().getName(),
                () -> {
                    writer.write("'$L',", httpTrait.getMethod());
                    writer.openBlock("[", "],", () -> {
                        for (Segment s : httpTrait.getUri().getSegments()) {
                            if (s.isGreedyLabel()) {
                                writer.write("{ type: 'greedy' },");
                            } else if (s.isLabel()) {
                                writer.write("{ type: 'path' },");
                            } else {
                                writer.write("{ type: 'path_literal', value: $S },", s.getContent());
                            }
                        }
                    });
                    writer.openBlock("[", "],", () -> {
                        for (Map.Entry<String, String> e : httpTrait.getUri().getQueryLiterals().entrySet()) {
                            if (e.getValue() == null) {
                                writer.write("{ type: 'query_literal', key: $S },", e.getKey());
                            } else {
                                writer.write("{ type: 'query_literal', key: $S, value: $S },",
                                        e.getKey(), e.getValue());
                            }
                        }
                        operation.getInput().ifPresent(inputId -> {
                            StructureShape inputShape = context.getModel().expectShape(inputId, StructureShape.class);
                            for (MemberShape ms : inputShape.members()) {
                                if (ms.isRequired() && ms.hasTrait(HttpQueryTrait.class)) {
                                    HttpQueryTrait queryTrait = ms.expectTrait(HttpQueryTrait.class);
                                    writer.write("{ type: 'query', key: $S },", queryTrait.getValue());
                                }
                            }
                        });
                    });
                    writer.writeInline("{ service: $S, operation: $S }",
                            serviceName, operation.getId().getName());
                });
    }

    @Override
    public void generateServiceHandlerFactory(GenerationContext context) {
        TypeScriptWriter writer = context.getWriter();
        TopDownIndex index = TopDownIndex.of(context.getModel());
        Set<OperationShape> operations = index.getContainedOperations(context.getService());
        SymbolProvider symbolProvider = context.getSymbolProvider();

        writer.addImport("serializeFrameworkException", null,
                "./protocols/" + ProtocolGenerator.getSanitizedName(getName()));
        writer.addImport("ValidationCustomizer", "__ValidationCustomizer", "@aws-smithy/server-common");
        writer.addImport("HttpRequest", "__HttpRequest", "@aws-sdk/protocol-http");
        writer.addImport("HttpResponse", "__HttpResponse", "@aws-sdk/protocol-http");

        Symbol serviceSymbol = symbolProvider.toSymbol(context.getService());
        Symbol handlerSymbol = serviceSymbol.expectProperty("handler", Symbol.class);
        Symbol operationsSymbol = serviceSymbol.expectProperty("operations", Symbol.class);

        if (context.getSettings().isDisableDefaultValidation()) {
            writer.write("export const get$L = <Context>(service: $T<Context>, "
                            + "customizer: __ValidationCustomizer<$T>): "
                            + "__ServiceHandler<Context, __HttpRequest, __HttpResponse> => {",
                    handlerSymbol.getName(), serviceSymbol, operationsSymbol);
        } else {
            writer.write("export const get$L = <Context>(service: $T<Context>): "
                            + "__ServiceHandler<Context, __HttpRequest, __HttpResponse> => {",
                    handlerSymbol.getName(), serviceSymbol);
        }
        writer.indent();

        generateServiceMux(context);
        writer.addImport("SmithyException", "__SmithyException", "@aws-sdk/types");
        writer.openBlock("const serFn: (op: $1T) => __OperationSerializer<$2T<Context>, $1T, __SmithyException> = "
                        + "(op) => {", "};", operationsSymbol, serviceSymbol, () -> {
            writer.openBlock("switch (op) {", "}", () -> {
                operations.stream()
                        .filter(o -> o.getTrait(HttpTrait.class).isPresent())
                        .forEach(writeOperationCase(writer, symbolProvider));
            });
        });

        if (!context.getSettings().isDisableDefaultValidation()) {
            writer.addImport("generateValidationSummary", "__generateValidationSummary", "@aws-smithy/server-common");
            writer.addImport("generateValidationMessage", "__generateValidationMessage", "@aws-smithy/server-common");
            writer.openBlock("const customizer: __ValidationCustomizer<$T> = (ctx, failures) => {", "};",
                operationsSymbol,
                () -> {
                    writeDefaultValidationCustomizer(writer);
                }
            );
        }

        writer.write("return new $T(service, mux, serFn, serializeFrameworkException, customizer);", handlerSymbol);

        writer.dedent().write("}");
    }

    @Override
    public void generateOperationHandlerFactory(GenerationContext context, OperationShape operation) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();

        writer.addImport("serializeFrameworkException", null,
                "./protocols/" + ProtocolGenerator.getSanitizedName(getName()));
        writer.addImport("HttpRequest", "__HttpRequest", "@aws-sdk/protocol-http");
        writer.addImport("HttpResponse", "__HttpResponse", "@aws-sdk/protocol-http");

        final Symbol operationSymbol = symbolProvider.toSymbol(operation);
        final Symbol inputType = operationSymbol.expectProperty("inputType", Symbol.class);
        final Symbol outputType = operationSymbol.expectProperty("outputType", Symbol.class);
        final Symbol serializerType = operationSymbol.expectProperty("serializerType", Symbol.class);
        final Symbol operationHandlerSymbol = operationSymbol.expectProperty("handler", Symbol.class);

        if (context.getSettings().isDisableDefaultValidation()) {
            writer.write("export const get$L = <Context>(operation: __Operation<$T, $T, Context>, "
                            + "customizer: __ValidationCustomizer<$S>): "
                            + "__ServiceHandler<Context, __HttpRequest, __HttpResponse> => {",
                    operationHandlerSymbol.getName(), inputType, outputType, operation.getId().getName());
        } else {
            writer.write("export const get$L = <Context>(operation: __Operation<$T, $T, Context>): "
                            + "__ServiceHandler<Context, __HttpRequest, __HttpResponse> => {",
                    operationHandlerSymbol.getName(), inputType, outputType);
        }
        writer.indent();

        generateOperationMux(context, operation);

        if (!context.getSettings().isDisableDefaultValidation()) {
            writer.addImport("generateValidationSummary", "__generateValidationSummary", "@aws-smithy/server-common");
            writer.addImport("generateValidationMessage", "__generateValidationMessage", "@aws-smithy/server-common");
            writer.openBlock("const customizer: __ValidationCustomizer<$S> = (ctx, failures) => {", "};",
                operation.getId().getName(),
                () -> {
                    writeDefaultValidationCustomizer(writer);
                }
            );
        }
        writer.write("return new $T(operation, mux, new $T(), serializeFrameworkException, customizer);",
                operationHandlerSymbol, serializerType);

        writer.dedent().write("}");
    }

    private void writeDefaultValidationCustomizer(TypeScriptWriter writer) {
        writer.openBlock("if (!failures) {", "}", () -> {
            writer.write("return undefined;");
        });

        writer.openBlock("return {", "};", () -> {
            writer.write("name: \"ValidationException\",");
            writer.write("$$fault: \"client\",");
            writer.write("message: __generateValidationSummary(failures),");
            writer.openBlock("fieldList: failures.map(failure => ({", "}))", () -> {
                writer.write("path: failure.path,");
                writer.write("message: __generateValidationMessage(failure)");
            });
        });
    }

    private Consumer<OperationShape> writeOperationCase(
            TypeScriptWriter writer,
            SymbolProvider symbolProvider
    ) {
        return operation -> {
            Symbol symbol = symbolProvider.toSymbol(operation).expectProperty("serializerType", Symbol.class);
            writer.write("case $S: return new $T();", operation.getId().getName(), symbol);
        };
    }

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        TopDownIndex topDownIndex = TopDownIndex.of(context.getModel());

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationResponseDeserializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol response bindings for %s because it does not have an "
                            + "http binding trait", getName(), operation.getId())));
        }
    }

    private void generateOperationResponseSerializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference responseType = getApplicationProtocol().getResponseType();
        HttpBindingIndex bindingIndex = HttpBindingIndex.of(context.getModel());
        TypeScriptWriter writer = context.getWriter();

        writer.addUseImports(responseType);
        String methodName = ProtocolGenerator.getGenericSerFunctionName(symbol) + "Response";
        Symbol outputType = symbol.expectProperty("outputType", Symbol.class);
        writer.addImport("ServerSerdeContext", null, "@aws-smithy/server-common");

        writer.openBlock("export const $L = async(\n"
                + "  input: $T,\n"
                + "  ctx: ServerSerdeContext\n"
                + "): Promise<$T> => {", "}", methodName, outputType, responseType, () -> {
            writeEmptyEndpoint(context);
            writeOperationStatusCode(context, operation, bindingIndex, trait);
            writeResponseHeaders(context, operation, bindingIndex,
                    () -> writeDefaultOutputHeaders(context, operation));

            List<HttpBinding> bodyBindings = writeResponseBody(context, operation, bindingIndex);
            if (!bodyBindings.isEmpty()) {
                // Track all shapes bound to the body so their serializers may be generated.
                bodyBindings.stream()
                        .map(HttpBinding::getMember)
                        .map(member -> context.getModel().expectShape(member.getTarget()))
                        .forEach(serializingDocumentShapes::add);
            }

            writer.openBlock("return new $T({", "});", responseType, () -> {
                writer.write("headers,");
                writer.write("body,");
                writer.write("statusCode,");
            });
        });
        writer.write("");

        for (ShapeId errorShapeId : operation.getErrors()) {
            serializingErrorShapes.add(context.getModel().expectShape(errorShapeId).asStructureShape().get());
        }
    }

    private void generateErrorSerializer(GenerationContext context, StructureShape error) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(error);
        SymbolReference responseType = getApplicationProtocol().getResponseType();
        HttpBindingIndex bindingIndex = HttpBindingIndex.of(context.getModel());
        TypeScriptWriter writer = context.getWriter();

        writer.addUseImports(responseType);
        String methodName = ProtocolGenerator.getGenericSerFunctionName(symbol) + "Error";
        writer.addImport("ServerSerdeContext", null, "@aws-smithy/server-common");

        writer.openBlock("export const $L = async(\n"
                + "  input: $T,\n"
                + "  ctx: ServerSerdeContext\n"
                + "): Promise<$T> => {", "}", methodName, symbol, responseType, () -> {
            writeEmptyEndpoint(context);
            generateErrorSerializationImplementation(context, error, responseType, bindingIndex);
        });
        writer.write("");
    }

    private void generateErrorSerializationImplementation(GenerationContext context,
                                                          StructureShape error,
                                                          SymbolReference responseType,
                                                          HttpBindingIndex bindingIndex) {
        TypeScriptWriter writer = context.getWriter();
        writeErrorStatusCode(context, error);
        writeResponseHeaders(context, error, bindingIndex, () -> writeDefaultErrorHeaders(context, error));

        List<HttpBinding> bodyBindings = writeResponseBody(context, error, bindingIndex);
        if (!bodyBindings.isEmpty()) {
            // Track all shapes bound to the body so their serializers may be generated.
            bodyBindings.stream()
                    .map(HttpBinding::getMember)
                    .map(member -> context.getModel().expectShape(member.getTarget()))
                    .forEach(serializingDocumentShapes::add);
        }

        writer.openBlock("return new $T({", "});", responseType, () -> {
            writer.write("headers,");
            writer.write("body,");
            writer.write("statusCode,");
        });
    }

    private void writeEmptyEndpoint(GenerationContext context) {
        context.getWriter().write("const context: __SerdeContext = {\n"
                + "  ...ctx,\n"
                + "  endpoint: () => Promise.resolve({\n"
                + "    protocol: '',\n"
                + "    hostname: '',\n"
                + "    path: '',\n"
                + "  }),\n"
                + "}");
    }

    private void generateOperationRequestSerializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference requestType = getApplicationProtocol().getRequestType();
        HttpBindingIndex bindingIndex = HttpBindingIndex.of(context.getModel());
        TypeScriptWriter writer = context.getWriter();

        // Ensure that the request type is imported.
        writer.addUseImports(requestType);
        writer.addImport("Endpoint", "__Endpoint", "@aws-sdk/types");
        // e.g., serializeAws_restJson1_1ExecuteStatement
        String methodName = ProtocolGenerator.getSerFunctionName(symbol, getName());
        // Add the normalized input type.
        Symbol inputType = symbol.expectProperty("inputType", Symbol.class);
        String contextType = CodegenUtils.getOperationSerializerContextType(writer, context.getModel(), operation);

        writer.openBlock("export const $L = async(\n"
                       + "  input: $T,\n"
                       + "  context: $L\n"
                       + "): Promise<$T> => {", "}", methodName, inputType, contextType, requestType, () -> {

            // Get the hostname, path, port, and scheme from client's resolved endpoint. Then construct the request from
            // them. The client's resolved endpoint can be default one or supplied by users.
            writer.write("const {hostname, protocol = $S, port, path: basePath} = await context.endpoint();", "https");

            writeRequestHeaders(context, operation, bindingIndex);
            writeResolvedPath(context, operation, bindingIndex, trait);
            boolean hasQueryComponents = writeRequestQueryString(context, operation, bindingIndex, trait);

            List<HttpBinding> bodyBindings = writeRequestBody(context, operation, bindingIndex);
            if (!bodyBindings.isEmpty()) {
                // Track all shapes bound to the body so their serializers may be generated.
                bodyBindings.stream()
                        .map(HttpBinding::getMember)
                        .map(member -> context.getModel().expectShape(member.getTarget()))
                        .forEach(serializingDocumentShapes::add);
            }

            boolean hasHostPrefix = operation.hasTrait(EndpointTrait.class);
            if (hasHostPrefix) {
                HttpProtocolGeneratorUtils.writeHostPrefix(context, operation);
            }
            writer.openBlock("return new $T({", "});", requestType, () -> {
                writer.write("protocol,");
                if (hasHostPrefix) {
                    writer.write("hostname: resolvedHostname,");
                } else {
                    writer.write("hostname,");
                }
                writer.write("port,");
                writer.write("method: $S,", trait.getMethod());
                writer.write("headers,");
                writer.write("path: resolvedPath,");
                if (hasQueryComponents) {
                    writer.write("query,");
                }
                // Always set the body,
                writer.write("body,");
            });
        });

        writer.write("");
    }

    private void writeOperationStatusCode(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();

        List<HttpBinding> bindings = bindingIndex.getResponseBindings(operation, Location.RESPONSE_CODE);
        TypeScriptWriter writer = context.getWriter();
        writer.write("let statusCode: number = $L", trait.getCode());
        if (!bindings.isEmpty()) {
            HttpBinding binding = bindings.get(0);
            // This can only be bound to an int so we don't need to do the same sort of complex finagling
            // as we do with other http bindings.
            String bindingMember = "input." + symbolProvider.toMemberName(binding.getMember());
            writer.openBlock("if ($L !== undefined) {", "}", bindingMember, () -> {
                writer.write("statusCode = $L", bindingMember);
            });
        }
    }

    private void writeErrorStatusCode(GenerationContext context, StructureShape error) {
        ErrorTrait trait = error.expectTrait(ErrorTrait.class);
        int code = error.getTrait(HttpErrorTrait.class)
                .map(HttpErrorTrait::getCode)
                .orElse(trait.getDefaultHttpStatusCode());
        context.getWriter().write("const statusCode: number = $L", code);
    }

    private void writeResolvedPath(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex,
            HttpTrait trait
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        List<HttpBinding> labelBindings = bindingIndex.getRequestBindings(operation, Location.LABEL);

        // Always write the bound path, but only the actual segments.
        writer.write("let resolvedPath = `$L` + $S;",
                "${basePath?.endsWith('/') ? basePath.slice(0, -1) : (basePath || '')}",
                "/" + trait.getUri().getSegments().stream()
                .map(Segment::toString)
                .collect(Collectors.joining("/")));

        // Handle any label bindings.
        if (!labelBindings.isEmpty()) {
            Model model = context.getModel();
            List<Segment> uriLabels = trait.getUri().getLabels();
            for (HttpBinding binding : labelBindings) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                Shape target = model.expectShape(binding.getMember().getTarget());
                String labelValue = getInputValue(context, binding.getLocation(), "input." + memberName,
                        binding.getMember(), target);
                // Get the correct label to use.
                Segment uriLabel = uriLabels.stream().filter(s -> s.getContent().equals(memberName)).findFirst().get();
                writer.addImport("extendedEncodeURIComponent", "__extendedEncodeURIComponent",
                        "@aws-sdk/smithy-client");
                String encodedSegment = uriLabel.isGreedyLabel()
                        ? "labelValue.split(\"/\").map(segment => __extendedEncodeURIComponent(segment)).join(\"/\")"
                        : "__extendedEncodeURIComponent(labelValue)";

                // Set the label's value and throw a clear error if empty or undefined.
                writer.write("if (input.$L !== undefined) {", memberName).indent()
                    .write("const labelValue: string = $L;", labelValue)
                    .openBlock("if (labelValue.length <= 0) {", "}", () -> {
                        writer.write("throw new Error('Empty value provided for input HTTP label: $L.');", memberName);
                    })
                    .write("resolvedPath = resolvedPath.replace($S, $L);", uriLabel.toString(), encodedSegment).dedent()
                .write("} else {").indent()
                    .write("throw new Error('No value provided for input HTTP label: $L.');", memberName).dedent()
                .write("}");
            }
        }
    }

    private boolean writeRequestQueryString(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex,
            HttpTrait trait
    ) {
        TypeScriptWriter writer = context.getWriter();
        List<HttpBinding> queryBindings = bindingIndex.getRequestBindings(operation, Location.QUERY);
        List<HttpBinding> queryParamsBindings = bindingIndex.getRequestBindings(operation, Location.QUERY_PARAMS);

        // Build the initial query bag.
        Map<String, String> queryLiterals = trait.getUri().getQueryLiterals();
        if (!queryLiterals.isEmpty() || !queryBindings.isEmpty() || !queryParamsBindings.isEmpty()) {
            writer.openBlock("const query: any = {", "};", () -> {
                if (!queryLiterals.isEmpty()) {
                    // Write any query literals present in the uri.
                    queryLiterals.forEach((k, v) -> writer.write("$S: $S,", k, v));
                }
                // Handle any additional query params bindings.
                // If query string parameter is also present in httpQuery, it would be overwritten.
                // Serializing HTTP messages https://awslabs.github.io/smithy/1.0/spec/core/http-traits.html#serializing-http-messages
                if (!queryParamsBindings.isEmpty()) {
                    SymbolProvider symbolProvider = context.getSymbolProvider();
                    String memberName = symbolProvider.toMemberName(queryParamsBindings.get(0).getMember());
                    writer.write("...(input.$1L !== undefined && input.$1L),", memberName);
                }
                // Handle any additional query bindings.
                if (!queryBindings.isEmpty()) {
                    for (HttpBinding binding : queryBindings) {
                        writeRequestQueryParam(context, binding);
                    }
                }
            });
        }

        // Any binding or literal means we generated a query bag.
        return !queryBindings.isEmpty() || !queryLiterals.isEmpty() || !queryParamsBindings.isEmpty();
    }

    private void writeRequestQueryParam(
            GenerationContext context,
            HttpBinding binding
    ) {
        Model model = context.getModel();
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();

        String memberName = symbolProvider.toMemberName(binding.getMember());
        writer.addImport("extendedEncodeURIComponent", "__extendedEncodeURIComponent",
                "@aws-sdk/smithy-client");

        Shape target = model.expectShape(binding.getMember().getTarget());
        String queryValue = getInputValue(context, binding.getLocation(), "input." + memberName,
                binding.getMember(), target);
        writer.write("...(input.$L !== undefined && { $S: $L }),", memberName,
                binding.getLocationName(), queryValue);
    }

    private void writeRequestHeaders(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();

        // Headers are always present either from the default document or the payload.
        writer.openBlock("const headers: any = {", "};", () -> {
            // Only set the content type if one can be determined.
            writeContentTypeHeader(context, operation, true);
            writeDefaultInputHeaders(context, operation);

            operation.getInput().ifPresent(outputId -> {
                for (HttpBinding binding : bindingIndex.getRequestBindings(operation, Location.HEADER)) {
                    writeNormalHeader(context, binding);
                }

                // Handle assembling prefix headers.
                for (HttpBinding binding : bindingIndex.getRequestBindings(operation, Location.PREFIX_HEADERS)) {
                    writePrefixHeaders(context, binding);
                }
            });
        });
    }

    private void writeNormalHeader(GenerationContext context, HttpBinding binding) {
        String memberLocation = "input." + context.getSymbolProvider().toMemberName(binding.getMember());
        Shape target = context.getModel().expectShape(binding.getMember().getTarget());
        String headerValue = getInputValue(context, binding.getLocation(), memberLocation + "!",
                binding.getMember(), target);
        context.getWriter().write("...isSerializableHeaderValue($L) && { $S: $L },",
                memberLocation, binding.getLocationName().toLowerCase(Locale.US), headerValue);
    }

    private void writePrefixHeaders(GenerationContext context, HttpBinding binding) {
        Model model = context.getModel();
        TypeScriptWriter writer = context.getWriter();
        String memberLocation = "input." + context.getSymbolProvider().toMemberName(binding.getMember());
        MapShape prefixMap = model.expectShape(binding.getMember().getTarget()).asMapShape().get();
        Shape target = model.expectShape(prefixMap.getValue().getTarget());
        // Iterate through each entry in the member.
        writer.openBlock("...($1L !== undefined) && Object.keys($1L).reduce(", "),", memberLocation,
            () -> {
                writer.openBlock("(acc: any, suffix: string) => ({", "}), {}",
                    () -> {
                        // Use a ! since we already validated the input member is defined above.
                        String headerValue = getInputValue(context, binding.getLocation(),
                                memberLocation + "![suffix]", binding.getMember(), target);
                        writer.write("...acc,");
                        // Append the prefix to key.
                        writer.write("[`$L$${suffix.toLowerCase()}`]: $L,",
                                binding.getLocationName().toLowerCase(Locale.US), headerValue);
                    });
            }
        );
    }

    private void writeResponseHeaders(
            GenerationContext context,
            Shape operationOrError,
            HttpBindingIndex bindingIndex,
            Runnable injectExtraHeaders
    ) {
        TypeScriptWriter writer = context.getWriter();

        // Headers are always present either from the default document or the payload.
        writer.openBlock("const headers: any = {", "};", () -> {
            writeContentTypeHeader(context, operationOrError, false);
            injectExtraHeaders.run();

            for (HttpBinding binding : bindingIndex.getResponseBindings(operationOrError, Location.HEADER)) {
                writeNormalHeader(context, binding);
            }

            // Handle assembling prefix headers.
            for (HttpBinding binding : bindingIndex.getResponseBindings(operationOrError, Location.PREFIX_HEADERS)) {
                writePrefixHeaders(context, binding);
            }
        });
    }

    private void writeContentTypeHeader(GenerationContext context, Shape operationOrError, boolean isInput) {
        HttpBindingIndex bindingIndex = HttpBindingIndex.of(context.getModel());
        Optional<String> optionalContentType;
        if (isInput) {
            optionalContentType = bindingIndex.determineRequestContentType(operationOrError, getDocumentContentType());
        } else {
            optionalContentType = bindingIndex.determineResponseContentType(operationOrError, getDocumentContentType());
        }
        // If we need to write a default body then it needs a content type.
        if (!optionalContentType.isPresent() && shouldWriteDefaultBody(context, operationOrError, isInput)) {
            optionalContentType = Optional.of(getDocumentContentType());
        }
        optionalContentType.ifPresent(contentType -> context.getWriter().write("'content-type': $S,", contentType));
    }

    private List<HttpBinding> writeRequestBody(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        List<HttpBinding> payloadBindings = bindingIndex.getRequestBindings(operation, Location.PAYLOAD);
        List<HttpBinding> documentBindings = bindingIndex.getRequestBindings(operation, Location.DOCUMENT);
        boolean shouldWriteDefaultBody = shouldWriteDefaultInputBody(context, operation);
        return writeBody(context, operation, payloadBindings, documentBindings, shouldWriteDefaultBody, true);
    }

    private List<HttpBinding> writeResponseBody(
            GenerationContext context,
            Shape operationOrError,
            HttpBindingIndex bindingIndex
    ) {
        // We just make one up here since it's not actually used by consumers.
        List<HttpBinding> payloadBindings = bindingIndex.getResponseBindings(operationOrError, Location.PAYLOAD);
        List<HttpBinding> documentBindings = bindingIndex.getResponseBindings(operationOrError, Location.DOCUMENT);
        boolean shouldWriteDefaultBody = operationOrError.asOperationShape()
                .map(operation -> shouldWriteDefaultOutputBody(context, operation))
                .orElseGet(() -> shouldWriteDefaultErrorBody(context, operationOrError.asStructureShape().get()));
        return writeBody(context, operationOrError, payloadBindings, documentBindings, shouldWriteDefaultBody, false);
    }

    private boolean shouldWriteDefaultBody(GenerationContext context, Shape operationOrError, boolean isInput) {
        if (isInput) {
            return shouldWriteDefaultInputBody(context, operationOrError.asOperationShape().get());
        } else if (operationOrError.isOperationShape()) {
            return shouldWriteDefaultOutputBody(context, operationOrError.asOperationShape().get());
        } else {
            return shouldWriteDefaultErrorBody(context, operationOrError.asStructureShape().get());
        }
    }

    /**
     * Given a context and operation, should a default input body be written. By default no body will be written
     * if there are no members bound to the input.
     *
     * @param context The generation context.
     * @param operation The operation whose input is being serialized.
     *
     * @return True if a default body should be generated.
     */
    protected boolean shouldWriteDefaultInputBody(GenerationContext context, OperationShape operation) {
        return HttpBindingIndex.of(context.getModel()).getRequestBindings(operation).isEmpty();
    }

    /**
     * Given a context and operation, should a default output body be written. By default no body will be written
     * if there are no members bound to the output.
     *
     * @param context The generation context.
     * @param operation The operation whose output is being serialized.
     *
     * @return True if a default body should be generated.
     */
    protected boolean shouldWriteDefaultOutputBody(GenerationContext context, OperationShape operation) {
        return HttpBindingIndex.of(context.getModel()).getResponseBindings(operation).isEmpty();
    }

    /**
     * Given a context and error, should a default body be written. By default no body will be written
     * if there are no members bound to the error.
     *
     * @param context The generation context.
     * @param error The error being serialized.
     *
     * @return True if a default body should be generated.
     */
    protected boolean shouldWriteDefaultErrorBody(GenerationContext context, StructureShape error) {
        return HttpBindingIndex.of(context.getModel()).getResponseBindings(error).isEmpty();
    }

    private List<HttpBinding> writeBody(
            GenerationContext context,
            Shape operationOrError,
            List<HttpBinding> payloadBindings,
            List<HttpBinding> documentBindings,
            boolean shouldWriteDefaultBody,
            boolean isInput
    ) {
        TypeScriptWriter writer = context.getWriter();
        // Write the default `body` property.
        writer.write("let body: any;");

        // Handle a payload binding explicitly.
        if (!payloadBindings.isEmpty()) {
            // There can only be one payload binding.
            HttpBinding payloadBinding = payloadBindings.get(0);
            if (isInput) {
                serializeInputPayload(context, operationOrError.asOperationShape().get(), payloadBinding);
            } else if (operationOrError.isOperationShape()) {
                serializeOutputPayload(context, operationOrError.asOperationShape().get(), payloadBinding);
            } else {
                serializeErrorPayload(context, operationOrError.asStructureShape().get(), payloadBinding);
            }
            return payloadBindings;
        }

        // If we have document bindings or need a defaulted request body,
        // use the input document serialization.
        if (!documentBindings.isEmpty() || shouldWriteDefaultBody) {
            documentBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
            if (isInput) {
                serializeInputDocumentBody(context, operationOrError.asOperationShape().get(), documentBindings);
            } else if (operationOrError.isOperationShape()) {
                serializeOutputDocumentBody(context, operationOrError.asOperationShape().get(), documentBindings);
            } else {
                serializeErrorDocumentBody(context, operationOrError.asStructureShape().get(), documentBindings);
            }
            return documentBindings;
        }

        // Otherwise, we have no bindings to add shapes from.
        return ListUtils.of();
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * shape. This may use native types (like getting Date formats for timestamps,)
     * converters (like a base64Encoder,) or invoke complex type serializers to
     * manipulate the dataSource into the proper input content.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input value.
     */
    protected String getInputValue(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            MemberShape member,
            Shape target
    ) {
        if (target instanceof StringShape) {
            return getStringInputParam(context, bindingType, dataSource, target);
        } else if (target instanceof FloatShape || target instanceof DoubleShape) {
            // Handle decimal numbers needing to have .0 in their value when whole numbers.
            return "((" + dataSource + " % 1 == 0) ? " + dataSource + " + \".0\" : " + dataSource + ".toString())";
        } else if (target instanceof BooleanShape || target instanceof NumberShape) {
            return dataSource + ".toString()";
        } else if (target instanceof TimestampShape) {
            return getTimestampInputParam(context, bindingType, dataSource, member);
        } else if (target instanceof DocumentShape) {
            return dataSource;
        } else if (target instanceof BlobShape) {
            return getBlobInputParam(bindingType, dataSource);
        } else if (target instanceof CollectionShape) {
            return getCollectionInputParam(context, bindingType, dataSource, (CollectionShape) target);
        } else if (target instanceof StructureShape || target instanceof UnionShape) {
            return getNamedMembersInputParam(context, bindingType, dataSource, target);
        } else if (target instanceof MapShape) {
            return getMapInputParam(context, bindingType, dataSource, (MapShape) target);
        }

        throw new CodegenException(String.format(
                "Unsupported %s binding of %s to %s in %s using the %s protocol",
                bindingType, member.getMemberName(), target.getType(), member.getContainer(), getName()));
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * string. By default, this base64 encodes content in headers if there is a
     * mediaType applied to the string, and passes through for all other cases.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input string.
     */
    private String getStringInputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            Shape target
    ) {
        String baseParam = HttpProtocolGeneratorUtils.getStringInputParam(context, target, dataSource);
        switch (bindingType) {
            case HEADER:
                // Encode these to base64 if a MediaType is present.
                if (target.hasTrait(MediaTypeTrait.ID)) {
                    return "Buffer.from(" + baseParam + ").toString('base64')";
                }
            default:
                return baseParam;
        }
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * blob. By default, this base64 encodes content in headers and query strings,
     * and passes through for payloads.
     *
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the input blob.
     */
    private String getBlobInputParam(Location bindingType, String dataSource) {
        switch (bindingType) {
            case PAYLOAD:
                return dataSource;
            case HEADER:
            case QUERY:
                // Encode these to base64.
                return "context.base64Encoder(" + dataSource + ")";
            default:
                throw new CodegenException("Unexpected blob binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * collection. By default, this separates the list with commas in headers, and
     * relies on the HTTP implementation for query strings.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input collection.
     */
    private String getCollectionInputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            CollectionShape target
    ) {
        MemberShape targetMember = target.getMember();
        Shape collectionTarget = context.getModel().expectShape(targetMember.getTarget());
        // Use a basic array to serialize this more easily.
        if (target.isSetShape()) {
            dataSource = "Array.from(" + dataSource + ".values())";
        }
        String collectionTargetValue = getInputValue(context, bindingType, "_entry", targetMember, collectionTarget);
        String iteratedParam = "(" + dataSource + " || []).map(_entry => " + collectionTargetValue + ")";
        switch (bindingType) {
            case HEADER:
                return iteratedParam + ".join(', ')";
            case QUERY:
            case QUERY_PARAMS:
                return iteratedParam;
            default:
                throw new CodegenException("Unexpected collection binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * shape. This redirects to a serialization function for payloads,
     * and fails otherwise.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input shape.
     */
    private String getNamedMembersInputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            Shape target
    ) {
        switch (bindingType) {
            case PAYLOAD:
                if (target.isUnionShape() && target.hasTrait(StreamingTrait.class)) {
                    this.serializeEventUnions.add(target.asUnionShape().get());
                    Symbol targetSymbol = context.getSymbolProvider().toSymbol(target);
                    String eventSerializer = ProtocolGenerator.getSerFunctionName(targetSymbol,
                            context.getProtocolName());
                    return "context.eventStreamMarshaller.serialize(" + dataSource
                            + ", event => " + eventSerializer + "_event(event, context))";
                }
                // Redirect to a serialization function.
                Symbol symbol = context.getSymbolProvider().toSymbol(target);
                return ProtocolGenerator.getSerFunctionName(symbol, context.getProtocolName())
                        + "(" + dataSource + ", context)";
            default:
                throw new CodegenException("Unexpected named member shape binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * map.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input collection.
     */
    private String getMapInputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            MapShape target
    ) {
        Model model = context.getModel();
        MemberShape mapMember = target.getValue();
        SymbolProvider symbolProvider = context.getSymbolProvider();

        String valueString = getInputValue(context, bindingType, "value", mapMember,
                model.expectShape(mapMember.getTarget()));
        return "Object.entries(" + dataSource + " || {}).reduce("
            + "(acc: any, [key, value]: [string, " + symbolProvider.toSymbol(mapMember) + "]) => ({"
            +   "...acc,"
            +   "[key]: " + valueString + ","
            + "}), {})";
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * shape. This uses the format specified, converting to strings when in a header,
     * label, or query string.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @return Returns a value or expression of the input shape.
     */
    private String getTimestampInputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            MemberShape member
    ) {
        HttpBindingIndex httpIndex = HttpBindingIndex.of(context.getModel());
        Format format;
        switch (bindingType) {
            case HEADER:
                format = httpIndex.determineTimestampFormat(member, bindingType, Format.HTTP_DATE);
                break;
            case LABEL:
                format = httpIndex.determineTimestampFormat(member, bindingType, getDocumentTimestampFormat());
                break;
            case QUERY:
                format = httpIndex.determineTimestampFormat(member, bindingType, Format.DATE_TIME);
                break;
            default:
                throw new CodegenException("Unexpected named member shape binding location `" + bindingType + "`");
        }

        String baseParam = HttpProtocolGeneratorUtils.getTimestampInputParam(context, dataSource, member, format);
        return baseParam + ".toString()";
    }

    /**
     * Writes any additional HTTP input headers required by the protocol implementation.
     *
     * <p>Two parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code context: SerdeContext}: a TypeScript type containing context and tools for type serde.</li>
     * </ul>
     *
     * <p>For example:
     *
     * <pre>{@code
     *   "foo": "This is a custom header",
     * }</pre>
     *
     * @param context The generation context.
     * @param operation The operation whose input is being generated.
     */
    protected void writeDefaultInputHeaders(GenerationContext context, OperationShape operation) {
    }

    /**
     * Writes any additional HTTP output headers required by the protocol implementation.
     *
     * <p>Two parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code context: SerdeContext}: a TypeScript type containing context and tools for type serde.</li>
     * </ul>
     *
     * <p>For example:
     *
     * <pre>{@code
     *   "foo": "This is a custom header",
     * }</pre>
     *
     * @param context The generation context.
     * @param operation The operation whose output is being generated.
     */
    protected void writeDefaultOutputHeaders(GenerationContext context, OperationShape operation) {
    }

    /**
     * Writes any additional HTTP error headers required by the protocol implementation.
     *
     * <p>Two parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code context: SerdeContext}: a TypeScript type containing context and tools for type serde.</li>
     * </ul>
     *
     * <p>For example:
     *
     * <pre>{@code
     *   "foo": "This is a custom header",
     * }</pre>
     *
     * @param context The generation context.
     * @param error The error which is being generated.
     */
    protected void writeDefaultErrorHeaders(GenerationContext context, StructureShape error) {
    }

    /**
     * Writes the code needed to serialize a protocol input document.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * Implementations MUST properly fill the body parameter even if no
     * document bindings are present.
     *
     * <p>For example:
     *
     * <pre>{@code
     * const bodyParams: any = {};
     * if (input.barValue !== undefined) {
     *   bodyParams['barValue'] = input.barValue;
     * }
     * body = JSON.stringify(bodyParams);
     * }</pre>
     * @param context The generation context.
     * @param operation The operation whose input is being generated.
     * @param documentBindings The bindings to place in the document.
     */
    protected abstract void serializeInputDocumentBody(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );

    /**
     * Writes the code needed to serialize a protocol output document.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * Implementations MUST properly fill the body parameter even if no
     * document bindings are present.
     *
     * <p>For example:
     *
     * <pre>{@code
     * const bodyParams: any = {};
     * if (input.barValue !== undefined) {
     *   bodyParams['barValue'] = input.barValue;
     * }
     * body = JSON.stringify(bodyParams);
     * }</pre>
     * @param context The generation context.
     * @param operation The operation whose output is being generated.
     * @param documentBindings The bindings to place in the document.
     */
    protected abstract void serializeOutputDocumentBody(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );

    /**
     * Writes the code needed to serialize a protocol error document.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * Implementations MUST properly fill the body parameter even if no
     * document bindings are present.
     *
     * <p>For example:
     *
     * <pre>{@code
     * const bodyParams: any = {};
     * if (input.barValue !== undefined) {
     *   bodyParams['barValue'] = input.barValue;
     * }
     * body = JSON.stringify(bodyParams);
     * }</pre>
     * @param context The generation context.
     * @param error The error that is being generated.
     * @param documentBindings The bindings to place in the document.
     */
    protected abstract void serializeErrorDocumentBody(
            GenerationContext context,
            StructureShape error,
            List<HttpBinding> documentBindings
    );

    /**
     * Writes the code needed to serialize the input payload of a request.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (input.body !== undefined) {
     *   body = context.base64Encoder(input.body);
     * }
     * }</pre>
     * @param context The generation context.
     * @param operation The operation whose input is being generated.
     * @param payloadBinding The payload binding to serialize.
     */
    protected void serializeInputPayload(
            GenerationContext context,
            OperationShape operation,
            HttpBinding payloadBinding
    ) {
        genericSerializePayload(context, payloadBinding);
    }

    /**
     * Writes the code needed to serialize the output payload of a response.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the response body.
     * This variable will already be defined in scope.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (input.body !== undefined) {
     *   body = context.base64Encoder(input.body);
     * }
     * }</pre>
     * @param context The generation context.
     * @param operation The operation whose output is being generated.
     * @param payloadBinding The payload binding to serialize.
     */
    protected void serializeOutputPayload(
            GenerationContext context,
            OperationShape operation,
            HttpBinding payloadBinding
    ) {
        genericSerializePayload(context, payloadBinding);
    }

    /**
     * Writes the code needed to serialize the error payload of a response.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the response body.
     * This variable will already be defined in scope.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (input.body !== undefined) {
     *   body = context.base64Encoder(input.body);
     * }
     * }</pre>
     * @param context The generation context.
     * @param error The error being generated.
     * @param payloadBinding The payload binding to serialize.
     */
    protected void serializeErrorPayload(
            GenerationContext context,
            StructureShape error,
            HttpBinding payloadBinding
    ) {
        genericSerializePayload(context, payloadBinding);
    }

    private void genericSerializePayload(GenerationContext context, HttpBinding payloadBinding) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        String memberName = symbolProvider.toMemberName(payloadBinding.getMember());

        writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
            Shape target = context.getModel().expectShape(payloadBinding.getMember().getTarget());

            // Because documents can be set to a null value, handle setting that as the body
            // instead of using toString, as `null.toString()` will fail.
            if (target.isDocumentShape()) {
                writer.openBlock("if (input.$L === null) {", "} else {", memberName,
                        () -> writer.write("body = \"null\";"));
                writer.indent();

            }
            writer.write("body = $L;", getInputValue(
                    context, Location.PAYLOAD, "input." + memberName, payloadBinding.getMember(), target));
            if (target.isDocumentShape()) {
                writer.dedent();
                writer.write("}");
            }
        });
    }

    // Writes a function used to dispatch event to corresponding event serializer if given event stream is
    // a multi-event event stream.
    private void generateSerializingEventUnion(
            GenerationContext context,
            UnionShape events
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(events);
        String protocolName = context.getProtocolName();
        String methodName = ProtocolGenerator.getSerFunctionName(symbol, protocolName) + "_event";
        Model model = context.getModel();
        writer.addImport("Message", "__Message", TypeScriptDependency.AWS_SDK_TYPES.packageName);
        writer.openBlock("const $L = (\n"
                + "  input: any,\n"
                + "  context: __SerdeContext\n"
                + "): __Message => {", "}", methodName, () -> {
            // Visit over the union type, then get the right serialization for the member.
            writer.openBlock("return $T.visit(input, {", "});", symbol, () -> {
                events.getAllMembers().forEach((memberName, memberShape) -> {
                    StructureShape target = model.expectShape(memberShape.getTarget(), StructureShape.class);
                    // Prepare event shapes to generate event serializers.
                    serializeEventShapes.add(target);
                    // Dispatch to special event deserialize function
                    Symbol eventSymbol = symbolProvider.toSymbol(target);
                    String eventSerMethodName =
                            ProtocolGenerator.getSerFunctionName(eventSymbol, protocolName) + "_event";
                    writer.write("$L: value => $L(value, context),", memberName, eventSerMethodName);
                });

                // Handle the unknown property.
                writer.write("_: value => value as any");
            });
        });
    }

    // Writes a function serializing event input into event messages.
    private void generateEventSerializer(GenerationContext context, StructureShape event) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(event);
        String methodName = ProtocolGenerator.getSerFunctionName(symbol, context.getProtocolName()) + "_event";
        writer.openBlock("const $L = (\n"
                       + "  input: $L,\n"
                       + "  context: __SerdeContext\n"
                       + "): __Message => {", "}", methodName, symbol.getName(), () -> {
            writer.openBlock("const message: __Message = {", "}", () -> {
                writer.openBlock("headers: {", "},", () -> {
                    //fix headers required by event stream
                    writer.write("\":event-type\": { type: \"string\", value: $S },", symbol.getName());
                    writer.write("\":message-type\": { type: \"string\", value: \"event\" }");
                });
                writer.write("body: new Uint8Array()");
            });
            writeEventHeaders(context, event);
            writeEventBody(context, event);
            writer.write("return message;");
        });
    }

    private void writeEventHeaders(GenerationContext context, StructureShape event) {
        TypeScriptWriter writer = context.getWriter();
        Model model = context.getModel();
        List<MemberShape> headerMembers = event.getAllMembers().values().stream()
                .filter(member -> member.hasTrait(EventHeaderTrait.class)).collect(Collectors.toList());
        for (MemberShape headerMember : headerMembers) {
            String memberName = headerMember.getMemberName();
            Shape target = model.expectShape(headerMember.getTarget());
            writer.openBlock("if (input.$L) {", "}", memberName, () -> {
                writer.write("message.headers[$S] = { type: $S, value: $L }", memberName,
                        getEventHeaderType(headerMember),
                        getOutputValue(context, Location.HEADER, "input." + memberName, headerMember, target));
            });
        }
    }

    /**
     * The value of event header 'type' property of given shape.
     */
    private String getEventHeaderType(Shape shape) {
        switch (shape.getType()) {
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INTEGER:
            case LONG:
            case STRING:
            case TIMESTAMP:
                return shape.getType().toString();
            case BLOB:
                return "binary";
            default:
                return "binary";
        }
    }

    private void writeEventBody(GenerationContext context, StructureShape event) {
        TypeScriptWriter writer = context.getWriter();
        Model model = context.getModel();
        List<MemberShape> payloadMembers = event.getAllMembers().values().stream()
                .filter(member -> member.hasTrait(EventPayloadTrait.class)).collect(Collectors.toList());
        List<MemberShape> documentMembers = event.getAllMembers().values().stream()
                .filter(member -> !member.hasTrait(EventHeaderTrait.class)
                        && !member.hasTrait(EventPayloadTrait.class))
                .collect(Collectors.toList());
        if (!payloadMembers.isEmpty()) {
            // Write event payload if exists. There is at most 1 payload member.
            MemberShape payloadMember = payloadMembers.get(0);
            String memberName = payloadMember.getMemberName();
            writer.write("message.body = $L || message.body;",
                    getInputValue(context, Location.PAYLOAD, "input." + memberName, payloadMember,
                            model.expectShape(payloadMember.getTarget())));
        } else if (!documentMembers.isEmpty()) {
            // Write event document bindings if exist.
            SymbolProvider symbolProvider = context.getSymbolProvider();
            Symbol symbol = symbolProvider.toSymbol(event);
            // Use normal structure serializer instead of event serializer to serialize document body.
            String serFunctionName = ProtocolGenerator.getSerFunctionName(symbol, context.getProtocolName());
            writer.write("message.body = $L(input, context);", serFunctionName);
        }
    }

    private void generateOperationRequestDeserializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference requestType = getApplicationProtocol().getRequestType();
        Model model = context.getModel();
        HttpBindingIndex bindingIndex = HttpBindingIndex.of(model);
        TypeScriptWriter writer = context.getWriter();

        // Ensure that the request type is imported.
        writer.addUseImports(requestType);
        writer.addImport("Endpoint", "__Endpoint", "@aws-sdk/types");
        String methodName = ProtocolGenerator.getGenericDeserFunctionName(symbol) + "Request";
        // Add the normalized input type.
        Symbol inputType = symbol.expectProperty("inputType", Symbol.class);
        String contextType = CodegenUtils.getOperationSerializerContextType(writer, context.getModel(), operation);

        writer.openBlock("export const $L = async(\n"
                + "  output: $T,\n"
                + "  context: $L\n"
                + "): Promise<$T> => {", "}", methodName, requestType, contextType, inputType, () -> {
            // Start deserializing the response.
            writer.openBlock("const contents: $T = {", "};", inputType, () -> {
                // Only set a type and the members if we have output.
                operation.getInput().ifPresent(shapeId -> {
                    // Set all the members to undefined to meet type constraints.
                    StructureShape target = model.expectShape(shapeId).asStructureShape().get();
                    new TreeMap<>(target.getAllMembers())
                            .forEach((memberName, memberShape) -> writer.write(
                                    "$L: undefined,", memberName));
                });
            });
            readQueryString(context, operation, bindingIndex);
            readPath(context, operation, bindingIndex, trait);
            readHost(context, operation);
            readRequestHeaders(context, operation, bindingIndex, "output");
            List<HttpBinding> documentBindings = readRequestBody(context, operation, bindingIndex);
            // Track all shapes bound to the document so their deserializers may be generated.
            documentBindings.forEach(binding -> {
                Shape target = model.expectShape(binding.getMember().getTarget());
                deserializingDocumentShapes.add(target);
            });
            writer.write("return Promise.resolve(contents);");
        });
        writer.write("");
    }

    private void readQueryString(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        List<HttpBinding> directQueryBindings = bindingIndex.getRequestBindings(operation, Location.QUERY);
        List<HttpBinding> mappedQueryBindings = bindingIndex.getRequestBindings(operation, Location.QUERY_PARAMS);
        if (directQueryBindings.isEmpty() && mappedQueryBindings.isEmpty()) {
            return;
        }
        writer.write("const query = output.query");
        writer.openBlock("if (query !== undefined && query !== null) {", "}", () -> {
            readDirectQueryBindings(context, directQueryBindings);
            if (!mappedQueryBindings.isEmpty()) {
                // There can only ever be one of these bindings on a given operation.
                readMappedQueryBindings(context, mappedQueryBindings.get(0));
            }
        });
    }

    private void readDirectQueryBindings(GenerationContext context, List<HttpBinding> directQueryBindings) {
        TypeScriptWriter writer = context.getWriter();
        for (HttpBinding binding : directQueryBindings) {
            String memberName = context.getSymbolProvider().toMemberName(binding.getMember());
            writer.openBlock("if (query[$S] !== undefined) {", "}", binding.getLocationName(), () -> {
                Shape target = context.getModel().expectShape(binding.getMember().getTarget());
                if (target instanceof CollectionShape) {
                    writer.write("const decoded = Array.isArray(query[$1S]) ? (query[$1S] as string[])"
                            + ".map(e => decodeURIComponent(e)) : [decodeURIComponent(query[$1S] as string)];",
                            binding.getLocationName());
                } else {
                    writer.addImport("SerializationException",
                            "__SerializationException",
                            "@aws-smithy/server-common");
                    writer.openBlock("if (Array.isArray(query[$1S])) {", "}",
                            binding.getLocationName(),
                            () -> {
                                writer.write("throw new __SerializationException();");
                            });
                    writer.write("const decoded = decodeURIComponent(query[$1S] as string);",
                            binding.getLocationName());
                }
                String queryValue = getOutputValue(context, binding.getLocation(), "decoded",
                        binding.getMember(), target);
                writer.write("contents.$L = $L;", memberName, queryValue);
            });
        }
    }

    private void readMappedQueryBindings(GenerationContext context, HttpBinding mappedBinding) {
        TypeScriptWriter writer = context.getWriter();
        MapShape target = context.getModel()
                .expectShape(mappedBinding.getMember().getTarget()).asMapShape().get();
        Shape valueShape = context.getModel().expectShape(target.getValue().getTarget());
        String valueType = "string";
        if (valueShape instanceof CollectionShape) {
            valueType = "string[]";
        }
        writer.write("let parsedQuery: { [key: string]: $L } = {}", valueType);
        String parsedValue = getOutputValue(context, mappedBinding.getLocation(),
                "decoded", target.getValue(), valueShape);
        writer.openBlock("for (const [key, value] of Object.entries(query)) {", "}", () -> {
            if (valueShape instanceof CollectionShape) {
                writer.write("const decoded = Array.isArray(value) ? (value as string[])"
                        + ".map(e => decodeURIComponent(e)) : [decodeURIComponent(value as string)];");
            } else {
                writer.addImport("SerializationException",
                        "__SerializationException",
                        "@aws-smithy/server-common");
                writer.openBlock("if (Array.isArray(value)) {", "}",
                        () -> {
                            writer.write("throw new __SerializationException();");
                        });
                writer.write("const decoded = decodeURIComponent(value as string);");
            }
            writer.write("parsedQuery[decodeURIComponent(key)] = $L;", parsedValue);
        });
        String memberName = context.getSymbolProvider().toMemberName(mappedBinding.getMember());
        writer.write("contents.$L = parsedQuery;", memberName);
    }

    private void readPath(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex,
            HttpTrait trait
    ) {
        TypeScriptWriter writer = context.getWriter();
        List<HttpBinding> pathBindings = bindingIndex.getRequestBindings(operation, Location.LABEL);
        if (pathBindings.isEmpty()) {
            return;
        }
        StringBuilder pathRegexBuilder = new StringBuilder();
        for (Segment segment : trait.getUri().getSegments()) {
            pathRegexBuilder.append("/");
            if (segment.isLabel()) {
                // Create a named capture group for the segment so we can grab it later without regard to order.
                pathRegexBuilder.append(String.format("(?<%s>", segment.getContent()));
                if (segment.isGreedyLabel()) {
                    pathRegexBuilder.append(".+");
                } else {
                    pathRegexBuilder.append("[^/]+");
                }
                pathRegexBuilder.append(")");
            } else {
                pathRegexBuilder.append(segment.getContent());
            }
        }
        writer.write("const pathRegex = new RegExp($S);", pathRegexBuilder.toString());
        writer.write("const parsedPath = output.path.match(pathRegex);");
        writer.openBlock("if (parsedPath?.groups !== undefined) {", "}", () -> {
            for (HttpBinding binding : pathBindings) {
                Shape target = context.getModel().expectShape(binding.getMember().getTarget());
                String memberName = context.getSymbolProvider().toMemberName(binding.getMember());
                // since this is in the path, we should decode early
                String dataSource = String.format("decodeURIComponent(parsedPath.groups.%s)",
                        binding.getLocationName());
                String labelValue = getOutputValue(context, binding.getLocation(),
                        dataSource, binding.getMember(), target);
                writer.write("contents.$L = $L;", memberName, labelValue);
            }
        });
    }

    private void readHost(GenerationContext context, OperationShape operation) {
        TypeScriptWriter writer = context.getWriter();
        if (!operation.hasTrait(EndpointTrait.class)) {
            return;
        }
        EndpointTrait endpointTrait = operation.expectTrait(EndpointTrait.class);
        if (endpointTrait.getHostPrefix().getLabels().isEmpty()) {
            return;
        }
        // Anchor to the beginning since we're looking at the host's prefix
        StringBuilder endpointRegexBuilder = new StringBuilder("^");
        for (Segment segment : endpointTrait.getHostPrefix().getSegments()) {
            if (segment.isLabel()) {
                // Create a named capture group for the segment so we can grab it later without regard to order.
                endpointRegexBuilder.append(String.format("(?<%s>.*)", segment.getContent()));
            } else {
                endpointRegexBuilder.append(segment.getContent().replace(".", "\\."));
            }
        }
        writer.write("const hostRegex = new RegExp($S);", endpointRegexBuilder.toString());
        writer.write("const parsedHost = output.path.match(hostRegex);");
        Shape input = context.getModel().expectShape(operation.getInput().get());
        writer.openBlock("if (parsedHost?.groups !== undefined) {", "}", () -> {
            for (MemberShape member : input.members()) {
                if (member.hasTrait(HostLabelTrait.class)) {
                    Shape target = context.getModel().expectShape(member.getTarget());
                    String memberName = context.getSymbolProvider().toMemberName(member);
                    String labelValue = getOutputValue(context, Location.LABEL,
                            "parsedHost.groups." + member.getMemberName(), member, target);
                    writer.write("contents.$L = $L;", memberName, labelValue);
                }
            }
        });
    }

    private void generateOperationResponseDeserializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference responseType = getApplicationProtocol().getResponseType();
        HttpBindingIndex bindingIndex = HttpBindingIndex.of(context.getModel());
        Model model = context.getModel();
        TypeScriptWriter writer = context.getWriter();

        // Ensure that the response type is imported.
        writer.addUseImports(responseType);
        // e.g., deserializeAws_restJson1_1ExecuteStatement
        String methodName = ProtocolGenerator.getDeserFunctionName(symbol, getName());
        String errorMethodName = methodName + "Error";
        // Add the normalized output type.
        Symbol outputType = symbol.expectProperty("outputType", Symbol.class);
        String contextType = CodegenUtils.getOperationDeserializerContextType(writer, context.getModel(), operation);

        // Handle the general response.
        writer.openBlock("export const $L = async(\n"
                       + "  output: $T,\n"
                       + "  context: $L\n"
                       + "): Promise<$T> => {", "}", methodName, responseType, contextType, outputType, () -> {
            // Redirect error deserialization to the dispatcher if we receive an error range
            // status code that's not the modeled code (300 or higher). This allows for
            // returning other 2XX codes that don't match the defined value.
            writer.openBlock("if (output.statusCode !== $L && output.statusCode >= 300) {", "}", trait.getCode(),
                    () -> writer.write("return $L(output, context);", errorMethodName));

            // Start deserializing the response.
            writer.openBlock("const contents: $T = {", "};", outputType, () -> {
                writer.write("$$metadata: deserializeMetadata(output),");

                // Only set a type and the members if we have output.
                operation.getOutput().ifPresent(outputId -> {
                    // Set all the members to undefined to meet type constraints.
                    StructureShape target = model.expectShape(outputId).asStructureShape().get();
                    new TreeMap<>(target.getAllMembers())
                            .forEach((memberName, memberShape) -> writer.write(
                                    "$L: undefined,", memberName));
                });
            });
            readResponseHeaders(context, operation, bindingIndex, "output");
            List<HttpBinding> documentBindings = readResponseBody(context, operation, bindingIndex);
            // Track all shapes bound to the document so their deserializers may be generated.
            documentBindings.forEach(binding -> {
                Shape target = model.expectShape(binding.getMember().getTarget());
                deserializingDocumentShapes.add(target);
            });
            writer.write("return Promise.resolve(contents);");
        });
        writer.write("");

        // Write out the error deserialization dispatcher.
        Set<StructureShape> errorShapes = HttpProtocolGeneratorUtils.generateErrorDispatcher(
                context, operation, responseType, this::writeErrorCodeParser,
                isErrorCodeInBody, this::getErrorBodyLocation);
        deserializingErrorShapes.addAll(errorShapes);
    }

    private void generateErrorDeserializer(GenerationContext context, StructureShape error) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        HttpBindingIndex bindingIndex = HttpBindingIndex.of(context.getModel());
        Model model = context.getModel();
        Symbol errorSymbol = symbolProvider.toSymbol(error);
        String errorDeserMethodName = ProtocolGenerator.getDeserFunctionName(errorSymbol,
                context.getProtocolName()) + "Response";
        String outputName = isErrorCodeInBody ? "parsedOutput" : "output";

        writer.openBlock("const $L = async (\n"
                       + "  $L: any,\n"
                       + "  context: __SerdeContext\n"
                       + "): Promise<$T> => {", "};",
                errorDeserMethodName, outputName, errorSymbol, () -> {
            writer.openBlock("const contents: $T = {", "};", errorSymbol, () -> {
                writer.write("name: $S,", error.getId().getName());
                writer.write("$$fault: $S,", error.getTrait(ErrorTrait.class).get().getValue());
                HttpProtocolGeneratorUtils.writeRetryableTrait(writer, error, ",");
                writer.write("$$metadata: deserializeMetadata($L),", outputName);
                // Set all the members to undefined to meet type constraints.
                new TreeMap<>(error.getAllMembers())
                        .forEach((memberName, memberShape) -> writer.write("$L: undefined,", memberName));
            });

            readResponseHeaders(context, error, bindingIndex, outputName);
            List<HttpBinding> documentBindings = readErrorResponseBody(context, error, bindingIndex);
            // Track all shapes bound to the document so their deserializers may be generated.
            documentBindings.forEach(binding -> {
                Shape target = model.expectShape(binding.getMember().getTarget());
                deserializingDocumentShapes.add(target);
            });
            writer.write("return contents;");
        });

        writer.write("");
    }

    private List<HttpBinding> readErrorResponseBody(
            GenerationContext context,
            Shape error,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        if (isErrorCodeInBody) {
            // Body is already parsed in the error dispatcher, simply assign the body.
            writer.write("const data: any = $L;", getErrorBodyLocation(context, "parsedOutput.body"));
            List<HttpBinding> responseBindings = bindingIndex.getResponseBindings(error, Location.DOCUMENT);
            responseBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
            deserializeErrorDocumentBody(context, error.asStructureShape().get(), responseBindings);
            return responseBindings;
        } else {
            // Deserialize response body just like in a normal response.
            return readResponseBody(context, error, bindingIndex);
        }
    }

    private void readResponseHeaders(
            GenerationContext context,
            Shape operationOrError,
            HttpBindingIndex bindingIndex,
            String outputName
    ) {
        List<HttpBinding> headerBindings = bindingIndex.getResponseBindings(operationOrError, Location.HEADER);
        readNormalHeaders(context, headerBindings, outputName);

        List<HttpBinding> prefixHeaderBindings =
                bindingIndex.getResponseBindings(operationOrError, Location.PREFIX_HEADERS);
        readPrefixHeaders(context, prefixHeaderBindings, outputName);
    }

    private void readRequestHeaders(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex,
            String outputName
    ) {
        List<HttpBinding> headerBindings = bindingIndex.getRequestBindings(operation, Location.HEADER);
        readNormalHeaders(context, headerBindings, outputName);

        List<HttpBinding> prefixHeaderBindings =
                bindingIndex.getRequestBindings(operation, Location.PREFIX_HEADERS);
        readPrefixHeaders(context, prefixHeaderBindings, outputName);
    }

    /**
     * Reads headers that are 1-1 mapped to members via the @httpHeader trait.
     *
     * @param context the generation context.
     * @param headerBindings a collection of header bindings.
     * @param outputName the name of the output variable to read from.
     */
    private void readNormalHeaders(
            GenerationContext context,
            Collection<HttpBinding> headerBindings,
            String outputName
    ) {
        for (HttpBinding binding : headerBindings) {
            TypeScriptWriter writer = context.getWriter();
            String memberName = context.getSymbolProvider().toMemberName(binding.getMember());
            String headerName = binding.getLocationName().toLowerCase(Locale.US);
            writer.openBlock("if ($L.headers[$S] !== undefined) {", "}", outputName, headerName, () -> {
                Shape target = context.getModel().expectShape(binding.getMember().getTarget());
                String headerValue = getOutputValue(context, binding.getLocation(),
                        outputName + ".headers['" + headerName + "']", binding.getMember(), target);
                writer.write("contents.$L = $L;", memberName, headerValue);
            });
        }
    }

    /**
     * Reads headers are bound by the @httpPrefixHeaders trait.
     *
     * @param context the generation context.
     * @param prefixHeaderBindings a collection of prefix header bindings.
     * @param outputName the name of the output variable to read from.
     */
    private void readPrefixHeaders(
            GenerationContext context,
            Collection<HttpBinding> prefixHeaderBindings,
            String outputName
    ) {
        if (prefixHeaderBindings.isEmpty()) {
            return;
        }

        Model model = context.getModel();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        TypeScriptWriter writer = context.getWriter();

        // Run through the headers one time, matching any prefix groups.
        writer.openBlock("Object.keys($L.headers).forEach(header => {", "});", outputName, () -> {
            for (HttpBinding binding : prefixHeaderBindings) {
                // Prepare a grab bag for these headers if necessary
                String memberName = symbolProvider.toMemberName(binding.getMember());
                writer.openBlock("if (contents.$L === undefined) {", "}", memberName, () -> {
                    writer.write("contents.$L = {};", memberName);
                });

                // Generate a single block for each group of lower-cased prefix headers.
                String headerLocation = binding.getLocationName().toLowerCase(Locale.US);
                writer.openBlock("if (header.startsWith($S)) {", "}", headerLocation, () -> {
                    MapShape prefixMap = model.expectShape(binding.getMember().getTarget()).asMapShape().get();
                    Shape target = model.expectShape(prefixMap.getValue().getTarget());
                    String headerValue = getOutputValue(context, binding.getLocation(),
                            outputName + ".headers[header]", binding.getMember(), target);

                    // Extract the non-prefix portion as the key.
                    writer.write("contents.$L[header.substring($L)] = $L;",
                            memberName, headerLocation.length(), headerValue);
                });
            }
        });
    }

    private List<HttpBinding> readRequestBody(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        List<HttpBinding> documentBindings = bindingIndex.getRequestBindings(operation, Location.DOCUMENT);
        documentBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
        List<HttpBinding> payloadBindings = bindingIndex.getRequestBindings(operation, Location.PAYLOAD);
        return readBody(context, operation, documentBindings, payloadBindings, Collections.emptyList(), false);
    }

    private List<HttpBinding> readResponseBody(
            GenerationContext context,
            Shape operationOrError,
            HttpBindingIndex bindingIndex
    ) {
        List<HttpBinding> documentBindings = bindingIndex.getResponseBindings(operationOrError, Location.DOCUMENT);
        documentBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
        List<HttpBinding> payloadBindings = bindingIndex.getResponseBindings(operationOrError, Location.PAYLOAD);
        List<HttpBinding> responseCodeBindings = bindingIndex.getResponseBindings(
                operationOrError, Location.RESPONSE_CODE);
        return readBody(context, operationOrError, documentBindings, payloadBindings, responseCodeBindings, true);
    }

    private List<HttpBinding> readBody(
            GenerationContext context,
            Shape operationOrError,
            List<HttpBinding> documentBindings,
            List<HttpBinding> payloadBindings,
            List<HttpBinding> responseCodeBindings,
            boolean isInput
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();

        if (!documentBindings.isEmpty()) {
            // If the response has document bindings, the body can be parsed to a JavaScript object.
            String bodyLocation = "(await parseBody(output.body, context))";
            // Use the protocol specific error location for retrieving contents.
            if (operationOrError instanceof StructureShape) {
                bodyLocation = getErrorBodyLocation(context, bodyLocation);
            }
            writer.write("const data: any = $L;", bodyLocation);

            if (isInput) {
                deserializeInputDocumentBody(context, operationOrError.asOperationShape().get(), documentBindings);
            } else if (operationOrError.isOperationShape()) {
                deserializeOutputDocumentBody(context, operationOrError.asOperationShape().get(), documentBindings);
            } else {
                deserializeErrorDocumentBody(context, operationOrError.asStructureShape().get(), documentBindings);
            }
        }
        if (!payloadBindings.isEmpty()) {
            HttpBinding payloadBinding = payloadBindings.get(0);
            if (isInput) {
                deserializeInputPayload(context, operationOrError.asOperationShape().get(), payloadBinding);
            } else if (operationOrError.isOperationShape()) {
                deserializeOutputPayload(context, operationOrError.asOperationShape().get(), payloadBinding);
            } else {
                deserializeErrorPayload(context, operationOrError.asStructureShape().get(), payloadBinding);
            }
            if (payloadBinding != null) {
                documentBindings = ListUtils.of(payloadBinding);
            }
        }

        // Handle any potential httpResponseCode binding overrides if the field
        // isn't set in the body.
        // These are only relevant when a payload is not present, as it cannot
        // coexist with a payload.
        for (HttpBinding responseCodeBinding : responseCodeBindings) {
            // The name of the member to get from the input shape.
            String memberName = symbolProvider.toMemberName(responseCodeBinding.getMember());
            writer.openBlock("if (contents.$L === undefined) {", "}", memberName, () ->
                    writer.write("contents.$L = output.statusCode;", memberName));
        }
        if (!documentBindings.isEmpty()) {
            return documentBindings;
        }

        // If there are no payload or document bindings, the body still needs to be
        // collected so the process can exit.
        writer.write("await collectBody(output.body, context);");
        return ListUtils.of();
    }

    private HttpBinding readPayload(
            GenerationContext context,
            HttpBinding binding
    ) {
        TypeScriptWriter writer = context.getWriter();

        // There can only be one payload binding.
        Shape target = context.getModel().expectShape(binding.getMember().getTarget());

        // Handle streaming shapes differently.
        if (target.hasTrait(StreamingTrait.class)) {
            if (target instanceof UnionShape) {
                // If payload is a event stream, return it after calling event stream deser function.
                generateEventStreamDeserializer(context, binding.getMember(), target);
                writer.write("contents.$L = data;", binding.getMemberName());
                // Don't generate non-eventstream payload shape again.
                return null;
            }
            // If payload is streaming, return raw low-level stream directly.
            writer.write("const data: any = output.body;");
        } else if (target instanceof BlobShape) {
            // If payload is non-streaming Blob, only need to collect stream to binary data (Uint8Array).
            writer.write("const data: any = await collectBody(output.body, context);");
        } else if (target instanceof StructureShape || target instanceof UnionShape) {
            // If payload is Structure or Union, they we need to parse the string into JavaScript object.
            writer.write("const data: any = await parseBody(output.body, context);");
        } else if (target instanceof StringShape || target instanceof DocumentShape) {
            // If payload is String or Document, we need to collect body and convert binary to string.
            writer.write("const data: any = await collectBodyString(output.body, context);");
        } else {
            throw new CodegenException(String.format("Unexpected shape type bound to payload: `%s`",
                    target.getType()));
        }
        writer.write("contents.$L = $L;", binding.getMemberName(), getOutputValue(context,
                Location.PAYLOAD, "data", binding.getMember(), target));
        return binding;
    }

    /**
     * Writes the code needed to deserialize the input payload of a request.
     *
     * <p>Implementations of this method are expected to set a value to the
     * bound member name of the {@code contents} variable after deserializing
     * the response body. This variable will already be defined in scope.
     *
     * @param context The generation context.
     * @param operation The operation whose input payload is being deserialized.
     * @param binding The payload binding to deserialize.
     * @return The deserialized payload binding.
     */
    protected HttpBinding deserializeInputPayload(
            GenerationContext context,
            OperationShape operation,
            HttpBinding binding
    ) {
        return readPayload(context, binding);
    }

    /**
     * Writes the code needed to deserialize the output payload of a response.
     *
     * <p>Implementations of this method are expected to set a value to the
     * bound member name of the {@code contents} variable after deserializing
     * the response body. This variable will already be defined in scope.
     *
     * @param context The generation context.
     * @param operation The operation whose output payload is being deserialized.
     * @param binding The payload binding to deserialize.
     * @return The deserialized payload binding.
     */
    protected HttpBinding deserializeOutputPayload(
            GenerationContext context,
            OperationShape operation,
            HttpBinding binding
    ) {
        return readPayload(context, binding);
    }

    /**
     * Writes the code needed to deserialize the error payload of a response.
     *
     * <p>Implementations of this method are expected to set a value to the
     * bound member name of the {@code contents} variable after deserializing
     * the response body. This variable will already be defined in scope.
     *
     * @param context The generation context.
     * @param error The error whose payload is being deserialized.
     * @param binding The payload binding to deserialize.
     * @return The deserialized payload binding.
     */
    protected HttpBinding deserializeErrorPayload(
            GenerationContext context,
            StructureShape error,
            HttpBinding binding
    ) {
        return readPayload(context, binding);
    }

    // Writes a function deserializing response payload to stream of event messages
    private void generateEventStreamDeserializer(GenerationContext context, MemberShape member, Shape target) {
        TypeScriptWriter writer = context.getWriter();
        writer.openBlock("const data: any = context.eventStreamMarshaller.deserialize(", ");", () -> {
            writer.write("output.body,");
            writer.openBlock("async event => {", "}", () -> {
                writer.write("const eventName = Object.keys(event)[0];");
                writer.openBlock("const eventHeaders = Object.entries(event[eventName].headers).reduce(", ");", () -> {
                    writer.write(
                            "(accummulator, curr) => {accummulator[curr[0]] = curr[1].value; return accummulator; },");
                    writer.write("{} as {[key: string]: any}");
                });
                writer.openBlock("const eventMessage = {", "};", () -> {
                    writer.write("headers: eventHeaders,");
                    writer.write("body: event[eventName].body");
                });
                writer.openBlock("const parsedEvent = {", "};", () -> {
                    writer.write("[eventName]: eventMessage");
                });
                Symbol targetSymbol = context.getSymbolProvider().toSymbol(target);
                StringBuilder deserFunctionBuilder = new StringBuilder(ProtocolGenerator.getDeserFunctionName(
                        targetSymbol, context.getProtocolName())).append("_event");
                if (target instanceof StructureShape) {
                    // Single-event stream. Save the structure and generate event-specific deser later.
                    this.deserializingEventShapes.add(target.asStructureShape().get());
                    // For single-event stream, supply event message to corresponding event structure deser.
                    deserFunctionBuilder.append("(eventMessage, context)");
                } else if (target instanceof UnionShape) {
                    // Multi-event stream. Save the union and generate dispatcher later.
                    this.deserializeEventUnions.add(target.asUnionShape().get());
                    // For multi-event stream, supply event name to event pairs to the events union deser.
                    deserFunctionBuilder.append("(parsedEvent, context)");
                } else {
                    throw new CodegenException(String.format("Unexpected shape targeted by eventstream: `%s`",
                            target.getType()));
                }
                writer.write("return await $L;", deserFunctionBuilder.toString());

            });
        });
    }

    /**
     * Writes a function used to dispatch event to corresponding event deserializers if given
     * event stream is a multi-event event stream.
     */
    private void generateDeserializingEventUnion(
            GenerationContext context,
            UnionShape events
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(events);
        String protocolName = context.getProtocolName();
        String methodName = ProtocolGenerator.getDeserFunctionName(symbol, protocolName) + "_event";
        Model model = context.getModel();
        writer.openBlock("const $L = async (\n"
                       + "  output: any,\n"
                       + "  context: __SerdeContext\n"
                       + "): Promise<$T> => {", "}", methodName, symbol, () -> {
            events.getAllMembers().forEach((name, member) -> {
                StructureShape target = model.expectShape(member.getTarget(), StructureShape.class);
                // Prepare event for generating event deserializers.
                deserializingEventShapes.add(target);
                writer.openBlock("if (output[$S] !== undefined) {", "}", name, () -> {
                    writer.openBlock("return {", "};", () -> {
                        // Dispatch to special event deserialize function
                        Symbol eventSymbol = symbolProvider.toSymbol(target);
                        String eventDeserMethodName =
                                ProtocolGenerator.getDeserFunctionName(eventSymbol, protocolName) + "_event";
                        writer.write("$1L: await $2L(output[$1S], context)", name, eventDeserMethodName);
                    });
                });
            });
            writer.write("return {$$unknown: output};");
        });
    }

    // Writes a function deserializing event message to event output.
    private void generateEventDeserializer(GenerationContext context, StructureShape event) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(event);
        String methodName = ProtocolGenerator.getDeserFunctionName(symbol, context.getProtocolName()) + "_event";
        // Handle the general response.
        writer.openBlock("const $L = async (\n"
                       + "  output: any,\n"
                       + "  context: __SerdeContext\n"
                       + "): Promise<$T> => {", "}", methodName, symbol, () -> {
            if (event.hasTrait(ErrorTrait.class)) {
                generateErrorEventDeserializer(context, event);
            } else {
                writer.write("let contents: $L = {} as any;", symbol.getName());
                readEventHeaders(context, event);
                readEventBody(context, event);
                writer.write("return contents;");
            }
        });
    }

    // Writes function content that deserialize error event with error deserializer
    private void generateErrorEventDeserializer(GenerationContext context, StructureShape event) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        // If this is an error event, we need to generate the error deserializer.
        deserializingErrorShapes.add(event);
        Symbol errorSymbol = symbolProvider.toSymbol(event);
        String errorDeserMethodName = ProtocolGenerator.getDeserFunctionName(errorSymbol,
                context.getProtocolName()) + "Response";
        if (isErrorCodeInBody) {
            // If error code is in body, parseBody() won't be called inside error deser. So we parse body here.
            // It's ok to parse body here because body won't be streaming if 'isErrorCodeInBody' is set.
            writer.openBlock("const parsedOutput: any = {", "};",
                    () -> {
                        writer.write("...output,");
                        writer.write("body: await parseBody(output.body, context)");
                    });
            writer.write("return $L(parsedOutput, context);", errorDeserMethodName);
        } else {
            writer.write("return $L(output, context);", errorDeserMethodName);
        }
    }

    // Parse members from event headers.
    private void readEventHeaders(GenerationContext context, StructureShape event) {
        TypeScriptWriter writer = context.getWriter();
        Model model = context.getModel();
        List<MemberShape> headerMembers = event.getAllMembers().values().stream()
                .filter(member -> member.hasTrait(EventHeaderTrait.class)).collect(Collectors.toList());
        for (MemberShape headerMember : headerMembers) {
            String memberName = headerMember.getMemberName();
            writer.openBlock("if (output.headers[$S] !== undefined) {", "}", memberName, () -> {
                Shape target = model.expectShape(headerMember.getTarget());
                String headerValue = getOutputValue(context, Location.HEADER,
                        "output.headers['" + memberName + "']", headerMember, target);
                writer.write("contents.$L = $L;", memberName, headerValue);
            });
        }
    }

    private void readEventBody(GenerationContext context, StructureShape event) {
        TypeScriptWriter writer = context.getWriter();
        // Parse members from event payload.
        List<MemberShape> payloadMembers = event.getAllMembers().values().stream()
                .filter(member -> member.hasTrait(EventPayloadTrait.class)).collect(Collectors.toList());
        List<MemberShape> documentMembers = event.getAllMembers().values().stream()
                .filter(member -> !member.hasTrait(EventHeaderTrait.class)
                        && !member.hasTrait(EventPayloadTrait.class))
                .collect(Collectors.toList());
        if (!payloadMembers.isEmpty()) {
            //There's only one event payload member
            MemberShape payloadMember = payloadMembers.get(0);
            readEventPayload(context, payloadMember);
        } else if (!documentMembers.isEmpty()) {
            // Parse member from event body using original event structure deser.
            SymbolProvider symbolProvider = context.getSymbolProvider();
            Symbol symbol = symbolProvider.toSymbol(event);
            // If response has document binding, the body can be parsed to JavaScript object.
            writer.write("const data: any = await parseBody(output.body, context);");
            // Deser the event document with the original event(structure) shape deser function
            writer.openBlock("contents = {", "} as any;", () -> {
                writer.write("...contents,");
                writer.write("...$L(data, context)",
                        ProtocolGenerator.getDeserFunctionName(symbol, context.getProtocolName()));
            });
            //need original structure shape deserializer to deserialize event body.
            deserializingDocumentShapes.add(event);
        }
    }

    private void readEventPayload(GenerationContext context, MemberShape payloadMember) {
        TypeScriptWriter writer = context.getWriter();
        Model model = context.getModel();
        Shape payloadTarget = model.expectShape(payloadMember.getTarget());
        String memberName = payloadMember.getMemberName();
        if (payloadTarget instanceof BlobShape) {
            // If event payload is a blob, only need to collect stream to binary data(Uint8Array).
            writer.write("contents.$L = output.body;", memberName);
        } else if (payloadTarget instanceof StructureShape || payloadTarget instanceof UnionShape) {
            // If body is Structure or Union, they we need to parse the string into JavaScript object.
            writer.write("contents.$L = await parseBody(output.body, context);", memberName);
        } else if (payloadTarget instanceof StringShape) {
            // If payload is string, we need to collect body and encode binary to string.
            writer.write("contents.$L = await collectBodyString(output.body, context);", memberName);
        } else {
            throw new CodegenException(String.format("Unexpected shape type bound to event payload: `%s`",
                    payloadTarget.getType()));
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * shape. This may use native types (like generating a Date for timestamps,)
     * converters (like a base64Decoder,) or invoke complex type deserializers to
     * manipulate the dataSource into the proper output content.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the output value.
     */
    private String getOutputValue(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            MemberShape member,
            Shape target
    ) {
        if (target instanceof NumberShape) {
            return getNumberOutputParam(bindingType, dataSource, target);
        } else if (target instanceof BooleanShape) {
            return getBooleanOutputParam(context, bindingType, dataSource);
        } else if (target instanceof StringShape) {
            return getStringOutputParam(context, bindingType, dataSource, target);
        } else if (target instanceof DocumentShape) {
            return dataSource;
        } else if (target instanceof TimestampShape) {
            HttpBindingIndex httpIndex = HttpBindingIndex.of(context.getModel());
            Format format = httpIndex.determineTimestampFormat(member, bindingType, getDocumentTimestampFormat());
            return HttpProtocolGeneratorUtils.getTimestampOutputParam(dataSource, bindingType, member, format);
        } else if (target instanceof BlobShape) {
            return getBlobOutputParam(bindingType, dataSource);
        } else if (target instanceof CollectionShape) {
            return getCollectionOutputParam(context, bindingType, dataSource, (CollectionShape) target);
        } else if (target instanceof StructureShape || target instanceof UnionShape) {
            return getNamedMembersOutputParam(context, bindingType, dataSource, target);
        }

        throw new CodegenException(String.format(
                "Unsupported %s binding of %s to %s in %s using the %s protocol",
                bindingType, member.getMemberName(), target.getType(), member.getContainer(), getName()));
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * boolean. By default, this checks strict equality to 'true' in headers and passes
     * through for documents.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output boolean.
     */
    private String getBooleanOutputParam(GenerationContext context, Location bindingType, String dataSource) {
        switch (bindingType) {
            case QUERY:
            case LABEL:
            case HEADER:
                context.getWriter().addImport("parseBoolean", "__parseBoolean", "@aws-sdk/smithy-client");
                return String.format("__parseBoolean(%s)", dataSource);
            default:
                throw new CodegenException("Unexpected boolean binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * string. By default, this base64 decodes content in headers if there is a
     * mediaType applied to the string, and passes through for all other cases.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input string.
     */
    private String getStringOutputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            Shape target
    ) {
        // Decode these to base64 if a MediaType is present.
        if (bindingType == Location.HEADER && target.hasTrait(MediaTypeTrait.ID)) {
            dataSource = "Buffer.from(" + dataSource + ", 'base64').toString('ascii')";
        }

        return HttpProtocolGeneratorUtils.getStringOutputParam(
                context, target, dataSource, !isGuaranteedString(bindingType));
    }

    private boolean isGuaranteedString(Location bindingType) {
        return bindingType != Location.PAYLOAD && bindingType != Location.DOCUMENT;
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * blob. By default, this base64 decodes content in headers and passes through
     * for payloads.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output blob.
     */
    private String getBlobOutputParam(Location bindingType, String dataSource) {
        switch (bindingType) {
            case PAYLOAD:
                return dataSource;
            case QUERY:
            case LABEL:
            case HEADER:
                // Decode these from base64.
                return "context.base64Decoder(" + dataSource + ")";
            default:
                throw new CodegenException("Unexpected blob binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * collection. By default, this splits a comma separated string in headers.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the output collection.
     */
    private String getCollectionOutputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            CollectionShape target
    ) {
        MemberShape targetMember = target.getMember();
        Shape collectionTarget = context.getModel().expectShape(targetMember.getTarget());
        String collectionTargetValue = getOutputValue(context, bindingType, "_entry.trim()",
                targetMember, collectionTarget);
        String outputParam;
        switch (bindingType) {
            case QUERY_PARAMS:
            case QUERY:
                return String.format("%1$s.map(_entry => %2$s)",
                        dataSource, collectionTargetValue);
            case LABEL:
                dataSource = "(" + dataSource + " || \"\")";
                // Split these values on slashes.
                outputParam = "" + dataSource + ".split('/')";

                // Iterate over each entry and do deser work.
                outputParam += ".map(_entry => " + collectionTargetValue + ")";

                return outputParam;
            case HEADER:
                dataSource = "(" + dataSource + " || \"\")";
                // Split these values on commas.
                outputParam = "" + dataSource + ".split(',')";

                // Headers that have HTTP_DATE formatted timestamps already contain a ","
                // in their formatted entry, so split on every other "," instead.
                if (collectionTarget.isTimestampShape()) {
                    // Check if our member resolves to the HTTP_DATE format.
                    HttpBindingIndex httpIndex = HttpBindingIndex.of(context.getModel());
                    Format format = httpIndex.determineTimestampFormat(targetMember, bindingType, Format.HTTP_DATE);

                    if (format == Format.HTTP_DATE) {
                        TypeScriptWriter writer = context.getWriter();
                        writer.addImport("splitEvery", "__splitEvery", "@aws-sdk/smithy-client");
                        outputParam = "__splitEvery(" + dataSource + ", ',', 2)";
                    }
                }

                // Iterate over each entry and do deser work.
                outputParam += ".map(_entry => " + collectionTargetValue + ")";

                return outputParam;
            default:
                throw new CodegenException("Unexpected collection binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * shape. This redirects to a deserialization function for documents and payloads,
     * and fails otherwise.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the output shape.
     */
    private String getNamedMembersOutputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            Shape target
    ) {
        switch (bindingType) {
            case PAYLOAD:
                // Redirect to a deserialization function.
                Symbol symbol = context.getSymbolProvider().toSymbol(target);
                return ProtocolGenerator.getDeserFunctionName(symbol, context.getProtocolName())
                               + "(" + dataSource + ", context)";
            default:
                throw new CodegenException("Unexpected named member shape binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * number. By default, invokes parseInt on byte/short/integer/long types in headers,
     * invokes parseFloat on float/double types in headers, and fails otherwise.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the output number.
     */
    private String getNumberOutputParam(Location bindingType, String dataSource, Shape target) {
        switch (bindingType) {
            case QUERY:
            case LABEL:
            case HEADER:
                if (target instanceof FloatShape || target instanceof DoubleShape) {
                    return "parseFloat(" + dataSource + ")";
                }
                return "parseInt(" + dataSource + ", 10)";
            default:
                throw new CodegenException("Unexpected number binding location `" + bindingType + "`");
        }
    }

    /**
     * Writes the code that loads an {@code errorCode} String with the content used
     * to dispatch errors to specific serializers.
     *
     * <p>Two variables will be in scope:
     *   <ul>
     *       <li>{@code output} or {@code parsedOutput}: a value of the HttpResponse type.
     *          <ul>
     *              <li>{@code output} is a raw HttpResponse, available when {@code isErrorCodeInBody} is set to
     *              {@code false}</li>
     *              <li>{@code parsedOutput} is a HttpResponse type with body parsed to JavaScript object, available
     *              when {@code isErrorCodeInBody} is set to {@code true}</li>
     *          </ul>
     *       </li>
     *       <li>{@code context}: the SerdeContext.</li>
     *   </ul>
     *
     * <p>For example:
     *
     * <pre>{@code
     * errorCode = output.headers["x-amzn-errortype"].split(':')[0];
     * }</pre>
     *
     * @param context The generation context.
     */
    protected abstract void writeErrorCodeParser(GenerationContext context);

    /**
     * Provides where within the passed output variable the actual error resides. This is useful
     * for protocols that wrap the specific error in additional elements within the body.
     *
     * @param context The generation context.
     * @param outputLocation The name of the variable containing the output body.
     * @return A string of the variable containing the error body within the output.
     */
    protected String getErrorBodyLocation(GenerationContext context, String outputLocation) {
        return outputLocation;
    }

    /**
     * Writes the code needed to deserialize a protocol input document.
     *
     * <p>Implementations of this method are expected to set members in the
     * {@code contents} variable that represents the type generated for the
     * response. This variable will already be defined in scope.
     *
     * <p>The contents of the response body will be available in a {@code data} variable.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (data.fieldList !== undefined) {
     *   contents.fieldList = deserializeAws_restJson1_1FieldList(data.fieldList, context);
     * }
     * }</pre>
     * @param context The generation context.
     * @param operation The operation whose input document is being deserialized.
     * @param documentBindings The bindings to read from the document.
     */
    protected abstract void deserializeInputDocumentBody(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );

    /**
     * Writes the code needed to deserialize a protocol output document.
     *
     * <p>Implementations of this method are expected to set members in the
     * {@code contents} variable that represents the type generated for the
     * response. This variable will already be defined in scope.
     *
     * <p>The contents of the response body will be available in a {@code data} variable.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (data.fieldList !== undefined) {
     *   contents.fieldList = deserializeAws_restJson1_1FieldList(data.fieldList, context);
     * }
     * }</pre>
     * @param context The generation context.
     * @param operation The operation whose output document is being deserialized.
     * @param documentBindings The bindings to read from the document.
     */
    protected abstract void deserializeOutputDocumentBody(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );

    /**
     * Writes the code needed to deserialize a protocol error document.
     *
     * <p>Implementations of this method are expected to set members in the
     * {@code contents} variable that represents the type generated for the
     * response. This variable will already be defined in scope.
     *
     * <p>The contents of the response body will be available in a {@code data} variable.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (data.fieldList !== undefined) {
     *   contents.fieldList = deserializeAws_restJson1_1FieldList(data.fieldList, context);
     * }
     * }</pre>
     * @param context The generation context.
     * @param error The error being deserialized.
     * @param documentBindings The bindings to read from the document.
     */
    protected abstract void deserializeErrorDocumentBody(
            GenerationContext context,
            StructureShape error,
            List<HttpBinding> documentBindings
    );
}
