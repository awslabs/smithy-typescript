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

import static software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin.Convention.HAS_MIDDLEWARE;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.traits.HttpChecksumRequiredTrait;
import software.amazon.smithy.typescript.codegen.LanguageTarget;
import software.amazon.smithy.typescript.codegen.TypeScriptDependency;
import software.amazon.smithy.typescript.codegen.TypeScriptSettings;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Adds md5 checksum dependencies if needed.
 */
@SmithyInternalApi
public final class AddChecksumRequiredDependency implements TypeScriptIntegration {

    @Override
    public void addConfigInterfaceFields(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer
    ) {
        if (!hasMd5Dependency(model, settings.getService(model))) {
            return;
        }

        writer.addImport("Readable", "Readable", "stream");
        writer.addImport("StreamHasher", "__StreamHasher", "@aws-sdk/types");
        writer.writeDocs("A function that, given a hash constructor and a stream, calculates the \n"
                + "hash of the streamed value.\n"
                + "@internal");
        writer.write("streamHasher?: __StreamHasher<Readable> | __StreamHasher<Blob>;\n");

        writer.addImport("Hash", "__Hash", "@aws-sdk/types");
        writer.addImport("HashConstructor", "__HashConstructor", "@aws-sdk/types");
        writer.writeDocs("A constructor for a class implementing the {@link __Hash} interface \n"
                + "that computes MD5 hashes.\n"
                + "@internal");
        writer.write("md5?: __HashConstructor;\n");
    }

    @Override
    public Map<String, Consumer<TypeScriptWriter>> getRuntimeConfigWriters(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            LanguageTarget target
    ) {
        if (!hasMd5Dependency(model, settings.getService(model))) {
            return Collections.emptyMap();
        }

        switch (target) {
            case NODE:
                return MapUtils.of(
                    "streamHasher", writer -> {
                        writer.addDependency(TypeScriptDependency.STREAM_HASHER_NODE);
                        writer.addImport("fileStreamHasher", "streamHasher",
                                TypeScriptDependency.STREAM_HASHER_NODE.packageName);
                        writer.write("streamHasher,");
                    },
                    "md5", writer -> {
                            writer.addDependency(TypeScriptDependency.AWS_SDK_TYPES);
                            writer.addImport("HashConstructor", "__HashConstructor",
                                    TypeScriptDependency.AWS_SDK_TYPES.packageName);
                            writer.write("md5: Hash.bind(null, \"md5\"),");
                    });
            case BROWSER:
                return MapUtils.of(
                    "streamHasher", writer -> {
                        writer.addDependency(TypeScriptDependency.STREAM_HASHER_BROWSER);
                        writer.addImport("blobHasher", "streamHasher",
                                TypeScriptDependency.STREAM_HASHER_BROWSER.packageName);
                        writer.write("streamHasher,");
                    },
                    "md5", writer -> {
                        writer.addDependency(TypeScriptDependency.MD5_BROWSER);
                        writer.addImport("Md5", "Md5", TypeScriptDependency.MD5_BROWSER.packageName);
                        writer.write("md5: Md5,");
                    });
            default:
                return Collections.emptyMap();
        }
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
            RuntimeClientPlugin.builder()
                        .withConventions(TypeScriptDependency.BODY_CHECKSUM.dependency, "ApplyMd5BodyChecksum",
                                         HAS_MIDDLEWARE)
                        .operationPredicate((m, s, o) -> hasChecksumRequiredTrait(m, s, o))
                        .build()
        );
    }


    // return true if operation shape is decorated with `httpChecksumRequired` trait.
    private static boolean hasChecksumRequiredTrait(Model model, ServiceShape service, OperationShape operation) {
        return operation.hasTrait(HttpChecksumRequiredTrait.class);
    }

    private static boolean hasMd5Dependency(
            Model model,
            ServiceShape service
    ) {
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        Set<OperationShape> operations = topDownIndex.getContainedOperations(service);
        for (OperationShape operation : operations) {
            if (hasChecksumRequiredTrait(model, service, operation)) {
                return true;
            }
        }
        return false;
    }
}
