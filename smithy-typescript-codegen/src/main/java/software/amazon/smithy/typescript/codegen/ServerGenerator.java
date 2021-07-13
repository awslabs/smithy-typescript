/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import java.util.Iterator;
import java.util.Set;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
final class ServerGenerator {

    private ServerGenerator() {}

    static void generateOperationsType(SymbolProvider symbolProvider,
                                       Shape serviceShape,
                                       Set<OperationShape> operations,
                                       TypeScriptWriter writer) {
        Symbol serviceSymbol = symbolProvider.toSymbol(serviceShape);
        writer.writeInline("export type $L = ", serviceSymbol.expectProperty("operations", Symbol.class).getName());
        for (Iterator<OperationShape> iter = operations.iterator(); iter.hasNext();) {
            writer.writeInline("$S", iter.next().getId().getName());
            if (iter.hasNext()) {
                writer.writeInline(" | ");
            }
        }
        writer.write(";");
    }

    static void generateServiceHandler(SymbolProvider symbolProvider,
                                       Shape serviceShape,
                                       Set<OperationShape> operations,
                                       TypeScriptWriter writer) {
        addCommonHandlerImports(writer);
        writer.addImport("UnknownOperationException", "__UnknownOperationException", "@aws-smithy/server-common");

        Symbol serviceSymbol = symbolProvider.toSymbol(serviceShape);
        Symbol handlerSymbol = serviceSymbol.expectProperty("handler", Symbol.class);
        Symbol operationsType = serviceSymbol.expectProperty("operations", Symbol.class);

        writeSerdeContextBase(writer);
        writeHandleFunction(writer);

        String classDeclaration = "export class $L<Context> implements __ServiceHandler<Context> {";
        writer.openBlock(classDeclaration, "}", handlerSymbol.getName(), () -> {
            writer.write("private readonly service: $T<Context>;", serviceSymbol);
            writer.write("private readonly mux: __Mux<$S, $T>;", serviceShape.getId().getName(), operationsType);
            writer.write("private readonly serializerFactory: <T extends $T>(operation: T) => "
                            + "__OperationSerializer<$T<Context>, T, __SmithyException>;",
                    operationsType, serviceSymbol);
            writer.write("private readonly serializeFrameworkException: (e: __SmithyFrameworkException, "
                            + "ctx: __ServerSerdeContext) => Promise<__HttpResponse>;");
            writer.write("private readonly validationCustomizer: __ValidationCustomizer<$T>;", operationsType);
            writer.writeDocs(() -> {
                writer.write("Construct a $T handler.", serviceSymbol);
                writer.write("@param service The {@link $1T} implementation that supplies the business logic for $1T",
                        serviceSymbol);
                writer.writeInline("@param mux The {@link __Mux} that determines which service and operation are ");
                writer.write("being invoked by a given {@link __HttpRequest}");
                writer.writeInline("@param serializerFactory A factory for an {@link __OperationSerializer} for each ");
                writer.write("operation in $T that ", serviceSymbol);
                writer.writeInline("                         ")
                      .write("handles deserialization of requests and serialization of responses");
                writer.write("@param serializeFrameworkException A function that can serialize "
                        + "{@link __SmithyFrameworkException}s");
                writer.write("@param validationCustomizer A {@link __ValidationCustomizer} for turning validation "
                        + "failures into {@link __SmithyFrameworkException}s");
            });
            writer.openBlock("constructor(", ") {", () -> {
                writer.write("service: $T<Context>,", serviceSymbol);
                writer.write("mux: __Mux<$S, $T>,", serviceShape.getId().getName(), operationsType);
                writer.write("serializerFactory:<T extends $T>(op: T) => "
                                + "__OperationSerializer<$T<Context>, T, __SmithyException>,",
                        operationsType, serviceSymbol);
                writer.write("serializeFrameworkException: (e: __SmithyFrameworkException, ctx: __ServerSerdeContext) "
                        + "=> Promise<__HttpResponse>,");
                writer.write("validationCustomizer: __ValidationCustomizer<$T>", operationsType);
            });
            writer.indent();
            writer.write("this.service = service;");
            writer.write("this.mux = mux;");
            writer.write("this.serializerFactory = serializerFactory;");
            writer.write("this.serializeFrameworkException = serializeFrameworkException;");
            writer.write("this.validationCustomizer = validationCustomizer;");
            writer.closeBlock("}");
            String handleDecl = "async handle(request: __HttpRequest, context: Context): Promise<__HttpResponse> {";
            writer.openBlock(handleDecl, "}", () -> {
                writer.write("const target = this.mux.match(request);");
                writer.openBlock("if (target === undefined) {", "}", () -> {
                    writer.write("return this.serializeFrameworkException(new __UnknownOperationException(), "
                            + "serdeContextBase);");
                });
                writer.openBlock("switch (target.operation) {", "}", () -> {
                    for (OperationShape operation : operations) {
                        String opName = operation.getId().getName();
                        Symbol operationSymbol = symbolProvider.toSymbol(operation);
                        Symbol inputSymbol = operationSymbol.expectProperty("inputType", Symbol.class);
                        writer.openBlock("case $S : {", "}", opName, () -> {
                            writer.write("return handle(request, context, $1S, this.serializerFactory($1S), "
                                    + "this.service.$2L, this.serializeFrameworkException, $3T.validate, "
                                    + "this.validationCustomizer);",
                                    opName, operationSymbol.getName(), inputSymbol);
                        });
                    }
                });
            });
        });
    }

    static void generateOperationHandler(SymbolProvider symbolProvider,
                                         Shape serviceShape,
                                         OperationShape operation,
                                         TypeScriptWriter writer) {
        addCommonHandlerImports(writer);

        writeSerdeContextBase(writer);
        writeHandleFunction(writer);

        String operationName = operation.getId().getName();
        Symbol serviceSymbol = symbolProvider.toSymbol(serviceShape);
        Symbol operationSymbol = symbolProvider.toSymbol(operation);

        Symbol inputSymbol = operationSymbol.expectProperty("inputType", Symbol.class);
        Symbol outputSymbol = operationSymbol.expectProperty("outputType", Symbol.class);
        Symbol handlerSymbol = operationSymbol.expectProperty("handler", Symbol.class);
        Symbol errorsSymbol = operationSymbol.expectProperty("errorsType", Symbol.class);

        String declaration = "export class $L<Context> implements __ServiceHandler<Context> {";
        writer.openBlock(declaration, "}", handlerSymbol.getName(), () -> {
            writer.write("private readonly operation: __Operation<$T, $T, Context>;", inputSymbol, outputSymbol);
            writer.write("private readonly mux: __Mux<$S, $S>;", serviceShape.getId().getName(), operationName);
            writer.write("private readonly serializer: __OperationSerializer<$T<Context>, $S, $T>;",
                    serviceSymbol, operationName, errorsSymbol);
            writer.write("private readonly serializeFrameworkException: (e: __SmithyFrameworkException, "
                    + "ctx: __ServerSerdeContext) => Promise<__HttpResponse>;");
            writer.write("private readonly validationCustomizer: __ValidationCustomizer<$S>;", operationName);
            writer.writeDocs(() -> {
                writer.write("Construct a $T handler.", operationSymbol);
                writer.write("@param operation The {@link __Operation} implementation that supplies the business "
                                + "logic for $1T", operationSymbol);
                writer.writeInline("@param mux The {@link __Mux} that verifies which service and operation are being ");
                writer.write("invoked by a given {@link __HttpRequest}");
                writer.write("@param serializer An {@link __OperationSerializer} for $T that ", operationSymbol);
                writer.writeInline("                  ")
                        .write("handles deserialization of requests and serialization of responses");
                writer.write("@param serializeFrameworkException A function that can serialize "
                        + "{@link __SmithyFrameworkException}s");
                writer.write("@param validationCustomizer A {@link __ValidationCustomizer} for turning validation "
                        + "failures into {@link __SmithyFrameworkException}s");
            });
            writer.openBlock("constructor(", ") {", () -> {
                writer.write("operation: __Operation<$T, $T, Context>,", inputSymbol, outputSymbol);
                writer.write("mux: __Mux<$S, $S>,", serviceShape.getId().getName(), operationName);
                writer.write("serializer: __OperationSerializer<$T<Context>, $S, $T>,",
                        serviceSymbol, operationName, errorsSymbol);
                writer.write("serializeFrameworkException: (e: __SmithyFrameworkException, ctx: __ServerSerdeContext) "
                        + "=> Promise<__HttpResponse>,");
                writer.write("validationCustomizer: __ValidationCustomizer<$S>", operationName);
            });
            writer.indent();
            writer.write("this.operation = operation;");
            writer.write("this.mux = mux;");
            writer.write("this.serializer = serializer;");
            writer.write("this.serializeFrameworkException = serializeFrameworkException;");
            writer.write("this.validationCustomizer = validationCustomizer;");
            writer.closeBlock("}");
            writer.openBlock("async handle(request: __HttpRequest, context: Context): Promise<__HttpResponse> {",
                "}",
                () -> {
                    writer.write("const target = this.mux.match(request);");
                    writer.openBlock("if (target === undefined) {", "}", () -> {
                        writer.write("console.log('Received a request that did not match $L.$L. This indicates a "
                                + "misconfiguration.');", serviceShape.getId(), operation.getId().getName());
                        writer.write("return this.serializeFrameworkException(new __InternalFailureException(), "
                                + "serdeContextBase);");
                    });
                    writer.write("return handle(request, context, $S, this.serializer, this.operation, "
                            + "this.serializeFrameworkException, $T.validate, this.validationCustomizer);",
                            operationName, inputSymbol);
                }
            );
        });
    }

    private static void addCommonHandlerImports(TypeScriptWriter writer) {
        writer.addImport("Operation", "__Operation", "@aws-smithy/server-common");
        writer.addImport("ServiceHandler", "__ServiceHandler", "@aws-smithy/server-common");
        writer.addImport("Mux", "__Mux", "@aws-smithy/server-common");
        writer.addImport("OperationSerializer", "__OperationSerializer", "@aws-smithy/server-common");
        writer.addImport("InternalFailureException", "__InternalFailureException", "@aws-smithy/server-common");
        writer.addImport("SerializationException", "__SerializationException", "@aws-smithy/server-common");
        writer.addImport("SmithyFrameworkException", "__SmithyFrameworkException", "@aws-smithy/server-common");
        writer.addImport("HttpRequest", "__HttpRequest", "@aws-sdk/protocol-http");
        writer.addImport("HttpResponse", "__HttpResponse", "@aws-sdk/protocol-http");
        writer.addImport("SmithyException", "__SmithyException", "@aws-sdk/types");
        writer.addImport("ValidationCustomizer", "__ValidationCustomizer", "@aws-smithy/server-common");
    }

    private static void writeHandleFunction(TypeScriptWriter writer) {
        writer.addImport("Operation", "__Operation", "@aws-smithy/server-common");
        writer.addImport("OperationInput", "__OperationInput", "@aws-smithy/server-common");
        writer.addImport("OperationOutput", "__OperationOutput", "@aws-smithy/server-common");
        writer.addImport("ValidationFailure", "__ValidationFailure", "@aws-smithy/server-common");
        writer.addImport("ValidationCustomizer", "__ValidationCustomizer", "@aws-smithy/server-common");

        writer.openBlock("async function handle<S, O extends keyof S & string, Context>(",
                "): Promise<__HttpResponse> {",
                () -> {
                    writer.write("request: __HttpRequest,");
                    writer.write("context: Context,");
                    writer.write("operationName: O,");
                    writer.write("serializer: __OperationSerializer<S, O, __SmithyException>,");
                    writer.write("operation: __Operation<__OperationInput<S[O]>, __OperationOutput<S[O]>, Context>,");
                    writer.write("serializeFrameworkException: (e: __SmithyFrameworkException, "
                            + "ctx: __ServerSerdeContext) => Promise<__HttpResponse>,");
                    writer.write("validationFn: (input: __OperationInput<S[O]>) => __ValidationFailure[],");
                    writer.write("validationCustomizer: __ValidationCustomizer<O>");
                });
        writer.indent();
        writer.write("let input;");
        writer.openBlock("try {", "} catch (error: unknown) {", () -> {
            writer.openBlock("input = await serializer.deserialize(request, {", "});", () -> {
                writer.write("endpoint: () => Promise.resolve(request), ...serdeContextBase");
            });
        });
        writer.indent();
        writer.write("return serializeFrameworkException(new __SerializationException(), "
                + "serdeContextBase);");
        writer.closeBlock("}");
        writer.openBlock("try {", "} catch(error: unknown) {", () -> {
            writer.write("let validationFailures = validationFn(input);");
            writer.openBlock("if (validationFailures && validationFailures.length > 0) {", "}", () -> {
                writer.write("let validationException = validationCustomizer({ operation: operationName }, "
                    + "validationFailures);");
                writer.openBlock("if (validationException) {", "}", () -> {
                    writer.write("return serializer.serializeError(validationException, serdeContextBase);");
                });
            });
            writer.write("let output = await operation(input, context);");
            writer.write("return serializer.serialize(output, serdeContextBase);");
        });
        writer.indent();
        writer.openBlock("if (serializer.isOperationError(error)) {", "}", () -> {
            writer.write("return serializer.serializeError(error, serdeContextBase);");
        });
        writer.write("console.log('Received an unexpected error', error);");
        writer.write("return serializeFrameworkException(new __InternalFailureException(), "
                + "serdeContextBase);");
        writer.closeBlock("}");
        writer.closeBlock("}");
    }

    private static void writeSerdeContextBase(TypeScriptWriter writer) {
        writer.addImport("ServerSerdeContext", "__ServerSerdeContext", "@aws-smithy/server-common");
        writer.addImport("NodeHttpHandler", null, "@aws-sdk/node-http-handler");
        writer.addImport("streamCollector", null, "@aws-sdk/node-http-handler");
        writer.addImport("fromBase64", null, "@aws-sdk/util-base64-node");
        writer.addImport("toBase64", null, "@aws-sdk/util-base64-node");
        writer.addImport("fromUtf8", null, "@aws-sdk/util-utf8-node");
        writer.addImport("toUtf8", null, "@aws-sdk/util-utf8-node");

        writer.openBlock("const serdeContextBase = {", "};", () -> {
            writer.write("base64Encoder: toBase64,");
            writer.write("base64Decoder: fromBase64,");
            writer.write("utf8Encoder: toUtf8,");
            writer.write("utf8Decoder: fromUtf8,");
            writer.write("streamCollector: streamCollector,");
            writer.write("requestHandler: new NodeHttpHandler(),");
            writer.write("disableHostPrefix: true");
        });
    }

    static void generateServerInterfaces(SymbolProvider symbolProvider,
                                         ServiceShape service,
                                         Set<OperationShape> operations,
                                         TypeScriptWriter writer) {
        writer.addImport("Operation", "__Operation", "@aws-smithy/server-common");

        String serviceInterfaceName = symbolProvider.toSymbol(service).getName();

        writer.openBlock("export interface $L<Context> {", "}", serviceInterfaceName, () -> {
            for (OperationShape operation : operations) {
                Symbol symbol = symbolProvider.toSymbol(operation);
                writer.write("$L: $T<Context>", symbol.getName(), symbol);
            }
        });
    }
}
