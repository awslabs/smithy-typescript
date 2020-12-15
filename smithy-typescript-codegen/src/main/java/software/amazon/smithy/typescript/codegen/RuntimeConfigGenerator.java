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
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import software.amazon.smithy.build.SmithyBuildException;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.typescript.codegen.integration.TypeScriptIntegration;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates runtime configuration files, files that are used to
 * supply different default values based on the targeted language
 * environment of the SDK (e.g., Node vs Browser).
 */
final class RuntimeConfigGenerator {

    private final TypeScriptSettings settings;
    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;
    private final TypeScriptDelegator delegator;
    private final List<TypeScriptIntegration> integrations;
    private final Map<String, Consumer<TypeScriptWriter>> nodeRuntimeConfigDefaults = MapUtils.of(
            "requestHandler", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_NODE_HTTP_HANDLER);
                writer.addImport("NodeHttpHandler", "NodeHttpHandler",
                        TypeScriptDependency.AWS_SDK_NODE_HTTP_HANDLER.packageName);
                writer.write("requestHandler: new NodeHttpHandler(),");
            },
            "sha256", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_HASH_NODE);
                writer.addImport("Hash", "Hash",
                        TypeScriptDependency.AWS_SDK_HASH_NODE.packageName);
                writer.write("sha256: Hash.bind(null, \"sha256\"),");
            },
            "urlParser", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_URL_PARSER_NODE);
                writer.addImport("parseUrl", "parseUrl",
                        TypeScriptDependency.AWS_SDK_URL_PARSER_NODE.packageName);
                writer.write("urlParser: parseUrl,");
            },
            "bodyLengthChecker", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_BODY_LENGTH_NODE);
                writer.addImport("calculateBodyLength", "calculateBodyLength",
                        TypeScriptDependency.AWS_SDK_UTIL_BODY_LENGTH_NODE.packageName);
                writer.write("bodyLengthChecker: calculateBodyLength,");
            },
            "streamCollector", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_NODE_HTTP_HANDLER);
                writer.addImport("streamCollector", "streamCollector",
                        TypeScriptDependency.AWS_SDK_NODE_HTTP_HANDLER.packageName);
                writer.write("streamCollector,");
            },
            "base64Decoder", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_BASE64_NODE);
                writer.addImport("fromBase64", "fromBase64",
                        TypeScriptDependency.AWS_SDK_UTIL_BASE64_NODE.packageName);
                writer.write("base64Decoder: fromBase64,");
            },
            "base64Encoder", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_BASE64_NODE);
                writer.addImport("toBase64", "toBase64",
                        TypeScriptDependency.AWS_SDK_UTIL_BASE64_NODE.packageName);
                writer.write("base64Encoder: toBase64,");
            },
            "utf8Decoder", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_UTF8_NODE);
                writer.addImport("fromUtf8", "fromUtf8",
                        TypeScriptDependency.AWS_SDK_UTIL_UTF8_NODE.packageName);
                writer.write("utf8Decoder: fromUtf8,");
            },
            "utf8Encoder", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_UTF8_NODE);
                writer.addImport("toUtf8", "toUtf8",
                        TypeScriptDependency.AWS_SDK_UTIL_UTF8_NODE.packageName);
                writer.write("utf8Encoder: toUtf8,");
            },
            "defaultUserAgent", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_USER_AGENT_NODE);
                writer.addImport("defaultUserAgent", "defaultUserAgent",
                        TypeScriptDependency.AWS_SDK_UTIL_USER_AGENT_NODE.packageName);
                // Cannot use import for package.json as it's not under TS rootDir.
                // require('../package.json') is added in template instead.
                writer.write("defaultUserAgent: defaultUserAgent(name, version),");
            }
    );
    private final Map<String, Consumer<TypeScriptWriter>> browserRuntimeConfigDefaults = MapUtils.of(
            "requestHandler", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_FETCH_HTTP_HANDLER);
                writer.addImport("FetchHttpHandler", "FetchHttpHandler",
                        TypeScriptDependency.AWS_SDK_FETCH_HTTP_HANDLER.packageName);
                writer.write("requestHandler: new FetchHttpHandler(),");
            },
            "sha256", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_CRYPTO_SHA256_BROWSER);
                writer.addImport("Sha256", "Sha256",
                        TypeScriptDependency.AWS_CRYPTO_SHA256_BROWSER.packageName);
                writer.write("sha256: Sha256,");
            },
            "urlParser", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_URL_PARSER_BROWSER);
                writer.addImport("parseUrl", "parseUrl",
                        TypeScriptDependency.AWS_SDK_URL_PARSER_BROWSER.packageName);
                writer.write("urlParser: parseUrl,");
            },
            "bodyLengthChecker", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_BODY_LENGTH_BROWSER);
                writer.addImport("calculateBodyLength", "calculateBodyLength",
                        TypeScriptDependency.AWS_SDK_UTIL_BODY_LENGTH_BROWSER.packageName);
                writer.write("bodyLengthChecker: calculateBodyLength,");
            },
            "streamCollector", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_FETCH_HTTP_HANDLER);
                writer.addImport("streamCollector", "streamCollector",
                        TypeScriptDependency.AWS_SDK_FETCH_HTTP_HANDLER.packageName);
                writer.write("streamCollector,");
            },
            "base64Decoder", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_BASE64_BROWSER);
                writer.addImport("fromBase64", "fromBase64",
                        TypeScriptDependency.AWS_SDK_UTIL_BASE64_BROWSER.packageName);
                writer.write("base64Decoder: fromBase64,");
            },
            "base64Encoder", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_BASE64_BROWSER);
                writer.addImport("toBase64", "toBase64",
                        TypeScriptDependency.AWS_SDK_UTIL_BASE64_BROWSER.packageName);
                writer.write("base64Encoder: toBase64,");
            },
            "utf8Decoder", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_UTF8_BROWSER);
                writer.addImport("fromUtf8", "fromUtf8",
                        TypeScriptDependency.AWS_SDK_UTIL_UTF8_BROWSER.packageName);
                writer.write("utf8Decoder: fromUtf8,");
            },
            "utf8Encoder", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_UTF8_BROWSER);
                writer.addImport("toUtf8", "toUtf8",
                        TypeScriptDependency.AWS_SDK_UTIL_UTF8_BROWSER.packageName);
                writer.write("utf8Encoder: toUtf8,");
            },
            "defaultUserAgent", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_UTIL_USER_AGENT_BROWSER);
                writer.addImport("defaultUserAgent", "defaultUserAgent",
                        TypeScriptDependency.AWS_SDK_UTIL_USER_AGENT_BROWSER.packageName);
                // Cannot use import for package.json as it's not under TS rootDir.
                // require('../package.json') is added in template instead.
                writer.write("defaultUserAgent: defaultUserAgent(name, version),");
            }
    );
    private final Map<String, Consumer<TypeScriptWriter>> reactNativeRuntimeConfigDefaults = MapUtils.of(
            "sha256", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_CRYPTO_SHA256_JS);
                writer.addImport("Sha256", "Sha256",
                        TypeScriptDependency.AWS_CRYPTO_SHA256_JS.packageName);
                writer.write("sha256: Sha256,");
            },
            "urlParser", writer -> {
                writer.addDependency(TypeScriptDependency.AWS_SDK_URL_PARSER_NODE);
                writer.addImport("parseUrl", "parseUrl",
                        TypeScriptDependency.AWS_SDK_URL_PARSER_NODE.packageName);
                writer.write("urlParser: parseUrl,");
            },
            "defaultUserAgent", writer -> {
                // Cannot use import for package.json as it's not under TS rootDir.
                // require('../package.json') is added in template instead.
                writer.write("defaultUserAgent: "
                    + "`aws-sdk-js-v3-react-native-$${name}/$${version}`,");
            }
    );
    private final Map<String, Consumer<TypeScriptWriter>> sharedRuntimeConfigDefaults = MapUtils.of(
            "disableHostPrefix", writer -> {
                writer.write("disableHostPrefix: false,");
            }
    );

    RuntimeConfigGenerator(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TypeScriptDelegator delegator,
            List<TypeScriptIntegration> integrations
    ) {
        this.settings = settings;
        this.model = model;
        this.service = settings.getService(model);
        this.symbolProvider = symbolProvider;
        this.delegator = delegator;
        this.integrations = integrations;
    }

    void generate(LanguageTarget target) {
        String template = TypeScriptUtils.loadResourceAsString(target.getTemplateFileName());
        String contents = template
                .replace("${clientModuleName}", symbolProvider.toSymbol(service).getNamespace().replace("src/", ""))
                .replace("${apiVersion}", service.getVersion())
                .replace("$", "$$") // sanitize template place holders.
                .replace("$${customizations}", "${L@customizations}");

        delegator.useFileWriter(target.getTargetFilename(), writer -> {
            // Inject customizations into the ~template.
            writer.onSection("customizations", original -> {
                writer.indent();
                // Start with defaults, use a TreeMap for keeping entries sorted.
                Map<String, Consumer<TypeScriptWriter>> configs =
                        new TreeMap<>(getDefaultRuntimeConfigs(target));
                // Add any integration supplied runtime config writers.
                for (TypeScriptIntegration integration : integrations) {
                    configs.putAll(integration.getRuntimeConfigWriters(settings, model, symbolProvider, target));
                }
                configs.values().forEach(value -> value.accept(writer));
                writer.dedent();
            });
            writer.write(contents, "");
        });
    }

    private Map<String, Consumer<TypeScriptWriter>> getDefaultRuntimeConfigs(LanguageTarget target) {
        switch (target) {
            case NODE:
                return nodeRuntimeConfigDefaults;
            case BROWSER:
                return browserRuntimeConfigDefaults;
            case REACT_NATIVE:
                return reactNativeRuntimeConfigDefaults;
            case SHARED:
                return sharedRuntimeConfigDefaults;
            default:
                throw new SmithyBuildException("Unknown target: " + target);
        }
    }
}
