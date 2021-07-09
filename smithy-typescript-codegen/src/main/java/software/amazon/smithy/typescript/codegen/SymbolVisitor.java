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

import static java.lang.String.format;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.ReservedWordSymbolProvider;
import software.amazon.smithy.codegen.core.ReservedWords;
import software.amazon.smithy.codegen.core.ReservedWordsBuilder;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.shapes.BigDecimalShape;
import software.amazon.smithy.model.shapes.BigIntegerShape;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ByteShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.LongShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.utils.StringUtils;

/**
 * This class is responsible for type mapping and file/identifier formatting.
 *
 * <p>Reserved words for TypeScript are automatically escaped so that they are
 * prefixed with "_". See "reserved-words.txt" for the list of words.
 */
@SmithyInternalApi
final class SymbolVisitor implements SymbolProvider, ShapeVisitor<Symbol> {

    static final String IMPLEMENTS_INTERFACE_PROPERTY = "implementsInterface";
    private static final Logger LOGGER = Logger.getLogger(SymbolVisitor.class.getName());

    private final Model model;
    private final TypeScriptSettings settings;
    private final ReservedWordSymbolProvider.Escaper escaper;
    private final Set<StructureShape> errorShapes = new HashSet<>();
    private final ModuleNameDelegator moduleNameDelegator;

    SymbolVisitor(Model model, TypeScriptSettings settings) {
        this(model, settings, ModuleNameDelegator.DEFAULT_CHUNK_SIZE);
    }

    SymbolVisitor(Model model, TypeScriptSettings settings, int shapeChunkSize) {
        this.model = model;
        this.settings = settings;

        // Load reserved words from a new-line delimited file.
        ReservedWords reservedWords = new ReservedWordsBuilder()
                .loadWords(TypeScriptCodegenPlugin.class.getResource("reserved-words.txt"))
                .build();

        escaper = ReservedWordSymbolProvider.builder()
                .nameReservedWords(reservedWords)
                // Only escape words when the symbol has a definition file to
                // prevent escaping intentional references to built-in types.
                .escapePredicate((shape, symbol) -> !StringUtils.isEmpty(symbol.getDefinitionFile()))
                .buildEscaper();

        // Get each structure that's used an error.
        OperationIndex operationIndex = OperationIndex.of(model);
        model.shapes(OperationShape.class).forEach(operationShape -> {
            errorShapes.addAll(operationIndex.getErrors(operationShape));
        });

        moduleNameDelegator = new ModuleNameDelegator(shapeChunkSize);
    }

    static void writeModelIndex(Model model, SymbolProvider symbolProvider, FileManifest fileManifest) {
        ModuleNameDelegator.writeModelIndex(model, symbolProvider, fileManifest);
    }

    @Override
    public Symbol toSymbol(Shape shape) {
        Symbol symbol = shape.accept(this);
        LOGGER.fine(() -> "Creating symbol from " + shape + ": " + symbol);
        return escaper.escapeSymbol(shape, symbol);
    }

    @Override
    public String toMemberName(MemberShape shape) {
        return escaper.escapeMemberName(shape.getMemberName());
    }

    @Override
    public Symbol blobShape(BlobShape shape) {
        if (shape.hasTrait(StreamingTrait.class)) {
            // Note: `Readable` needs an import and a dependency.
            return createSymbolBuilder(shape, "Readable | ReadableStream | Blob", null)
                    .addReference(Symbol.builder().name("Readable").namespace("stream", "/").build())
                    .build();
        }

        return createSymbolBuilder(shape, "Uint8Array").build();
    }

    @Override
    public Symbol booleanShape(BooleanShape shape) {
        return createSymbolBuilder(shape, "boolean").build();
    }

    @Override
    public Symbol listShape(ListShape shape) {
        Symbol reference = toSymbol(shape.getMember());
        return createSymbolBuilder(shape, format("(%s)[]", reference.getName()), null)
                .addReference(reference)
                .build();
    }

    @Override
    public Symbol setShape(SetShape shape) {
        Symbol reference = toSymbol(shape.getMember());
        return createSymbolBuilder(shape, format("(%s)[]", reference.getName()), null)
                .addReference(reference)
                .build();
    }

    /**
     * Maps get generated as an inline interface with a fixed value type.
     *
     * <p>For example:
     *
     * <pre>{@code
     * interface MyStructureShape {
     *   memberPointingToMap: {[key: string]: string};
     * }
     * }</pre>
     *
     * @inheritDoc
     */
    @Override
    public Symbol mapShape(MapShape shape) {
        Symbol reference = toSymbol(shape.getValue());
        return createSymbolBuilder(shape, format("{ [key: string]: %s }", reference.getName()), null)
                .addReference(reference)
                .build();
    }

    @Override
    public Symbol byteShape(ByteShape shape) {
        return createNumber(shape);
    }

    @Override
    public Symbol shortShape(ShortShape shape) {
        return createNumber(shape);
    }

    @Override
    public Symbol integerShape(IntegerShape shape) {
        return createNumber(shape);
    }

    @Override
    public Symbol longShape(LongShape shape) {
        return createNumber(shape);
    }

    @Override
    public Symbol floatShape(FloatShape shape) {
        return createNumber(shape);
    }

    @Override
    public Symbol doubleShape(DoubleShape shape) {
        return createNumber(shape);
    }

    private Symbol createNumber(Shape shape) {
        return createSymbolBuilder(shape, "number").build();
    }

    @Override
    public Symbol bigIntegerShape(BigIntegerShape shape) {
        // BigInt is not supported across all environments, use big.js instead.
        return createBigJsSymbol(shape);
    }

    @Override
    public Symbol bigDecimalShape(BigDecimalShape shape) {
        return createBigJsSymbol(shape);
    }

    private Symbol createBigJsSymbol(Shape shape) {
        return createSymbolBuilder(shape, "Big", TypeScriptDependency.TYPES_BIG_JS.packageName)
                .addDependency(TypeScriptDependency.TYPES_BIG_JS)
                .addDependency(TypeScriptDependency.BIG_JS)
                .build();
    }

    @Override
    public Symbol documentShape(DocumentShape shape) {
        Symbol.Builder builder = createSymbolBuilder(shape, "__DocumentType.Value");
        return addSmithyUseImport(builder, "DocumentType", "__DocumentType").build();
    }

    @Override
    public Symbol operationShape(OperationShape shape) {
        String commandName = flattenShapeName(shape) + "Command";
        String moduleName = moduleNameDelegator.formatModuleName(shape, commandName);
        Symbol intermediate = createGeneratedSymbolBuilder(shape, commandName, moduleName).build();
        Symbol.Builder builder = intermediate.toBuilder();
        // Add input and output type symbols (XCommandInput / XCommandOutput).
        builder.putProperty("inputType", intermediate.toBuilder().name(commandName + "Input").build());
        builder.putProperty("outputType", intermediate.toBuilder().name(commandName + "Output").build());

        return builder.build();
    }

    @Override
    public Symbol stringShape(StringShape shape) {
        // Enums that provide a name for each variant create an actual enum type.
        Optional<EnumTrait> enumTrait = shape.getTrait(EnumTrait.class);
        if (enumTrait.isPresent()) {
            return createEnumSymbol(shape, enumTrait.get());
        }

        // Handle media type generation, defaulting to a string.
        Optional<MediaTypeTrait> mediaTypeTrait = shape.getTrait(MediaTypeTrait.class);
        if (mediaTypeTrait.isPresent()) {
            String mediaType = mediaTypeTrait.get().getValue();
            if (CodegenUtils.isJsonMediaType(mediaType)) {
                Symbol.Builder builder = createSymbolBuilder(shape, "__LazyJsonString | string");
                return addSmithyUseImport(builder, "LazyJsonString", "__LazyJsonString").build();
            } else {
                LOGGER.warning(() -> "Found unsupported mediatype " + mediaType + " on String shape: " + shape);
            }
        }

        return createSymbolBuilder(shape, "string").build();
    }

    private Symbol createEnumSymbol(StringShape shape, EnumTrait enumTrait) {
        return createObjectSymbolBuilder(shape)
                .putProperty(EnumTrait.class.getName(), enumTrait)
                .build();
    }

    @Override
    public Symbol resourceShape(ResourceShape shape) {
        return createObjectSymbolBuilder(shape).build();
    }

    @Override
    public Symbol serviceShape(ServiceShape shape) {
        String name = StringUtils.capitalize(shape.getId().getName(shape)) + "Client";
        String moduleName = moduleNameDelegator.formatModuleName(shape, name);
        return createGeneratedSymbolBuilder(shape, name, moduleName).build();
    }

    @Override
    public Symbol structureShape(StructureShape shape) {
        Symbol.Builder builder = createObjectSymbolBuilder(shape);

        // Errors won't be re-used in locations where being a MetadataBearer is an issue.
        if (errorShapes.contains(shape)) {
            SymbolReference reference = SymbolReference.builder()
                    .options(SymbolReference.ContextOption.DECLARE)
                    .alias("$MetadataBearer")
                    .symbol(TypeScriptDependency.AWS_SDK_TYPES.createSymbol("MetadataBearer"))
                    .putProperty(IMPLEMENTS_INTERFACE_PROPERTY, true)
                    .build();
            builder.addReference(reference);
        }

        return builder.build();
    }

    private Symbol.Builder addSmithyUseImport(Symbol.Builder builder, String name, String as) {
        Symbol importSymbol = Symbol.builder()
                .name(name)
                .namespace("@aws-sdk/smithy-client", "/")
                .build();
        SymbolReference reference = SymbolReference.builder()
                .symbol(importSymbol)
                .alias(as)
                .options(SymbolReference.ContextOption.USE)
                .build();
        return builder.addReference(reference);
    }

    @Override
    public Symbol unionShape(UnionShape shape) {
        return createObjectSymbolBuilder(shape)
                .putProperty("SymbolProvider", this)
                .build();
    }

    @Override
    public Symbol memberShape(MemberShape shape) {
        Shape targetShape = model.getShape(shape.getTarget())
                .orElseThrow(() -> new CodegenException("Shape not found: " + shape.getTarget()));
        Symbol targetSymbol = toSymbol(targetShape)
                .toBuilder()
                .putProperty("model", model)
                .putProperty("SymbolProvider", this)
                .build();

        if (targetSymbol.getProperties().containsKey(EnumTrait.class.getName())) {
            return createMemberSymbolWithEnumTarget(targetSymbol);
        }

        // While unions are targeted with the streaming trait to make them event streams,
        // we don't want to generate a unique type for event streams but instead make
        // member references to them AsyncIterable of the union we generate.
        if (targetShape.hasTrait(StreamingTrait.class) && targetShape.isUnionShape()) {
            return createMemberSymbolWithEventStream(targetSymbol);
        }

        return targetSymbol;
    }

    private Symbol createMemberSymbolWithEnumTarget(Symbol targetSymbol) {
        return targetSymbol.toBuilder()
                .namespace(null, "/")
                .name(targetSymbol.getName() + " | string")
                .addReference(targetSymbol)
                .build();
    }

    private Symbol createMemberSymbolWithEventStream(Symbol targetSymbol) {
        return targetSymbol.toBuilder()
                .namespace(null, "/")
                .name(String.format("AsyncIterable<%s>", targetSymbol.getName()))
                .addReference(targetSymbol)
                .build();
    }

    @Override
    public Symbol timestampShape(TimestampShape shape) {
        return createSymbolBuilder(shape, "Date").build();
    }

    private String flattenShapeName(ToShapeId id) {
        ServiceShape serviceShape = model.expectShape(settings.getService(), ServiceShape.class);
        return StringUtils.capitalize(id.toShapeId().getName(serviceShape));
    }

    private Symbol.Builder createObjectSymbolBuilder(Shape shape) {
        String name = flattenShapeName(shape);
        String moduleName = moduleNameDelegator.formatModuleName(shape, name);
        return createGeneratedSymbolBuilder(shape, name, moduleName);
    }

    private Symbol.Builder createSymbolBuilder(Shape shape, String typeName) {
        return Symbol.builder()
                .putProperty("shape", shape)
                .putProperty("traceFileNamespace", moduleNameDelegator.formatModuleName(shape, null))
                .putProperty("traceFileNamespaceDelimiter", "/")
                .name(typeName);
    }

    private Symbol.Builder createSymbolBuilder(Shape shape, String typeName, String namespace) {
        return Symbol.builder()
                .putProperty("shape", shape)
                .name(typeName)
                .namespace(namespace, "/")
                .putProperty("traceFileNamespace", moduleNameDelegator.formatModuleName(shape, null))
                .putProperty("traceFileNamespaceDelimiter", "/");
    }

    private Symbol.Builder createGeneratedSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createSymbolBuilder(shape, typeName, namespace)
                .definitionFile(toFilename(namespace));
    }

    private String toFilename(String namespace) {
        return namespace + ".ts";
    }

    /**
     * Utility class to locate which path should the symbol be generated into.
     * It will break the models into multiple files to prevent it getting too big.
     */
    static final class ModuleNameDelegator {
        static final int DEFAULT_CHUNK_SIZE = 300;
        static final String SHAPE_NAMESPACE_PREFIX = "./models/";

        private final Map<Shape, String> visitedModels = new HashMap<>();
        private int bucketCount = 0;
        private int currentBucketSize = 0;
        private final int chunkSize;

        ModuleNameDelegator(int shapeChunkSize) {
            chunkSize = shapeChunkSize;
        }

        public String formatModuleName(Shape shape, String name) {
            // All shapes except for the service and operations are stored in models.
            if (shape.getType() == ShapeType.SERVICE) {
                return "./" + name;
            } else if (shape.getType() == ShapeType.OPERATION) {
                return "./commands/" + name;
            } else if (visitedModels.containsKey(shape)) {
                return visitedModels.get(shape);
            }
            // Add models into buckets no bigger than chunk size.
            String path = SHAPE_NAMESPACE_PREFIX + "models_" + bucketCount;
            visitedModels.put(shape, path);
            currentBucketSize++;
            if (currentBucketSize == chunkSize) {
                bucketCount++;
                currentBucketSize = 0;
            }
            return path;
        }

        static void writeModelIndex(Model model, SymbolProvider symbolProvider, FileManifest fileManifest) {
            TypeScriptWriter writer = new TypeScriptWriter("");
            model.shapes()
                    .map(shape -> symbolProvider.toSymbol(shape).getNamespace())
                    .filter(namespace -> namespace.startsWith(SHAPE_NAMESPACE_PREFIX))
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .forEach(namespace -> writer.write(
                        "export * from $S;", namespace.replaceFirst(SHAPE_NAMESPACE_PREFIX, "./")));
            fileManifest.writeFile("models/index.ts", writer.toString());
        }
    }
}
