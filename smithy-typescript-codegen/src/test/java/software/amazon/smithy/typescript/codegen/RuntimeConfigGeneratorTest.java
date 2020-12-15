package software.amazon.smithy.typescript.codegen;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.typescript.codegen.integration.TypeScriptIntegration;

public class RuntimeConfigGeneratorTest {
    @Test
    public void expandsRuntimeConfigFile() {
        Model model = Model.assembler().addImport(getClass().getResource("simple-service.smithy")).assemble().unwrap();
        MockManifest manifest = new MockManifest();

        List<TypeScriptIntegration> integrations = new ArrayList<>();
        integrations.add(new TypeScriptIntegration() {
            @Override
            public Map<String, Consumer<TypeScriptWriter>> getRuntimeConfigWriters(
                    TypeScriptSettings settings,
                    Model model,
                    SymbolProvider symbolProvider,
                    LanguageTarget target
            ) {
                Map<String, Consumer<TypeScriptWriter>> config = new HashMap<>();
                config.put("syn", writer -> {
                    writer.write("syn: 'ack1',");
                });
                config.put("foo", writer -> {
                    writer.write("foo: 'bar',");
                });
                return config;
            }
        });

        integrations.add(new TypeScriptIntegration() {
            @Override
            public byte getOrder() {
                return 1;
            }
            @Override
            public Map<String, Consumer<TypeScriptWriter>> getRuntimeConfigWriters(
                    TypeScriptSettings settings,
                    Model model,
                    SymbolProvider symbolProvider,
                    LanguageTarget target
            ) {
                Map<String, Consumer<TypeScriptWriter>> config = new HashMap<>();
                config.put("syn", writer -> {
                    writer.write("syn: 'ack2',");
                });
                return config;
            }
        });

        TypeScriptSettings settings = TypeScriptSettings.from(model, Node.objectNodeBuilder()
                .withMember("service", Node.from("smithy.example#Example"))
                .withMember("package", Node.from("example"))
                .withMember("packageVersion", Node.from("1.0.0"))
                .build());
        SymbolProvider symbolProvider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        TypeScriptDelegator delegator = new TypeScriptDelegator(
                settings, model, manifest, symbolProvider, integrations);
        RuntimeConfigGenerator generator = new RuntimeConfigGenerator(
                settings, model, symbolProvider, delegator, integrations);
        generator.generate(LanguageTarget.NODE);
        generator.generate(LanguageTarget.BROWSER);
        generator.generate(LanguageTarget.REACT_NATIVE);
        generator.generate(LanguageTarget.SHARED);
        delegator.flushWriters();

        Assertions.assertTrue(manifest.hasFile("src/runtimeConfig.ts"));
        Assertions.assertTrue(manifest.hasFile("src/runtimeConfig.browser.ts"));
        Assertions.assertTrue(manifest.hasFile("src/runtimeConfig.native.ts"));
        Assertions.assertTrue(manifest.hasFile("src/runtimeConfig.shared.ts"));

        // Does the runtimeConfig.shared.ts file expand the template properties properly?
        String runtimeConfigSharedContents = manifest.getFileString("src/runtimeConfig.shared.ts").get();
        assertThat(runtimeConfigSharedContents, containsString("apiVersion: \"1.0.0\","));
        assertThat(runtimeConfigSharedContents, containsString("syn: 'ack2',"));
        assertThat(runtimeConfigSharedContents, containsString("foo: 'bar',"));

        // Does the runtimeConfig.ts file expand the template properties properly?
        String runtimeConfigContents = manifest.getFileString("src/runtimeConfig.ts").get();
        assertThat(runtimeConfigContents, containsString("const { name, version } = require('../package.json')"));
        assertThat(runtimeConfigContents,
                   containsString("import { ClientDefaults } from \"./ExampleClient\";"));
        assertThat(runtimeConfigContents, containsString("syn: 'ack2',"));
        assertThat(runtimeConfigSharedContents, containsString("foo: 'bar',"));

        // Does the runtimeConfig.browser.ts file expand the template properties properly?
        String runtimeConfigBrowserContents = manifest.getFileString("src/runtimeConfig.browser.ts").get();
        assertThat(runtimeConfigContents, containsString("const { name, version } = require('../package.json')"));
        assertThat(runtimeConfigBrowserContents,
                   containsString("import { ClientDefaults } from \"./ExampleClient\";"));
        assertThat(runtimeConfigContents, containsString("syn: 'ack2',"));
        assertThat(runtimeConfigSharedContents, containsString("foo: 'bar',"));

        // Does the runtimeConfig.native.ts file expand the browser template properties properly?
        String runtimeConfigNativeContents = manifest.getFileString("src/runtimeConfig.native.ts").get();
        assertThat(runtimeConfigContents, containsString("const { name, version } = require('../package.json')"));
        assertThat(runtimeConfigNativeContents,
                containsString("import { ClientDefaults } from \"./ExampleClient\";"));
        assertThat(runtimeConfigNativeContents,
                containsString("import { ClientDefaultValues as BrowserDefaults } from \"./runtimeConfig.browser\";"));
        assertThat(runtimeConfigContents, containsString("syn: 'ack2',"));
        assertThat(runtimeConfigSharedContents, containsString("foo: 'bar',"));
    }
}
