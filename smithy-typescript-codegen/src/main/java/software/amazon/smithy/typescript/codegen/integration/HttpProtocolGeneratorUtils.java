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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.knowledge.HttpBinding.Location;
import software.amazon.smithy.model.pattern.SmithyPattern;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EndpointTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.RetryableTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.typescript.codegen.CodegenUtils;
import software.amazon.smithy.typescript.codegen.TypeScriptDependency;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;
import software.amazon.smithy.typescript.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Utility methods for generating HTTP protocols.
 */
@SmithyUnstableApi
public final class HttpProtocolGeneratorUtils {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private HttpProtocolGeneratorUtils() {}

    /**
     * Given a format and a source of data, generate an input value provider for the
     * timestamp.
     *
     * @param context The generation context.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param shape The shape that represents the value being provided.
     * @param format The timestamp format to provide.
     * @return Returns a value or expression of the input timestamp.
     */
    public static String getTimestampInputParam(
            GenerationContext context,
            String dataSource,
            Shape shape,
            Format format
    ) {
        switch (format) {
            case DATE_TIME:
                // Use the split to not serialize milliseconds.
                return "(" + dataSource + ".toISOString().split('.')[0]+\"Z\")";
            case EPOCH_SECONDS:
                return "Math.round(" + dataSource + ".getTime() / 1000)";
            case HTTP_DATE:
                context.getWriter().addImport("dateToUtcString", "__dateToUtcString", "@aws-sdk/smithy-client");
                return "__dateToUtcString(" + dataSource + ")";
            default:
                throw new CodegenException("Unexpected timestamp format `" + format.toString() + "` on " + shape);
        }
    }

    /**
     * Given a format and a source of data, generate an output value provider for the
     * timestamp.
     *
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param bindingType How this value is bound to the operation output.
     * @param shape The shape that represents the value being received.
     * @param format The timestamp format to provide.
     * @return Returns a value or expression of the output timestamp.
     */
    public static String getTimestampOutputParam(String dataSource, Location bindingType, Shape shape, Format format) {
        String modifiedSource;
        switch (format) {
            case DATE_TIME:
            case HTTP_DATE:
                modifiedSource = dataSource;
                break;
            case EPOCH_SECONDS:
                modifiedSource = dataSource;
                // Make sure we turn a header, label, or query's forced string into a number.
                if (bindingType.equals(Location.HEADER) || bindingType.equals(Location.QUERY)
                        || bindingType.equals(Location.LABEL)) {
                    modifiedSource = "parseInt(" + modifiedSource + ", 10)";
                }
                // Convert whole and decimal numbers to milliseconds.
                modifiedSource = "Math.round(" + modifiedSource + " * 1000)";
                break;
            default:
                throw new CodegenException("Unexpected timestamp format `" + format.toString() + "` on " + shape);
        }

        return "new Date(" + modifiedSource + ")";
    }

    /**
     * Given a String input, determine its media type and generate an input value
     * provider for it.
     *
     * <p>This currently only supports using the LazyJsonString for {@code "application/json"}.
     *
     * @param context The generation context.
     * @param shape The shape that represents the value being provided.
     * @param dataSource The in-code location of the data to provide an input of
     *   ({@code input.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the input string.
     */
    static String getStringInputParam(GenerationContext context, Shape shape, String dataSource) {
        // Handle media type generation, defaulting to the dataSource.
        Optional<MediaTypeTrait> mediaTypeTrait = shape.getTrait(MediaTypeTrait.class);
        if (mediaTypeTrait.isPresent()) {
            String mediaType = mediaTypeTrait.get().getValue();
            if (CodegenUtils.isJsonMediaType(mediaType)) {
                TypeScriptWriter writer = context.getWriter();
                writer.addImport("LazyJsonString", "__LazyJsonString", "@aws-sdk/smithy-client");
                return "__LazyJsonString.fromObject(" + dataSource + ")";
            } else {
                LOGGER.warning(() -> "Found unsupported mediatype " + mediaType + " on String shape: " + shape);
            }
        }

        return dataSource;
    }

    /**
     * Given a String output, determine its media type and generate an output value
     * provider for it.
     *
     * <p>This currently only supports using the LazyJsonString for {@code "application/json"}.
     *
     * @param context The generation context.
     * @param shape The shape that represents the value being received.
     * @param dataSource The in-code location of the data to provide an output of
     *   ({@code output.foo}, {@code entry}, etc.)
     * @param useExpect Whether or not to wrap the string in expectString. This should
     *   only be false if the value is guaranteed to be a string already.
     * @return Returns a value or expression of the output string.
     */
    static String getStringOutputParam(GenerationContext context, Shape shape, String dataSource, boolean useExpect) {
        // Handle media type generation, defaulting to a standard String.
        Optional<MediaTypeTrait> mediaTypeTrait = shape.getTrait(MediaTypeTrait.class);
        if (mediaTypeTrait.isPresent()) {
            String mediaType = mediaTypeTrait.get().getValue();
            if (CodegenUtils.isJsonMediaType(mediaType)) {
                TypeScriptWriter writer = context.getWriter();
                writer.addImport("LazyJsonString", "__LazyJsonString", "@aws-sdk/smithy-client");
                return "new __LazyJsonString(" + dataSource + ")";
            } else {
                LOGGER.warning(() -> "Found unsupported mediatype " + mediaType + " on String shape: " + shape);
            }
        }

        if (!useExpect) {
            return dataSource;
        }
        context.getWriter().addImport("expectString", "__expectString", "@aws-sdk/smithy-client");
        return "__expectString(" + dataSource + ")";
    }

    /**
     * Given a String output, determine its media type and generate an output value
     * provider for it.
     *
     * <p>This currently only supports using the LazyJsonString for {@code "application/json"}.
     *
     * @param context The generation context.
     * @param shape The shape that represents the value being received.
     * @param dataSource The in-code location of the data to provide an output of
     *   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output string.
     */
    static String getStringOutputParam(GenerationContext context, Shape shape, String dataSource) {
        return getStringOutputParam(context, shape, dataSource, true);
    }

    /**
     * Writes a response metadata deserializer function for HTTP protocols. This
     * will load things like the status code, headers, and more.
     *
     * @param context The generation context.
     * @param responseType The response type for the HTTP protocol.
     */
    static void generateMetadataDeserializer(GenerationContext context, SymbolReference responseType) {
        TypeScriptWriter writer = context.getWriter();

        writer.addImport("ResponseMetadata", "__ResponseMetadata", "@aws-sdk/types");
        writer.openBlock("const deserializeMetadata = (output: $T): __ResponseMetadata => ({", "});", responseType,
                () -> {
                    writer.write("httpStatusCode: output.statusCode,");
                    writer.write("requestId: output.headers[\"x-amzn-requestid\"] ??"
                        + " output.headers[\"x-amzn-request-id\"],");
                    writer.write("extendedRequestId: output.headers[\"x-amz-id-2\"],");
                    writer.write("cfId: output.headers[\"x-amz-cf-id\"],");
                });
        writer.write("");
    }

    /**
     * Writes a response body stream collector. This function converts the low-level response body stream to
     * Uint8Array binary data.
     *
     * @param context The generation context.
     */
    static void generateCollectBody(GenerationContext context) {
        TypeScriptWriter writer = context.getWriter();

        writer.addImport("SerdeContext", "__SerdeContext", "@aws-sdk/types");
        writer.write("// Collect low-level response body stream to Uint8Array.");
        writer.openBlock("const collectBody = (streamBody: any = new Uint8Array(), context: __SerdeContext): "
                + "Promise<Uint8Array> => {", "};", () -> {
            writer.openBlock("if (streamBody instanceof Uint8Array) {", "}", () -> {
                writer.write("return Promise.resolve(streamBody);");
            });
            writer.write("return context.streamCollector(streamBody) || Promise.resolve(new Uint8Array());");
        });

        writer.write("");
    }

    /**
     * Writes a function converting the low-level response body stream to utf-8 encoded string. It depends on
     * response body stream collector {@link #generateCollectBody(GenerationContext)}.
     *
     * @param context The generation context
     */
    static void generateCollectBodyString(GenerationContext context) {
        TypeScriptWriter writer = context.getWriter();

        writer.addImport("SerdeContext", "__SerdeContext", "@aws-sdk/types");
        writer.write("// Encode Uint8Array data into string with utf-8.");
        writer.write("const collectBodyString = (streamBody: any, context: __SerdeContext): Promise<string> => "
                + "collectBody(streamBody, context).then(body => context.utf8Encoder(body))");
        writer.write("");
    }

    /**
     * Writes any additional utils needed for HTTP protocols with bindings.
     *
     * @param context The generation context.
     */
    static void generateHttpBindingUtils(GenerationContext context) {
        TypeScriptWriter writer = context.getWriter();
        writer.write(IoUtils.readUtf8Resource(HttpProtocolGeneratorUtils.class, "http-binding-utils.ts"));
    }

    /**
     * Writes $retryable key for error if it contains RetryableTrait.
     *
     * @param writer The code writer.
     * @param error The error to write retryableTrait for.
     * @param separator The string to be used after emitting key-value pair for retryableTrait.
     */
    public static void writeRetryableTrait(TypeScriptWriter writer, StructureShape error, String separator) {
        Optional<RetryableTrait> retryableTrait = error.getTrait(RetryableTrait.class);
        if (retryableTrait.isPresent()) {
            String textAfterBlock = String.format("}%s", separator);
            writer.openBlock("$$retryable: {", textAfterBlock, () -> {
                if (retryableTrait.get().getThrottling()) {
                    writer.write("throttling: true,");
                }
            });
        }
    }

    /**
     * Writes a function used to dispatch to the proper error deserializer
     * for each error that the operation can return. The generated function
     * assumes a deserialization function is generated for the structures
     * returned.
     *
     * @param context The generation context.
     * @param operation The operation to generate for.
     * @param responseType The response type for the HTTP protocol.
     * @param errorCodeGenerator A consumer
     * @param shouldParseErrorBody Flag indicating whether need to parse response body in this dispatcher function
     * @param bodyErrorLocationModifier A function that returns the location of an error in a body given a data source.
     * @return A set of all error structure shapes for the operation that were dispatched to.
     */
    static Set<StructureShape> generateErrorDispatcher(
            GenerationContext context,
            OperationShape operation,
            SymbolReference responseType,
            Consumer<GenerationContext> errorCodeGenerator,
            boolean shouldParseErrorBody,
            BiFunction<GenerationContext, String, String> bodyErrorLocationModifier
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Set<StructureShape> errorShapes = new TreeSet<>();

        Symbol symbol = symbolProvider.toSymbol(operation);
        Symbol outputType = symbol.expectProperty("outputType", Symbol.class);
        String errorMethodName = ProtocolGenerator.getDeserFunctionName(symbol, context.getProtocolName()) + "Error";

        writer.openBlock("const $L = async(\n"
                       + "  output: $T,\n"
                       + "  context: __SerdeContext,\n"
                       + "): Promise<$T> => {", "}", errorMethodName, responseType, outputType, () -> {
            // Prepare error response for parsing error code. If error code needs to be parsed from response body
            // then we collect body and parse it to JS object, otherwise leave the response body as is.
            if (shouldParseErrorBody) {
                writer.openBlock("const parsedOutput: any = {", "};",
                        () -> {
                            writer.write("...output,");
                            writer.write("body: await parseBody(output.body, context)");
                        });
            }

            // Error responses must be at least SmithyException and MetadataBearer implementations.
            writer.addImport("SmithyException", "__SmithyException",
                    TypeScriptDependency.AWS_SDK_TYPES.packageName);
            writer.addImport("MetadataBearer", "__MetadataBearer",
                    TypeScriptDependency.AWS_SDK_TYPES.packageName);
            // These responses will also have additional properties, so enable that on the interface.
            writer.write("let response: __SmithyException & __MetadataBearer & {[key: string]: any};");
            writer.write("let errorCode: string = \"UnknownError\";");
            errorCodeGenerator.accept(context);
            writer.openBlock("switch (errorCode) {", "}", () -> {
                // Generate the case statement for each error, invoking the specific deserializer.
                new TreeSet<>(operation.getErrors()).forEach(errorId -> {
                    StructureShape error = context.getModel().expectShape(errorId).asStructureShape().get();
                    // Track errors bound to the operation so their deserializers may be generated.
                    errorShapes.add(error);
                    Symbol errorSymbol = symbolProvider.toSymbol(error);
                    String errorDeserMethodName = ProtocolGenerator.getDeserFunctionName(errorSymbol,
                            context.getProtocolName()) + "Response";
                    writer.openBlock("case $S:\ncase $S:", "  break;", errorId.getName(), errorId.toString(), () -> {
                        // Dispatch to the error deserialization function.
                        String outputParam = shouldParseErrorBody ? "parsedOutput" : "output";
                        writer.openBlock("response = {", "}", () -> {
                            writer.write("...await $L($L, context),", errorDeserMethodName, outputParam);
                            writer.write("name: errorCode,");
                            writer.write("$$metadata: deserializeMetadata(output),");
                        });
                    });
                });

                // Build a generic error the best we can for ones we don't know about.
                writer.write("default:").indent();
                        if (shouldParseErrorBody) {
                            // Body is already parsed above
                            writer.write("const parsedBody = parsedOutput.body;");
                        } else {
                            // Body is not parsed above, so parse it here
                            writer.write("const parsedBody = await parseBody(output.body, context);");
                        }

                        // Get the protocol specific error location for retrieving contents.
                        String errorLocation = bodyErrorLocationModifier.apply(context, "parsedBody");
                        writer.write("errorCode = $1L.code || $1L.Code || errorCode;", errorLocation);
                        writer.openBlock("response = {", "} as any;", () -> {
                            writer.write("...$L,", errorLocation);
                            writer.write("name: `$${errorCode}`,");
                            writer.write("message: $1L.message || $1L.Message || errorCode,", errorLocation);
                            writer.write("$$fault: \"client\",");
                            writer.write("$$metadata: deserializeMetadata(output)");
                        }).dedent();
            });

            // Attempt to pull out the exception message for clearer JS errors,
            // and then clean up the response object.
            writer.write("const message = response.message || response.Message || errorCode;");
            writer.write("response.message = message;");
            writer.write("delete response.Message;");

            writer.write("return Promise.reject(Object.assign(new Error(message), response));");
        });
        writer.write("");

        return errorShapes;
    }

    /**
     * Writes resolved hostname, prepending existing hostname with hostPrefix and replacing each hostLabel with
     * the corresponding top-level input member value.
     *
     * @param context The generation context.
     * @param operation The operation to generate for.
     */
    static void writeHostPrefix(GenerationContext context, OperationShape operation) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        EndpointTrait trait = operation.getTrait(EndpointTrait.class).get();
        writer.write("let { hostname: resolvedHostname } = await context.endpoint();");
        // Check if disableHostPrefixInjection has been set to true at runtime
        writer.openBlock("if (context.disableHostPrefix !== true) {", "}", () -> {
            writer.addImport("isValidHostname", "__isValidHostname",
                    TypeScriptDependency.AWS_SDK_PROTOCOL_HTTP.packageName);
            writer.write("resolvedHostname = $S + resolvedHostname;", trait.getHostPrefix().toString());
            if (operation.getInput().isPresent()) {
                List<SmithyPattern.Segment> prefixLabels = trait.getHostPrefix().getLabels();
                StructureShape inputShape = context.getModel().expectShape(operation.getInput()
                        .get(), StructureShape.class);
                for (SmithyPattern.Segment label : prefixLabels) {
                    MemberShape member = inputShape.getMember(label.getContent()).get();
                    String memberName = symbolProvider.toMemberName(member);
                    writer.openBlock("if (input.$L === undefined) {", "}", memberName, () -> {
                        writer.write("throw new Error('Empty value provided for input host prefix: $L.');", memberName);
                    });
                    writer.write("resolvedHostname = resolvedHostname.replace(\"{$L}\", input.$L!)",
                            label.getContent(), memberName);
                }
            }
            writer.openBlock("if (!__isValidHostname(resolvedHostname)) {", "}", () -> {
                writer.write("throw new Error(\"ValidationError: prefixed hostname must be hostname compatible.\");");
            });
        });
    }
}
