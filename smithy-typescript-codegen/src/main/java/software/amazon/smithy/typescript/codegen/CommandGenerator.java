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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.typescript.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.utils.OptionalUtils;

/**
 * Generates a client command using plugins.
 */
final class CommandGenerator implements Runnable {

    static final String COMMAND_PROPERTIES_SECTION = "command_properties";
    static final String COMMAND_BODY_EXTRA_SECTION = "command_body_extra";
    static final String COMMAND_CONSTRUCTOR_SECTION = "command_constructor";

    private final TypeScriptSettings settings;
    private final Model model;
    private final ServiceShape service;
    private final OperationShape operation;
    private final SymbolProvider symbolProvider;
    private final TypeScriptWriter writer;
    private final Symbol symbol;
    private final List<RuntimeClientPlugin> runtimePlugins;
    private final OperationIndex operationIndex;
    private final Symbol inputType;
    private final Symbol outputType;
    private final ProtocolGenerator protocolGenerator;
    private final ApplicationProtocol applicationProtocol;

    CommandGenerator(
            TypeScriptSettings settings,
            Model model,
            OperationShape operation,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer,
            List<RuntimeClientPlugin> runtimePlugins,
            ProtocolGenerator protocolGenerator,
            ApplicationProtocol applicationProtocol
    ) {
        this.settings = settings;
        this.model = model;
        this.service = settings.getService(model);
        this.operation = operation;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.runtimePlugins = runtimePlugins.stream()
                .filter(plugin -> plugin.matchesOperation(model, service, operation))
                .collect(Collectors.toList());
        this.protocolGenerator = protocolGenerator;
        this.applicationProtocol = applicationProtocol;

        symbol = symbolProvider.toSymbol(operation);
        operationIndex = model.getKnowledge(OperationIndex.class);
        inputType = symbol.expectProperty("inputType", Symbol.class);
        outputType = symbol.expectProperty("outputType", Symbol.class);
    }

    @Override
    public void run() {
        Symbol serviceSymbol = symbolProvider.toSymbol(service);
        String configType = ServiceGenerator.getResolvedConfigTypeName(serviceSymbol);

        // Add required imports.
        writer.addImport(configType, configType, serviceSymbol.getNamespace());
        writer.addImport("ServiceInputTypes", "ServiceInputTypes", serviceSymbol.getNamespace());
        writer.addImport("ServiceOutputTypes", "ServiceOutputTypes", serviceSymbol.getNamespace());
        writer.addImport("Command", "$Command", "@aws-sdk/smithy-client");
        writer.addImport("FinalizeHandlerArguments", "FinalizeHandlerArguments", "@aws-sdk/types");
        writer.addImport("Handler", "Handler", "@aws-sdk/types");
        writer.addImport("HandlerExecutionContext", "HandlerExecutionContext", "@aws-sdk/types");
        writer.addImport("MiddlewareStack", "MiddlewareStack", "@aws-sdk/types");

        addInputAndOutputTypes();

        String name = symbol.getName();
        writer.writeShapeDocs(operation);
        writer.openBlock("export class $L extends $$Command<$T, $T, $L> {", "}", name, inputType, outputType,
                configType, () -> {

            // Section for adding custom command properties.
            writer.write("// Start section: $L", COMMAND_PROPERTIES_SECTION);
            writer.pushState(COMMAND_PROPERTIES_SECTION).popState();
            writer.write("// End section: $L", COMMAND_PROPERTIES_SECTION);
            writer.write("");

            generateCommandConstructor();
            writer.write("");
            generateCommandMiddlewareResolver(configType);
            writeSerde();

            // Hook for adding more methods to the command.
            writer.write("// Start section: $L", COMMAND_BODY_EXTRA_SECTION)
                    .pushState(COMMAND_BODY_EXTRA_SECTION)
                    .popState()
                    .write("// End section: $L", COMMAND_BODY_EXTRA_SECTION);
        });
    }

    private void generateCommandConstructor() {
        writer.openBlock("constructor(readonly input: $T) {", "}", inputType, () -> {
            // The constructor can be intercepted and changed.
            writer.write("// Start section: $L", COMMAND_CONSTRUCTOR_SECTION)
                    .pushState(COMMAND_CONSTRUCTOR_SECTION)
                    .write("super();")
                    .popState()
                    .write("// End section: $L", COMMAND_CONSTRUCTOR_SECTION);
        });
    }

    private void generateCommandMiddlewareResolver(String configType) {
        Symbol serde = TypeScriptDependency.MIDDLEWARE_SERDE.createSymbol("getSerdePlugin");
        writer.writeDocs("@internal");
        writer.write("resolveMiddleware(")
                .indent()
                .write("clientStack: MiddlewareStack<$L, $L>,", "ServiceInputTypes", "ServiceOutputTypes")
                .write("configuration: $L,", configType)
                .write("options?: $T", applicationProtocol.getOptionsType())
                .dedent();
        writer.openBlock("): Handler<$T, $T> {", "}", inputType, outputType, () -> {
            // Add serialization and deserialization plugin.
            writer.write("this.middlewareStack.use($T(configuration, this.serialize, this.deserialize));", serde);

            // Add customizations.
            addCommandSpecificPlugins();

            // Resolve the middleware stack.
            writer.write("\nconst stack = clientStack.concat(this.middlewareStack);\n");
            writer.write("const { logger } = configuration;");
            writer.write("const clientName = $S;", symbolProvider.toSymbol(service).getName());
            writer.write("const commandName = $S;", symbolProvider.toSymbol(operation).getName());
            writer.openBlock("const handlerExecutionContext: HandlerExecutionContext = {", "}", () -> {
                writer.write("logger,");
                writer.write("clientName,");
                writer.write("commandName,");
                writer.openBlock("inputFilterSensitiveLog: ", ",", () -> {
                    OptionalUtils.ifPresentOrElse(operationIndex.getInput(operation),
                        input -> writer.writeInline("$T.filterSensitiveLog", symbolProvider.toSymbol(input)),
                        () -> writer.writeInline("(input: any) => input"));
                });
                writer.openBlock("outputFilterSensitiveLog: ", ",", () -> {
                    OptionalUtils.ifPresentOrElse(operationIndex.getOutput(operation),
                        output -> writer.writeInline("$T.filterSensitiveLog", symbolProvider.toSymbol(output)),
                        () -> writer.writeInline("(output: any) => output"));
                });
            });
            writer.openBlock("\nif (typeof logger.info === 'function') {", "}\n", () -> {
                writer.openBlock("logger.info({", "});", () -> {
                    writer.write("clientName,");
                    writer.write("commandName,");
                });
            });
            writer.write("const { requestHandler } = configuration;");
            writer.openBlock("return stack.resolve(", ");", () -> {
                writer.write("(request: FinalizeHandlerArguments<any>) => ");
                writer.write("  requestHandler.handle(request.request as $T, options || {}),",
                             applicationProtocol.getRequestType());
                writer.write("handlerExecutionContext");
            });
        });
    }

    private void addInputAndOutputTypes() {
        writeInputType(inputType.getName(), operationIndex.getInput(operation));
        writeOutputType(outputType.getName(), operationIndex.getOutput(operation));
        writer.write("");
    }

    private void writeInputType(String typeName, Optional<StructureShape> inputShape) {
        if (inputShape.isPresent()) {
            StructureShape input = inputShape.get();
            List<MemberShape> blobStreamingMembers = getBlobStreamingMembers(input);
            if (blobStreamingMembers.isEmpty()) {
                writer.write("export type $L = $T;", typeName, symbolProvider.toSymbol(input));
            } else {
                writeStreamingInputType(typeName, input, blobStreamingMembers.get(0));
            }
        } else {
            // If the input is non-existent, then use an empty object.
            writer.write("export type $L = {}", typeName);
        }
    }

    private void writeOutputType(String typeName, Optional<StructureShape> outputShape) {
        // Output types should always be MetadataBearers, possibly in addition
        // to a defined output shape.
        writer.addImport("MetadataBearer", "__MetadataBearer", TypeScriptDependency.AWS_SDK_TYPES.packageName);
        if (outputShape.isPresent()) {
            writer.write("export type $L = $T & __MetadataBearer;",
                    typeName, symbolProvider.toSymbol(outputShape.get()));
        } else {
            writer.write("export type $L = __MetadataBearer", typeName);
        }
    }

    private List<MemberShape> getBlobStreamingMembers(StructureShape shape) {
        return shape.getAllMembers().values().stream()
                .filter(memberShape -> {
                    // Streaming blobs need to have their types modified
                    // See `writeStreamingInputType`
                    Shape target = model.expectShape(memberShape.getTarget());
                    return target.isBlobShape() && target.hasTrait(StreamingTrait.class);
                })
                .collect(Collectors.toList());
    }

    /**
     * Ease the input streaming member restriction so that users don't need to construct a stream every time.
     * This type decoration is allowed in Smithy because it makes input type more permissive than output type
     * for the same member.
     * Refer here for more rationales: https://github.com/aws/aws-sdk-js-v3/issues/843
     */
    private void writeStreamingInputType(String typeName, StructureShape inputShape, MemberShape streamingMember) {
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        String memberName = streamingMember.getMemberName();
        String optionalSuffix = streamingMember.isRequired() ? "" : "?";
        writer.openBlock("export type $L = Omit<$T, $S> & {", "};", typeName, inputSymbol, memberName, () ->
            writer.write("$1L$2L: $3T[$1S]|string|Uint8Array|Buffer;", memberName, optionalSuffix, inputSymbol));
    }

    private void addCommandSpecificPlugins() {
        // Some plugins might only apply to specific commands. They are added to the
        // command's middleware stack here. Plugins that apply to all commands are
        // applied automatically when the Command's middleware stack is copied from
        // the service's middleware stack.
        for (RuntimeClientPlugin plugin : runtimePlugins) {
            plugin.getPluginFunction().ifPresent(symbol -> {
                writer.write("this.middlewareStack.use($T(configuration));", symbol);
            });
        }
    }

    private void writeSerde() {
        writer.write("")
                .write("private serialize(")
                .indent()
                    .write("input: $T,", inputType)
                    .write("context: $L", CodegenUtils.getOperationSerializerContextType(writer, model, operation))
                .dedent()
                .openBlock(
                        "): Promise<$T> {", "}",
                        applicationProtocol.getRequestType(),
                        () -> writeSerdeDispatcher(true)
                );

        writer.write("")
                .write("private deserialize(")
                .indent()
                    .write("output: $T,", applicationProtocol.getResponseType())
                    .write("context: $L", CodegenUtils.getOperationDeserializerContextType(writer, model, operation))
                .dedent()
                .openBlock("): Promise<$T> {", "}", outputType, () -> writeSerdeDispatcher(false))
                .write("");
    }

    private void writeSerdeDispatcher(boolean isInput) {
        // For example:
        // return getFooCommandAws_RestJson1_1Serialize(input, utils);
        if (protocolGenerator == null) {
            writer.write("throw new Error(\"No supported protocol was found\");");
        } else {
            String serdeFunctionName = isInput
                    ? ProtocolGenerator.getSerFunctionName(symbol, protocolGenerator.getName())
                    : ProtocolGenerator.getDeserFunctionName(symbol, protocolGenerator.getName());
            writer.addImport(serdeFunctionName, serdeFunctionName,
                             "./src/protocols/" + ProtocolGenerator.getSanitizedName(protocolGenerator.getName()));
            writer.write("return $L($L, context);", serdeFunctionName, isInput ? "input" : "output");
        }
    }
}
