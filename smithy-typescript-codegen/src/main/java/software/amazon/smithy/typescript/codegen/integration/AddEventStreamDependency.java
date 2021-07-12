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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.typescript.codegen.LanguageTarget;
import software.amazon.smithy.typescript.codegen.TypeScriptDependency;
import software.amazon.smithy.typescript.codegen.TypeScriptSettings;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Adds event streams if needed.
 */
@SmithyInternalApi
public final class AddEventStreamDependency implements TypeScriptIntegration {

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .withConventions(TypeScriptDependency.AWS_SDK_EVENTSTREAM_SERDE_CONFIG_RESOLVER.dependency,
                                "EventStreamSerde", RuntimeClientPlugin.Convention.HAS_CONFIG)
                        .servicePredicate(AddEventStreamDependency::hasEventStream)
                        .build()
        );
    }

    @Override
    public void addConfigInterfaceFields(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer
    ) {
        if (!hasEventStream(model, settings.getService(model))) {
            return;
        }

        writer.addDependency(TypeScriptDependency.AWS_SDK_EVENTSTREAM_SERDE_CONFIG_RESOLVER);
        writer.addImport("EventStreamSerdeProvider", "__EventStreamSerdeProvider",
                TypeScriptDependency.AWS_SDK_TYPES.packageName);
        writer.writeDocs("The function that provides necessary utilities for generating and parsing event stream");
        writer.write("eventStreamSerdeProvider?: __EventStreamSerdeProvider;\n");
    }

    @Override
    public Map<String, Consumer<TypeScriptWriter>> getRuntimeConfigWriters(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            LanguageTarget target
    ) {
        if (!hasEventStream(model, settings.getService(model))) {
            return Collections.emptyMap();
        }
        switch (target) {
            case NODE:
                return MapUtils.of("eventStreamSerdeProvider", writer -> {
                    writer.addDependency(TypeScriptDependency.AWS_SDK_EVENTSTREAM_SERDE_NODE);
                    writer.addImport("eventStreamSerdeProvider", "eventStreamSerdeProvider",
                            TypeScriptDependency.AWS_SDK_EVENTSTREAM_SERDE_NODE.packageName);
                    writer.write("eventStreamSerdeProvider");
                });
            case BROWSER:
                return MapUtils.of("eventStreamSerdeProvider", writer -> {
                    writer.addDependency(TypeScriptDependency.AWS_SDK_EVENTSTREAM_SERDE_BROWSER);
                    writer.addImport("eventStreamSerdeProvider", "eventStreamSerdeProvider",
                            TypeScriptDependency.AWS_SDK_EVENTSTREAM_SERDE_BROWSER.packageName);
                    writer.write("eventStreamSerdeProvider");
                });
            default:
                return Collections.emptyMap();
        }
    }

    private static boolean hasEventStream(
            Model model,
            ServiceShape service
    ) {
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        Set<OperationShape> operations = topDownIndex.getContainedOperations(service);
        EventStreamIndex eventStreamIndex = EventStreamIndex.of(model);
        for (OperationShape operation : operations) {
            if (eventStreamIndex.getInputInfo(operation).isPresent()
                    || eventStreamIndex.getOutputInfo(operation).isPresent()
            ) {
                return true;
            }
        }
        return false;
    }
}
