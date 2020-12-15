package software.amazon.smithy.typescript.codegen;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;

public class CommandGeneratorTest {
    @Test
    public void addsCommandSpecificPlugins() {
        testCommmandCodegen("output-structure.smithy",
                "  resolveMiddleware(\n" +
                "    clientStack: MiddlewareStack<ServiceInputTypes, ServiceOutputTypes>,\n" +
                "    configuration: ExampleClientResolvedConfig,\n" +
                "    options?: __HttpHandlerOptions\n" +
                "  ): Handler<GetFooCommandInput, GetFooCommandOutput> {\n" +
                "    this.middlewareStack.use(getSerdePlugin(configuration, this.serialize, this.deserialize));\n" +
                "\n" +
                "    const stack = clientStack.concat(this.middlewareStack);");
    }

    @Test
    public void writesSerializer() {
        testCommmandCodegen("output-structure.smithy",
                "  private serialize(\n" +
                "    input: GetFooCommandInput,\n" +
                "    context: __SerdeContext\n" +
                "  ): Promise<__HttpRequest> {");
    }

    @Test
    public void writesDeserializer() {
        testCommmandCodegen("output-structure.smithy",
                "  private deserialize(\n" +
                "    output: __HttpResponse,\n" +
                "    context: __SerdeContext\n" +
                "  ): Promise<GetFooCommandOutput> {");
    }

    private void testCommmandCodegen(String file, String expectedType) {
        Model model = Model.assembler()
                .addImport(getClass().getResource(file))
                .assemble()
                .unwrap();
        MockManifest manifest = new MockManifest();
        PluginContext context = PluginContext.builder()
                .model(model)
                .fileManifest(manifest)
                .settings(Node.objectNodeBuilder()
                                  .withMember("service", Node.from("smithy.example#Example"))
                                  .withMember("package", Node.from("example"))
                                  .withMember("packageVersion", Node.from("1.0.0"))
                                  .build())
                .build();

        new TypeScriptCodegenPlugin().execute(context);
        String contents = manifest.getFileString("/src/commands/GetFooCommand.ts").get();

        assertThat(contents, containsString("as __MetadataBearer"));
        assertThat(contents, containsString(expectedType));
    }
}
