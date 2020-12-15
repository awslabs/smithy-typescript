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

import java.util.Optional;

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.PaginatedIndex;
import software.amazon.smithy.model.knowledge.PaginationInfo;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;

final class PaginationGenerator implements Runnable {

    static final String PAGINATION_INTERFACE_FILE = "pagination/Interfaces.ts";

    private final TypeScriptWriter writer;
    private final PaginationInfo paginatedInfo;

    private final Symbol serviceSymbol;
    private final Symbol operationSymbol;
    private final Symbol inputSymbol;
    private final Symbol outputSymbol;

    private final String operationName;
    private final String methodName;
    private final String nonModularServiceName;
    private final String paginationType;

    PaginationGenerator(
            Model model,
            ServiceShape service,
            OperationShape operation,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer,
            String nonModularServiceName
    ) {

        this.writer = writer;

        this.serviceSymbol = symbolProvider.toSymbol(service);
        this.operationSymbol = symbolProvider.toSymbol(operation);
        this.inputSymbol = symbolProvider.toSymbol(operation).expectProperty("inputType", Symbol.class);
        this.outputSymbol = symbolProvider.toSymbol(operation).expectProperty("outputType", Symbol.class);

        this.operationName = operation.getId().getName();
        this.nonModularServiceName = nonModularServiceName;

        // e.g. listObjects
        this.methodName = Character.toLowerCase(operationName.charAt(0)) + operationName.substring(1);
        this.paginationType = this.nonModularServiceName + "PaginationConfiguration";

        PaginatedIndex paginatedIndex = model.getKnowledge(PaginatedIndex.class);
        Optional<PaginationInfo> paginationInfo = paginatedIndex.getPaginationInfo(service, operation);
        this.paginatedInfo = paginationInfo.orElseThrow(() -> {
            return new CodegenException("Expected Paginator to have pagination information.");
        });
    }

    @Override
    public void run() {
        // Import Service Types
        writer.addImport(operationSymbol.getName(),
                operationSymbol.getName(),
                operationSymbol.getNamespace());
        writer.addImport(inputSymbol.getName(),
                inputSymbol.getName(),
                inputSymbol.getNamespace());
        writer.addImport(outputSymbol.getName(),
                outputSymbol.getName(),
                outputSymbol.getNamespace());
        String nonModularLocation = serviceSymbol.getNamespace()
                .replace(serviceSymbol.getName(), nonModularServiceName);
        writer.addImport(nonModularServiceName,
                nonModularServiceName,
                nonModularLocation);
        writer.addImport(serviceSymbol.getName(), serviceSymbol.getName(), serviceSymbol.getNamespace());

        // Import Pagination types
        writer.addImport("Paginator", "Paginator", "@aws-sdk/types");
        writer.addImport(paginationType, paginationType, "./src/" + PAGINATION_INTERFACE_FILE.replace(".ts", ""));

        writeCommandRequest();
        writeMethodRequest();
        writePager();
    }

    static String getOutputFilelocation(OperationShape operation) {
        return "pagination/" + operation.getId().getName() + "Paginator.ts";
    }

    static void generateServicePaginationInterfaces(
            String nonModularServiceName,
            Symbol service,
            TypeScriptWriter writer
    ) {
        writer.addImport("PaginationConfiguration", "PaginationConfiguration", "@aws-sdk/types");
        String nonModularLocation = service.getNamespace().replace(service.getName(), nonModularServiceName);
        writer.addImport(nonModularServiceName, nonModularServiceName, nonModularLocation);
        writer.addImport(service.getName(), service.getName(), service.getNamespace());

        writer.openBlock("export interface $LPaginationConfiguration extends PaginationConfiguration {",
                "}", nonModularServiceName, () -> {
            writer.write("client: $L | $L;", nonModularServiceName, service.getName());
        });
    }

     private String destructurePath(String path) {
        return "."  + path.replace(".", "!.");
    }

    private void writePager() {
        String serviceTypeName = serviceSymbol.getName();
        String inputTypeName = inputSymbol.getName();
        String outputTypeName = outputSymbol.getName();

        String inputTokenName = paginatedInfo.getPaginatedTrait().getInputToken().get();
        String outputTokenName = paginatedInfo.getPaginatedTrait().getOutputToken().get();

        writer.openBlock(
                "export async function* paginate$L(config: $L, input: $L, ...additionalArguments: any): Paginator<$L>{",
                "}",  operationName, paginationType, inputTypeName, outputTypeName, () -> {
            String destructuredInputTokenName = destructurePath(inputTokenName);
            writer.write("// ToDo: replace with actual type instead of typeof input$L", destructuredInputTokenName);
            writer.write("let token: typeof input$L | undefined = config.startingToken || undefined;",
                    destructuredInputTokenName);

            writer.write("let hasNext = true;");
            writer.write("let page: $L;", outputTypeName);
            writer.openBlock("while (hasNext) {", "}", () -> {
                writer.write("input$L = token;", destructuredInputTokenName);

                if (paginatedInfo.getPageSizeMember().isPresent()) {
                    String pageSize = paginatedInfo.getPageSizeMember().get().getMemberName();
                    writer.write("input[$S] = config.pageSize;", pageSize);
                }

                writer.openBlock("if (config.client instanceof $L) {", "}", nonModularServiceName, () -> {
                    writer.write("page = await makePagedRequest(config.client, input, ...additionalArguments);");
                });
                writer.openBlock("else if (config.client instanceof $L) {", "}", serviceTypeName, () -> {
                    writer.write("page = await makePagedClientRequest(config.client, input, ...additionalArguments);");
                });
                writer.openBlock("else {", "}", () -> {
                    writer.write("throw new Error(\"Invalid client, expected $L | $L\");",
                            nonModularServiceName, serviceTypeName);
                });

                writer.write("yield page;");
                writer.write("token = page$L;", destructurePath(outputTokenName));

                writer.write("hasNext = !!(token);");
            });

            writer.write("// @ts-ignore");
            writer.write("return undefined;");
        });
    }


    /**
     * Paginated command that calls client.method({...}) under the hood. This is meant for server side environments and
     * exposes the entire service.
     */
    private void writeMethodRequest() {
        writer.writeDocs("@private");
        writer.openBlock(
                "const makePagedRequest = async (client: $L, input: $L, ...args: any): Promise<$L> => {",
                "}", nonModularServiceName, inputSymbol.getName(),
                outputSymbol.getName(), () -> {
            writer.write("// @ts-ignore");
            writer.write("return await client.$L(input, ...args);", methodName);
        });
    }

    /**
     * Paginated command that calls CommandClient().send({...}) under the hood. This is meant for client side (browser)
     * environments and does not generally expose the entire service.
     */
    private void writeCommandRequest() {
        writer.writeDocs("@private");
        writer.openBlock(
                "const makePagedClientRequest = async (client: $L, input: $L, ...args: any): Promise<$L> => {",
                "}", serviceSymbol.getName(), inputSymbol.getName(),
                outputSymbol.getName(), () -> {
            writer.write("// @ts-ignore");
            writer.write("return await client.send(new $L(input), ...args);", operationSymbol.getName());
        });
    }
}
