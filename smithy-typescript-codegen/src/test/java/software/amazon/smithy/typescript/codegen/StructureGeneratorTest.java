package software.amazon.smithy.typescript.codegen;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;

public class StructureGeneratorTest {
    @Test
    public void properlyGeneratesEmptyMessageMemberOfException() {
        testErrorStructureCodegen("error-test-empty.smithy",
                                  "export interface Err extends __SmithyException, $MetadataBearer {\n"
                                  + "  name: \"Err\";\n"
                                  + "  $fault: \"client\";\n"
                                  + "}");
    }

    @Test
    public void properlyGeneratesOptionalMessageMemberOfException() {
        testErrorStructureCodegen("error-test-optional-message.smithy",
                                  "export interface Err extends __SmithyException, $MetadataBearer {\n"
                                  + "  name: \"Err\";\n"
                                  + "  $fault: \"client\";\n"
                                  + "  message?: string;\n"
                                  + "}");
    }

    @Test
    public void properlyGeneratesRequiredMessageMemberOfException() {
        testErrorStructureCodegen("error-test-required-message.smithy",
                                  "export interface Err extends __SmithyException, $MetadataBearer {\n"
                                  + "  name: \"Err\";\n"
                                  + "  $fault: \"client\";\n"
                                  + "  message: string | undefined;\n"
                                  + "}");
    }

    @Test
    public void generatesEmptyRetryableTrait() {
        testErrorStructureCodegen("error-test-retryable.smithy",
                                  "export interface Err extends __SmithyException, $MetadataBearer {\n"
                                  + "  name: \"Err\";\n"
                                  + "  $fault: \"client\";\n"
                                  + "  $retryable: {\n"
                                  + "  };\n"
                                  + "}");
    }

    @Test
    public void generatesRetryableTraitWithThrottling() {
        testErrorStructureCodegen("error-test-retryable-throttling.smithy",
                                  "export interface Err extends __SmithyException, $MetadataBearer {\n"
                                  + "  name: \"Err\";\n"
                                  + "  $fault: \"client\";\n"
                                  + "  $retryable: {\n"
                                  + "    throttling: true,\n"
                                  + "  };\n"
                                  + "}");
    }

    @Test
    public void filtersSensitiveSimpleShape() {
        testStructureCodegen("test-sensitive-simple-shape.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.password && { password:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void skipsFilterForInsensitiveSimpleShape() {
        testStructureCodegen("test-insensitive-simple-shape.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterForStructureWithSensitiveData() {
        testStructureCodegen("test-structure-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      User.filterSensitiveLog(obj.foo)\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterInStructureWithSensitiveData() {
        testStructureCodegen("test-structure-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: User): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.password && { password:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void filtersSensitiveStructure() {
        testStructureCodegen("test-sensitive-structure.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void skipsFilterForStructureWithoutSensitiveData() {
        testStructureCodegen("test-structure-without-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "  })\n");
    }

    @Test
    public void filtersSensitiveMemberPointingToStructure() {
        testStructureCodegen("test-sensitive-member-pointing-to-structure.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.sensitiveFoo && { sensitiveFoo:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterForUnionWithSensitiveData() {
        testStructureCodegen("test-union-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      TestUnion.filterSensitiveLog(obj.foo)\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterInUnionWithSensitiveData() {
        testStructureCodegen("test-union-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: TestUnion): any => {\n"
                                + "    if (obj.bar !== undefined) return {bar:\n"
                                + "      obj.bar\n"
                                + "    };\n"
                                + "    if (obj.sensitiveBar !== undefined) return {sensitiveBar:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    };\n"
                                + "    if (obj.$unknown !== undefined) return {[obj.$unknown[0]]: 'UNKNOWN'};\n"
                                + "  }\n");
    }

    @Test
    public void callsFilterForUnionWithoutSensitiveData() {
        testStructureCodegen("test-union-without-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      TestUnion.filterSensitiveLog(obj.foo)\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterInUnionWithoutSensitiveData() {
        testStructureCodegen("test-union-without-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: TestUnion): any => {\n"
                                + "    if (obj.fooString !== undefined) return {fooString:\n"
                                + "      obj.fooString\n"
                                + "    };\n"
                                + "    if (obj.barString !== undefined) return {barString:\n"
                                + "      obj.barString\n"
                                + "    };\n"
                                + "    if (obj.$unknown !== undefined) return {[obj.$unknown[0]]: 'UNKNOWN'};\n"
                                + "  }\n");
    }

    @Test
    public void callsFilterInUnionWithStructure() {
        testStructureCodegen("test-union-with-structure.smithy",
                                "  export const filterSensitiveLog = (obj: TestUnion): any => {\n"
                                + "    if (obj.fooUser !== undefined) return {fooUser:\n"
                                + "      User.filterSensitiveLog(obj.fooUser)\n"
                                + "    };\n"
                                + "    if (obj.$unknown !== undefined) return {[obj.$unknown[0]]: 'UNKNOWN'};\n"
                                + "  }\n");
    }

    @Test
    public void callsFilterInUnionWithList() {
        testStructureCodegen("test-union-with-list.smithy",
                                "  export const filterSensitiveLog = (obj: TestUnion): any => {\n"
                                + "    if (obj.list !== undefined) return {list:\n"
                                + "      obj.list\n"
                                + "    };\n"
                                + "    if (obj.$unknown !== undefined) return {[obj.$unknown[0]]: 'UNKNOWN'};\n"
                                + "  }\n");
    }

    @Test
    public void callsFilterInUnionWithMap() {
        testStructureCodegen("test-union-with-map.smithy",
                                "  export const filterSensitiveLog = (obj: TestUnion): any => {\n"
                                + "    if (obj.map !== undefined) return {map:\n"
                                + "      obj.map\n"
                                + "    };\n"
                                + "    if (obj.$unknown !== undefined) return {[obj.$unknown[0]]: 'UNKNOWN'};\n"
                                + "  }\n");
    }

    @Test
    public void filtersStreamingUnion() {
        testStructureCodegen("test-streaming-union.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      'STREAMING_CONTENT'\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void filtersSensitiveUnion() {
        testStructureCodegen("test-sensitive-union.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void filtersSensitiveMemberPointingToUnion() {
        testStructureCodegen("test-sensitive-member-pointing-to-union.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.sensitiveFoo && { sensitiveFoo:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterForListWithStructureWithSensitiveData() {
        testStructureCodegen("test-list-with-structure-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      obj.foo.map(\n"
                                + "        item =>\n"
                                + "        User.filterSensitiveLog(item)\n"
                                + "      )\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterInListWithStructureWithSensitiveData() {
        testStructureCodegen("test-list-with-structure-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: User): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.password && { password:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterForListWithUnionWithSensitiveData() {
        testStructureCodegen("test-list-with-union-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      obj.foo.map(\n"
                                + "        item =>\n"
                                + "        TestUnion.filterSensitiveLog(item)\n"
                                + "      )\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterInListWithUnionWithSensitiveData() {
        testStructureCodegen("test-list-with-union-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: TestUnion): any => {\n"
                                + "    if (obj.bar !== undefined) return {bar:\n"
                                + "      obj.bar\n"
                                + "    };\n"
                                + "    if (obj.sensitiveBar !== undefined) return {sensitiveBar:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    };\n"
                                + "    if (obj.$unknown !== undefined) return {[obj.$unknown[0]]: 'UNKNOWN'};\n"
                                + "  }\n");
    }

    @Test
    public void callsFilterForListWithSensitiveMember() {
        testStructureCodegen("test-list-with-sensitive-member.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void filtersSensitiveList() {
        testStructureCodegen("test-sensitive-list.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void skipsFilterForInsensitiveList() {
        testStructureCodegen("test-insensitive-list.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "  })\n");
    }

    @Test
    public void filtersSensitiveMemberPointingToList() {
        testStructureCodegen("test-sensitive-member-pointing-to-list.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.sensitiveFoo && { sensitiveFoo:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterForMapWithStructureWithSensitiveData() {
        testStructureCodegen("test-map-with-structure-with-sensitive-data.smithy",
                            "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                            + "    ...obj,\n"
                            + "    ...(obj.foo && { foo:\n"
                            + "      Object.entries(obj.foo).reduce((acc: any, [key, value]: [string, User]) => ({\n"
                            + "        ...acc,\n"
                            + "        [key]:\n"
                            + "          User.filterSensitiveLog(value)\n"
                            + "        ,\n"
                            + "      }), {})\n"
                            + "    }),\n"
                            + "  })\n");
    }

    @Test
    public void callsFilterInMapWithStructureWithSensitiveData() {
        testStructureCodegen("test-map-with-structure-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: User): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.password && { password:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void callsFilterForMapWithUnionWithSensitiveData() {
        testStructureCodegen("test-map-with-union-with-sensitive-data.smithy",
                        "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                        + "    ...obj,\n"
                        + "    ...(obj.foo && { foo:\n"
                        + "      Object.entries(obj.foo).reduce((acc: any, [key, value]: [string, TestUnion]) => ({\n"
                        + "        ...acc,\n"
                        + "        [key]:\n"
                        + "          TestUnion.filterSensitiveLog(value)\n"
                        + "        ,\n"
                        + "      }), {})\n"
                        + "    }),\n"
                        + "  })\n");
    }

    @Test
    public void callsFilterInMapWithUnionWithSensitiveData() {
        testStructureCodegen("test-map-with-union-with-sensitive-data.smithy",
                                "  export const filterSensitiveLog = (obj: TestUnion): any => {\n"
                                + "    if (obj.bar !== undefined) return {bar:\n"
                                + "      obj.bar\n"
                                + "    };\n"
                                + "    if (obj.sensitiveBar !== undefined) return {sensitiveBar:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    };\n"
                                + "    if (obj.$unknown !== undefined) return {[obj.$unknown[0]]: 'UNKNOWN'};\n"
                                + "  }\n");
    }


    @Test
    public void callsFilterForMapWithSensitiveMember() {
        testStructureCodegen("test-map-with-sensitive-member.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "    ...(obj.foo && { foo:\n"
                                + "      SENSITIVE_STRING\n"
                                + "    }),\n"
                                + "  })\n");
    }

    @Test
    public void filtersSensitiveMap() {
        testStructureCodegen("test-sensitive-map.smithy",
                            "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                            + "    ...obj,\n"
                            + "    ...(obj.foo && { foo:\n"
                            + "      SENSITIVE_STRING\n"
                            + "    }),\n"
                            + "  })\n");
    }

    @Test
    public void filtersSensitiveMemberPointingToMap() {
        testStructureCodegen("test-sensitive-member-pointing-to-map.smithy",
                            "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                            + "    ...obj,\n"
                            + "    ...(obj.sensitiveFoo && { sensitiveFoo:\n"
                            + "      SENSITIVE_STRING\n"
                            + "    }),\n"
                            + "  })\n");
    }

    @Test
    public void skipsFilterForInsensitiveMap() {
        testStructureCodegen("test-insensitive-map.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "  })\n");
    }

    @Test
    public void skipsFilterOnEncounteringRecursiveShapes() {
        testStructureCodegen("test-recursive-shapes.smithy",
                                "  export const filterSensitiveLog = (obj: GetFooInput): any => ({\n"
                                + "    ...obj,\n"
                                + "  })\n");
    }

    private String testStructureCodegen(String file, String expectedType) {
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
        String contents = manifest.getFileString("/src/models/models_0.ts").get();

        assertThat(contents, containsString(expectedType));
        return contents;
    }

    private void testErrorStructureCodegen(String file, String expectedType) {
        String contents = testStructureCodegen(file, expectedType);

        assertThat(contents, containsString("as __SmithyException"));
        assertThat(contents, containsString("namespace Err {"));
    }

    @Test
    public void generatesNonErrorStructures() {
        StructureShape struct = createNonErrorStructure();
        ModelAssembler assembler = Model.assembler().addShape(struct);
        struct.getAllMembers().values().forEach(assembler::addShape);
        Model model = assembler.assemble().unwrap();

        TypeScriptWriter writer = new TypeScriptWriter("./foo");
        new StructureGenerator(model, TypeScriptCodegenPlugin.createSymbolProvider(model), writer, struct).run();
        String output = writer.toString();

        assertThat(output, containsString("export interface Bar {"));
        assertThat(output, containsString("foo?: string;"));
        assertThat(output, containsString("export namespace Bar {"));
    }

    private StructureShape createNonErrorStructure() {
        return StructureShape.builder()
                .id("com.foo#Bar")
                .addMember(MemberShape.builder().id("com.foo#Bar$foo").target("smithy.api#String").build())
                .build();
    }

    @Test
    public void generatesNonErrorStructuresThatExtendOtherInterfaces() {
        StructureShape struct = createNonErrorStructure();
        ModelAssembler assembler = Model.assembler().addShape(struct);
        struct.getAllMembers().values().forEach(assembler::addShape);
        OperationShape operation = OperationShape.builder().id("com.foo#Operation").output(struct).build();
        assembler.addShape(operation);
        Model model = assembler.assemble().unwrap();

        TypeScriptWriter writer = new TypeScriptWriter("./foo");
        new StructureGenerator(model, TypeScriptCodegenPlugin.createSymbolProvider(model), writer, struct).run();
        String output = writer.toString();

        assertThat(output, containsString("export interface Bar {"));
    }
}
