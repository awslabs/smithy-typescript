package software.amazon.smithy.typescript.codegen;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.MediaTypeTrait;

public class SymbolProviderTest {
    @Test
    public void createsSymbols() {
        Shape shape = StructureShape.builder().id("com.foo.baz#Hello").build();
        Model model = Model.assembler().addShape(shape).assemble().unwrap();
        SymbolProvider provider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        Symbol symbol = provider.toSymbol(shape);

        assertThat(symbol.getName(), equalTo("Hello"));
        assertThat(symbol.getNamespace(), equalTo("./src/models/models_0"));
        assertThat(symbol.getNamespaceDelimiter(), equalTo("/"));
        assertThat(symbol.getDefinitionFile(), equalTo("./src/models/models_0.ts"));
    }

    @Test
    public void createsSymbolsIntoTargetNamespace() {
        Shape shape1 = StructureShape.builder().id("com.foo#Hello").build();
        Shape shape2 = StructureShape.builder().id("com.foo.baz#Hello").build();
        Model model = Model.assembler().addShapes(shape1, shape2).assemble().unwrap();
        SymbolProvider provider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        Symbol symbol1 = provider.toSymbol(shape1);
        Symbol symbol2 = provider.toSymbol(shape2);
        MockManifest manifest = new MockManifest();
        SymbolVisitor.writeModelIndex(model, provider, manifest);

        assertThat(symbol1.getName(), equalTo("Hello"));
        assertThat(symbol1.getNamespace(), equalTo("./src/models/models_0"));
        assertThat(symbol1.getNamespaceDelimiter(), equalTo("/"));
        assertThat(symbol1.getDefinitionFile(), equalTo("./src/models/models_0.ts"));

        assertThat(symbol2.getName(), equalTo("Hello"));
        assertThat(symbol2.getNamespace(), equalTo("./src/models/models_0"));
        assertThat(symbol2.getNamespaceDelimiter(), equalTo("/"));
        assertThat(symbol2.getDefinitionFile(), equalTo("./src/models/models_0.ts"));
        assertThat(manifest.getFileString("src/models/index.ts").get(),
                containsString("export * from \"./models_0\";"));
    }

    @Test
    public void escapesReservedWords() {
        Shape shape = StructureShape.builder().id("com.foo.baz#Pick").build();
        Model model = Model.assembler().addShape(shape).assemble().unwrap();
        SymbolProvider provider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        Symbol symbol = provider.toSymbol(shape);

        assertThat(symbol.getName(), equalTo("_Pick"));
    }

    @Test
    public void doesNotEscapeBuiltins() {
        MemberShape member = MemberShape.builder().id("foo.bar#Object$a").target("smithy.api#String").build();
        StructureShape struct = StructureShape.builder()
                .id("foo.bar#Object")
                .addMember(member)
                .build();
        Model model = Model.assembler()
                .addShapes(struct, member)
                .assemble()
                .unwrap();

        SymbolProvider provider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        Symbol structSymbol = provider.toSymbol(struct);
        Symbol memberSymbol = provider.toSymbol(member);

        // Normal structure with escaping.
        assertThat(structSymbol.getName(), equalTo("_Object"));
        assertThat(structSymbol.getNamespace(), equalTo("./src/models/models_0"));

        // Reference to built-in type with no escaping.
        assertThat(memberSymbol.getName(), equalTo("string"));
        assertThat(memberSymbol.getNamespace(), equalTo(""));
    }

    @Test
    public void escapesRecursiveSymbols() {
        StructureShape record = StructureShape.builder().id("foo.bar#Record").build();
        MemberShape listMember = MemberShape.builder().id("foo.bar#Records$member").target(record).build();
        ListShape list = ListShape.builder()
                .id("foo.bar#Records")
                .member(listMember)
                .build();
        Model model = Model.assembler()
                .addShapes(list, listMember, record)
                .assemble()
                .unwrap();

        SymbolProvider provider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        Symbol listSymbol = provider.toSymbol(list);

        assertThat(listSymbol.getName(), equalTo("(_Record)[]"));
    }

    @Test
    public void errorStructuresAreMetadataBearers() {
        Model model = Model.assembler()
                .addImport(getClass().getResource("output-structure.smithy"))
                .assemble()
                .unwrap();

        Shape input = model.expectShape(ShapeId.from("smithy.example#GetFooInput"));
        Shape output = model.expectShape(ShapeId.from("smithy.example#GetFooOutput"));
        Shape error = model.expectShape(ShapeId.from("smithy.example#GetFooError"));
        SymbolProvider provider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        Symbol inputSymbol = provider.toSymbol(input);
        Symbol outputSymbol = provider.toSymbol(output);
        Symbol errorSymbol = provider.toSymbol(error);

        // Input and Output does not use MetadataBearer
        assertThat(inputSymbol.getReferences().stream()
                .filter(ref -> ref.getProperty("extends").isPresent())
                .count(), equalTo(0L));
        assertThat(outputSymbol.getReferences().stream()
                 .filter(ref -> ref.getAlias().equals("$MetadataBearer"))
                 .count(), equalTo(0L));

        // Output uses MetadataBearer
        assertThat(errorSymbol.getReferences().stream()
                .filter(ref -> ref.getProperty(SymbolVisitor.IMPLEMENTS_INTERFACE_PROPERTY).isPresent())
                .count(), greaterThan(0L));
        assertThat(errorSymbol.getReferences().stream()
                 .filter(ref -> ref.getAlias().equals("$MetadataBearer"))
                 .count(), greaterThan(0L));
    }

    @Test
    public void createsCommandModules() {
        Model model = Model.assembler()
                .addImport(getClass().getResource("output-structure.smithy"))
                .assemble()
                .unwrap();

        Shape command = model.expectShape(ShapeId.from("smithy.example#GetFoo"));
        SymbolProvider provider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        Symbol commandSymbol = provider.toSymbol(command);

        assertThat(commandSymbol.getName(), equalTo("GetFooCommand"));
        assertThat(commandSymbol.getNamespace(), equalTo("./src/commands/GetFooCommand"));
    }

    @Test
    public void usesLazyJsonStringForJsonMediaType() {
        StringShape jsonString = StringShape.builder().id("foo.bar#jsonString")
                .addTrait(new MediaTypeTrait("application/json")).build();
        MemberShape member = MemberShape.builder().id("foo.bar#test$a").target(jsonString).build();
        StructureShape struct = StructureShape.builder()
                .id("foo.bar#test")
                .addMember(member)
                .build();
        Model model = Model.assembler()
                .addShapes(struct, member, jsonString)
                .assemble()
                .unwrap();

        SymbolProvider provider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        Symbol memberSymbol = provider.toSymbol(member);

        assertThat(memberSymbol.getName(), equalTo("__LazyJsonString | string"));
    }
}
