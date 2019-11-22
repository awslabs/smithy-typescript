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

import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;

/**
 * Generates an index to export the service client and each command.
 */
final class IndexGenerator implements Runnable {

    private final TypeScriptSettings settings;
    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;
    private final TypeScriptWriter writer;
    private final Symbol symbol;
    private final FileManifest fileManifest;

    IndexGenerator(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            FileManifest fileManifest
    ) {
        this.settings = settings;
        this.model = model;
        this.service = settings.getService(model);
        this.symbolProvider = symbolProvider;
        this.writer = new TypeScriptWriter("");
        this.fileManifest = fileManifest;
        symbol = symbolProvider.toSymbol(service);
    }

    @Override
    public void run() {
        generateIndex();
    }

    private void generateIndex() {
        writer.write("export * from \"./" + symbol.getName() + "\";");
        TopDownIndex topDownIndex = model.getKnowledge(TopDownIndex.class);
        for (OperationShape operation : topDownIndex.getContainedOperations(service)) {
            writer.write("export * from \"./commands/" + symbolProvider.toSymbol(operation).getName() + "\";");
        }
        fileManifest.writeFile("index.tx", writer.toString());
    }
}
