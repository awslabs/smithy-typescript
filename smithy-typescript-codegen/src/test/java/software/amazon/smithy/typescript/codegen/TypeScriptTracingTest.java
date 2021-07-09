package software.amazon.smithy.typescript.codegen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.codegen.core.trace.ShapeLink;
import software.amazon.smithy.codegen.core.trace.TraceFile;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.traits.EnumDefinition;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.utils.ListUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;


public class TypeScriptTracingTest {
    @Test
    public void includesAllExportStatementsInCommands() {
        String modelFile = "output-structure.smithy";
        String tsFileName = "/commands/GetFooCommand.ts";
        String baseId = "software.amazon.awssdk.services.commands.GetFooCommand.";
        String shapeId = "smithy.example#GetFoo";

        testFromExports(modelFile, tsFileName, baseId, ListUtils.of(shapeId));
    }

    @Test
    public void includesAllExportStatementsInModels() {
        String modelFile = "output-structure.smithy";
        String tsFileName = "/models/models_0.ts";
        String baseId = "software.amazon.awssdk.services.models.models_0.";
        List<String> shapeIds = Arrays.asList("smithy.example#GetFooInput", "smithy.example#GetFooOutput",
                "smithy.example#GetFooError");

        testFromExports(modelFile, tsFileName, baseId, shapeIds);
    }

    @Test
    public void includesAllExportStatementsInService() {
        String modelFile = "output-structure.smithy";
        String tsFileName = "/Example.ts";
        String baseId = "software.amazon.awssdk.services.";
        List<String> shapeIds = Collections.singletonList("smithy.example#Example");

        testFromExports(modelFile, tsFileName, baseId, shapeIds);
    }

    @Test
    public void includesAllExportStatementsInServiceClient() {
        String modelFile = "output-structure.smithy";
        String tsFileName = "/ExampleClient.ts";
        String baseId = "software.amazon.awssdk.services.ExampleClient.";
        List<String> shapeIds = Collections.singletonList("smithy.example#Example");

        testFromExports(modelFile, tsFileName, baseId, shapeIds);
    }

    @Test
    public void addsOperationShapeLinks() {
        TraceFile traceFile = getGeneratedTraceFile(getModel("output-structure.smithy"));
        List<ShapeLink> shapeLinks = traceFile.getShapes().get(ShapeId.from("smithy.example#GetFoo"));
        List<String> shapeLinkIds = shapeLinks.stream().map(ShapeLink::getId).collect(Collectors.toList());
        String serviceBaseId = "software.amazon.awssdk.services.";
        String commandBaseId = serviceBaseId + "commands.GetFooCommand";
        assertThat(shapeLinkIds, containsInAnyOrder(commandBaseId + ".GetFooCommand",
                commandBaseId + ".GetFooCommandInput",
                commandBaseId + ".GetFooCommandOutput",
                commandBaseId + ".GetFooCommand#constructor",
                commandBaseId + ".GetFooCommand#serialize",
                commandBaseId + ".GetFooCommand#deserialize",
                commandBaseId + ".GetFooCommand#resolveMiddleware",
                serviceBaseId + "Example#getFoo"));
    }

    @Test
    public void addsUnionShapeLinks() {
        TraceFile traceFile = getGeneratedTraceFile(getModel("simple-service-with-union.smithy"));
        List<ShapeLink> shapeLinks = traceFile.getShapes().get(ShapeId.from("smithy.example#MyUnion"));
        List<String> shapeLinkIds = shapeLinks.stream().map(ShapeLink::getId).collect(Collectors.toList());
        String unionBaseId = "software.amazon.awssdk.services.models.models_0.MyUnion";
        assertThat(shapeLinkIds, containsInAnyOrder(unionBaseId, unionBaseId + ".Visitor",
                unionBaseId + ".Visitor$stringA",
                unionBaseId + ".Visitor$stringB",
                unionBaseId + ".Visitor$value",
                unionBaseId + "#visit"));
    }

    @Test
    public void addsStructureShapeLinks() {
        TraceFile traceFile = getGeneratedTraceFile(getModel("output-structure.smithy"));
        List<ShapeLink> shapeLinks = traceFile.getShapes().get(ShapeId.from("smithy.example#GetFooInput"));
        List<String> shapeLinkIds = shapeLinks.stream().map(ShapeLink::getId).collect(Collectors.toList());
        String structureBaseId = "software.amazon.awssdk.services.models.models_0.GetFooInput";
        assertThat(shapeLinkIds, containsInAnyOrder(structureBaseId, structureBaseId + "#isa",
                structureBaseId + "#filterSensitiveLog"));
    }

    @Test
    public void addsEnumShapeLinks() {
        EnumTrait trait = EnumTrait.builder()
                .addEnum(EnumDefinition.builder().value("FOO").name("FOO").build())
                .addEnum(EnumDefinition.builder().value("BAR").name("BAR").build())
                .build();
        StringShape stringShape = StringShape.builder().id("smithy.example#Baz").addTrait(trait).build();
        ServiceShape serviceShape = ServiceShape.builder().id("smithy.example#Example")
                .addResource("smithy.example#Baz")
                .version("1.0")
                .build();
        Model model = Model.builder().addShape(stringShape).addShape(serviceShape).build();
        TraceFile traceFile = getGeneratedTraceFile(model);

        List<ShapeLink> shapeLinks = traceFile.getShapes().get(ShapeId.from("smithy.example#Baz"));
        List<String> shapeLinkIds = shapeLinks.stream().map(ShapeLink::getId).collect(Collectors.toList());

        String structureBaseId = "software.amazon.awssdk.services.models.index.GetFooInput";

        assertThat(shapeLinkIds, contains("software.amazon.awssdk.services.models.models_0.Baz"));
    }

    @Test
    public void addsServiceShapeLinks() {
        TraceFile traceFile = getGeneratedTraceFile(getModel("simple-service.smithy"));
        List<ShapeLink> shapeLinks = traceFile.getShapes().get(ShapeId.from("smithy.example#Example"));
        List<String> shapeLinkIds = shapeLinks.stream().map(ShapeLink::getId).collect(Collectors.toList());
        String serviceBaseId = "software.amazon.awssdk.services.Example";
        String serviceClientBaseId = serviceBaseId + "Client.";
        assertThat(shapeLinkIds, containsInAnyOrder(serviceClientBaseId + "ServiceInputTypes",
                serviceClientBaseId + "ServiceOutputTypes",
                serviceClientBaseId + "ClientDefaults",
                serviceClientBaseId + "ExampleClientConfig",
                serviceClientBaseId + "ExampleClientResolvedConfig",
                serviceClientBaseId + "ExampleClient",
                serviceClientBaseId + "ExampleClient$config",
                serviceClientBaseId + "ExampleClient#constructor",
                serviceClientBaseId + "ExampleClient#destroy",
                serviceBaseId));
    }

    @Test
    public void addsUnionMemberShapeLinks() {
        TraceFile traceFile = getGeneratedTraceFile(getModel("simple-service-with-union.smithy"));
        List<ShapeLink> stringShapeLinks = traceFile.getShapes().get(ShapeId.from("smithy.example#MyUnion$stringA"));
        List<ShapeLink> valueShapeLinks = traceFile.getShapes().get(ShapeId.from("smithy.example#MyUnion$value"));

        String stringShapeId = stringShapeLinks.get(0).getId();
        String valueShapeId = valueShapeLinks.get(0).getId();

        String unionBaseId = "software.amazon.awssdk.services.models.models_0.MyUnion.";
        assertThat(stringShapeId, equalTo(unionBaseId + "StringAMember"));
        assertThat(valueShapeId, equalTo(unionBaseId + "ValueMember"));
    }

    @Test
    public void addsNonUnionMemberShapeLinks() {
        TraceFile traceFile = getGeneratedTraceFile(getModel("test-insensitive-simple-shape.smithy"));
        String firstNameShapeId = traceFile.getShapes()
                .get(ShapeId.from("smithy.example#GetFooInput$firstname"))
                .get(0)
                .getId();
        String lastNameShapeId = traceFile.getShapes()
                .get(ShapeId.from("smithy.example#GetFooInput$lastname"))
                .get(0)
                .getId();

        String memberBaseId = "software.amazon.awssdk.services.models.models_0.GetFooInput$";
        assertThat(firstNameShapeId, equalTo(memberBaseId + "firstname"));
        assertThat(lastNameShapeId, equalTo(memberBaseId + "lastname"));
    }

    private TraceFile getGeneratedTraceFile(Model model) {
        MockManifest manifest = mockCodegen(model);

        // Get generated trace file as string and convert to node.
        String contents = manifest.getFileString("/example.trace.json").get();
        ObjectNode node = Node.parse(contents).expectObjectNode();

        return TraceFile.fromNode(node);
    }

    private MockManifest mockCodegen(Model model) {
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
        return manifest;
    }

    private Model getModel(String file) {
        return Model.assembler()
                .addImport(getClass().getResource(file))
                .assemble()
                .unwrap();
    }

    private void testFromExports(String modelFileName, String tsFileName, String baseId, List<String> shapeIds) {
        Model model = getModel(modelFileName);
        MockManifest manifest = mockCodegen(model);
        TraceFile traceFile = getGeneratedTraceFile(model);

        String contents = manifest.getFileString(tsFileName).get();
        List<String> exportNames = new ArrayList<>();
        // Split by windows or unix newlines.
        String[] lines = contents.split("\\r?\\n");

        for (String line : lines) {
            if (line.startsWith("export") && !(line.startsWith("export namespace"))) {
                String[] words = line.split(" ");
                if (words.length > 2) {
                    exportNames.add(words[2]);
                }
            }
        }

        // Expected shapeLinkIds.
        List<String> expectedShapeLinkIds = exportNames.stream()
                .distinct()
                .map(name -> baseId + name)
                .collect(Collectors.toList());

        // Getting shapeLinkIds in TraceFile, only including shapeLinkIds in the GetFooCommand file.
        List<ShapeLink> shapeLinks = new ArrayList<>();
        for (String shapeId : shapeIds) {
            shapeLinks.addAll(traceFile.getShapes().get(ShapeId.from(shapeId)));
        }

        List<String> shapeLinkIds = shapeLinks.stream()
                .filter(shapeLink -> shapeLink.getFile().get().equals("." + tsFileName))
                .map(ShapeLink::getId)
                .filter(id -> !id.contains("#"))
                .filter(id -> !id.contains("$"))
                .collect(Collectors.toList());

        assertThat(expectedShapeLinkIds, containsInAnyOrder(shapeLinkIds.toArray()));
    }

}
