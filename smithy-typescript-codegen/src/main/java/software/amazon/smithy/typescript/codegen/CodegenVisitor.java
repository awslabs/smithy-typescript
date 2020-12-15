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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.TopologicalIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.BoxTrait;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.PaginatedTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.typescript.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.typescript.codegen.integration.TypeScriptIntegration;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.waiters.WaitableTrait;
import software.amazon.smithy.waiters.Waiter;

class CodegenVisitor extends ShapeVisitor.Default<Void> {

    private static final Logger LOGGER = Logger.getLogger(CodegenVisitor.class.getName());

    /**
     * A mapping of static resource files to copy over to a new filename.
     */
    private static final Map<String, String> STATIC_FILE_COPIES = MapUtils.of(
            "jest.config.js", "jest.config.js",
            "tsconfig.es.json", "tsconfig.es.json",
            "tsconfig.json", "tsconfig.json"
    );

    private final TypeScriptSettings settings;
    private final Model model;
    private final ServiceShape service;
    private final FileManifest fileManifest;
    private final SymbolProvider symbolProvider;
    private final Model nonTraits;
    private final TypeScriptDelegator writers;
    private final List<TypeScriptIntegration> integrations = new ArrayList<>();
    private final List<RuntimeClientPlugin> runtimePlugins = new ArrayList<>();
    private final ProtocolGenerator protocolGenerator;
    private final ApplicationProtocol applicationProtocol;

    CodegenVisitor(PluginContext context) {
        // Load all integrations.
        ClassLoader loader = context.getPluginClassLoader().orElse(getClass().getClassLoader());
        LOGGER.info("Attempting to discover TypeScriptIntegration from the classpath...");
        ServiceLoader.load(TypeScriptIntegration.class, loader)
                .forEach(integration -> {
                    LOGGER.info(() -> "Adding TypeScriptIntegration: " + integration.getClass().getName());
                    integrations.add(integration);
                    integration.getClientPlugins().forEach(runtimePlugin -> {
                        LOGGER.info(() -> "Adding TypeScript runtime plugin: " + runtimePlugin);
                        runtimePlugins.add(runtimePlugin);
                    });
                });
        // Sort the integrations in specified order.
        integrations.sort(Comparator.comparingInt(TypeScriptIntegration::getOrder));

        // Preprocess model using integrations.
        TypeScriptSettings typescriptSettings = TypeScriptSettings.from(context.getModel(), context.getSettings());
        for (TypeScriptIntegration integration : integrations) {
            Model modifiedModel = integration.preprocessModel(context, typescriptSettings);
            if (modifiedModel != context.getModel()) {
                context = context.toBuilder().model(modifiedModel).build();
                typescriptSettings = TypeScriptSettings.from(modifiedModel, context.getSettings());
            }
        }
        settings = typescriptSettings;
        model = context.getModel();
        nonTraits = context.getModelWithoutTraitShapes();
        service = settings.getService(model);
        fileManifest = context.getFileManifest();
        LOGGER.info(() -> "Generating TypeScript client for service " + service.getId());

        // Decorate the symbol provider using integrations.
        SymbolProvider resolvedProvider = TypeScriptCodegenPlugin.createSymbolProvider(model);
        for (TypeScriptIntegration integration : integrations) {
            resolvedProvider = integration.decorateSymbolProvider(settings, model, resolvedProvider);
        }
        symbolProvider = SymbolProvider.cache(resolvedProvider);

        // Resolve the nullable protocol generator and application protocol.
        protocolGenerator = resolveProtocolGenerator(integrations, service, settings);
        applicationProtocol = protocolGenerator == null
                ? ApplicationProtocol.createDefaultHttpApplicationProtocol()
                : protocolGenerator.getApplicationProtocol();

        writers = new TypeScriptDelegator(settings, model, fileManifest, symbolProvider, integrations);
    }

    private ProtocolGenerator resolveProtocolGenerator(
            Collection<TypeScriptIntegration> integrations,
            ServiceShape service,
            TypeScriptSettings settings
    ) {
        // Collect all of the supported protocol generators.
        Map<ShapeId, ProtocolGenerator> generators = new HashMap<>();
        for (TypeScriptIntegration integration : integrations) {
            for (ProtocolGenerator generator : integration.getProtocolGenerators()) {
                generators.put(generator.getProtocol(), generator);
            }
        }

        ShapeId protocolName;
        try {
            protocolName = settings.resolveServiceProtocol(model, service, generators.keySet());
        } catch (UnresolvableProtocolException e) {
            LOGGER.warning("Unable to find a protocol generator for " + service.getId() + ": " + e.getMessage());
            protocolName = null;
        }

        return protocolName != null ? generators.get(protocolName) : null;
    }

    void execute() {
        // Write shared / static content.
        STATIC_FILE_COPIES.forEach((from, to) -> {
            LOGGER.fine(() -> "Writing contents of `" + from + "` to `" + to + "`");
            fileManifest.writeFile(from, getClass(), to);
        });

        // Generate models that are connected to the service being generated.
        LOGGER.fine("Walking shapes from " + service.getId() + " to find shapes to generate");
        // Walk the tree and condense duplicate shapes
        Collection<Shape> shapeSet = condenseShapes(new Walker(nonTraits).walkShapes(service));
        Model prunedModel = Model.builder().addShapes(shapeSet).build();

        // Generate models from condensed shapes
        for (Shape shape : TopologicalIndex.of(prunedModel).getOrderedShapes()) {
            shape.accept(this);
        }
        for (Shape shape : TopologicalIndex.of(prunedModel).getRecursiveShapes()) {
            shape.accept(this);
        }
        SymbolVisitor.writeModelIndex(prunedModel, symbolProvider, fileManifest);

        // Generate the client Node and Browser configuration files. These
        // files are switched between in package.json based on the targeted
        // environment.
        RuntimeConfigGenerator configGenerator = new RuntimeConfigGenerator(
                settings, model, symbolProvider, writers, integrations);
        for (LanguageTarget target : LanguageTarget.values()) {
            LOGGER.fine("Generating " + target + " runtime configuration");
            configGenerator.generate(target);
        }

        // Write each custom file.
        for (TypeScriptIntegration integration : integrations) {
            LOGGER.finer(() -> "Calling writeAdditionalFiles on " + integration.getClass().getCanonicalName());
            integration.writeAdditionalFiles(settings, model, symbolProvider, writers::useFileWriter);
        }

        // Generate index for client.
        IndexGenerator.writeIndex(settings, model, symbolProvider, fileManifest);

        // Generate protocol tests IFF found in the model.
        if (protocolGenerator != null) {
            ShapeId protocol = protocolGenerator.getProtocol();
            new HttpProtocolTestGenerator(settings, model, protocol, symbolProvider, writers).run();
        }

        // Write each pending writer.
        LOGGER.fine("Flushing TypeScript writers");
        List<SymbolDependency> dependencies = writers.getDependencies();
        writers.flushWriters();

        // Write the package.json file, including all symbol dependencies.
        LOGGER.fine("Generating package.json files");
        PackageJsonGenerator.writePackageJson(
                settings, fileManifest, SymbolDependency.gatherDependencies(dependencies.stream()));
    }

    @Override
    protected Void getDefault(Shape shape) {
        return null;
    }

    /**
     * Renders structures as interfaces.
     *
     * <p>A namespace is created with the same name as the structure to
     * provide helper functionality for checking if a given value is
     * known to be of the same type as the structure. This will be
     * even more useful if/when inheritance is added to Smithy.
     *
     * <p>Note that the {@code required} trait on structures is used to
     * determine whether or not a generated TypeScript interface uses
     * required members. This is typically not recommended in other languages
     * since it's documented as backward-compatible for a model to migrate a
     * required property to optional. This becomes an issue when an older
     * client consumes a service that has relaxed a member to become optional.
     * In the case of sending data from the client to the server, the client
     * likely either is still operating under the assumption that the property
     * is required, or the client can set a property explicitly to
     * {@code undefined} to fix any TypeScript compilation errors. In the
     * case of deserializing a value from a service to the client, the
     * deserializers will need to set previously required properties to
     * undefined too.
     *
     * <p>The generator will explicitly state that a required property can
     * be set to {@code undefined}. This makes it clear that undefined checks
     * need to be made when using {@code --strictNullChecks}, but has no
     * effect otherwise.
     *
     * @param shape Shape being generated.
     */
    @Override
    public Void structureShape(StructureShape shape) {
        writers.useShapeWriter(shape, writer -> new StructureGenerator(model, symbolProvider, writer, shape).run());
        return null;
    }

    /**
     * Renders a TypeScript union.
     *
     * @param shape Shape to render as a union.
     * @see UnionGenerator
     */
    @Override
    public Void unionShape(UnionShape shape) {
        writers.useShapeWriter(shape, writer -> new UnionGenerator(model, symbolProvider, writer, shape).run());
        return null;
    }

    @Override
    public Void stringShape(StringShape shape) {
        if (shape.hasTrait(EnumTrait.class)) {
            Symbol symbol = symbolProvider.toSymbol(shape);
            writers.useShapeWriter(shape, writer -> new EnumGenerator(shape, symbol, writer).run());
        }

        return null;
    }

    @Override
    public Void serviceShape(ServiceShape shape) {
        if (!Objects.equals(service, shape)) {
            LOGGER.fine(() -> "Skipping `" + shape.getId() + "` because it is not `" + service.getId() + "`");
            return null;
        }

        // Generate the modular service client.
        writers.useShapeWriter(shape, writer -> new ServiceGenerator(
                settings, model, symbolProvider, writer, integrations, runtimePlugins, applicationProtocol).run());

        // Generate the non-modular service client.
        Symbol serviceSymbol = symbolProvider.toSymbol(shape);
        String nonModularName = serviceSymbol.getName().replace("Client", "");
        String filename = serviceSymbol.getDefinitionFile().replace("Client", "");
        writers.useFileWriter(filename, writer -> new NonModularServiceGenerator(
                settings, model, symbolProvider, nonModularName, writer, applicationProtocol).run());

        // Generate each operation for the service.
        TopDownIndex topDownIndex = model.getKnowledge(TopDownIndex.class);
        Set<OperationShape> containedOperations = new TreeSet<>(topDownIndex.getContainedOperations(service));
        boolean hasPaginatedOperation = false;

        for (OperationShape operation : containedOperations) {
            writers.useShapeWriter(operation, commandWriter -> new CommandGenerator(
                    settings, model, operation, symbolProvider, commandWriter,
                    runtimePlugins, protocolGenerator, applicationProtocol).run());
            if (operation.hasTrait(PaginatedTrait.ID)) {
                hasPaginatedOperation = true;
                String outputFilename = "./src/" + PaginationGenerator.getOutputFilelocation(operation);
                writers.useFileWriter(outputFilename, paginationWriter ->
                        new PaginationGenerator(model, service, operation, symbolProvider, paginationWriter,
                                nonModularName).run());
            }
            if (operation.hasTrait(WaitableTrait.ID)) {
                WaitableTrait waitableTrait = operation.expectTrait(WaitableTrait.class);

                waitableTrait.getWaiters().forEach((String waiterName, Waiter waiter) -> {
                    String outputFilename = WaiterGenerator.getOutputFileLocation(waiterName);
                    writers.useFileWriter(outputFilename, waiterWriter ->
                            new WaiterGenerator(waiterName, waiter, service, operation, waiterWriter,
                                    symbolProvider).run());
                });
            }
        }

        if (hasPaginatedOperation) {
            writers.useFileWriter("./src/" + PaginationGenerator.PAGINATION_INTERFACE_FILE, paginationWriter ->
                    PaginationGenerator.generateServicePaginationInterfaces(
                            nonModularName,
                            serviceSymbol,
                            paginationWriter));
        }

        if (protocolGenerator != null) {
            LOGGER.info("Generating serde for protocol " + protocolGenerator.getName() + " on " + shape.getId());
            String fileName = "src/protocols/"
                    + ProtocolGenerator.getSanitizedName(protocolGenerator.getName()) + ".ts";
            writers.useFileWriter(fileName, writer -> {
                ProtocolGenerator.GenerationContext context = new ProtocolGenerator.GenerationContext();
                context.setProtocolName(protocolGenerator.getName());
                context.setIntegrations(integrations);
                context.setModel(model);
                context.setService(shape);
                context.setSettings(settings);
                context.setSymbolProvider(symbolProvider);
                context.setWriter(writer);
                protocolGenerator.generateRequestSerializers(context);
                protocolGenerator.generateResponseDeserializers(context);
                protocolGenerator.generateSharedComponents(context);
            });
        }

        return null;
    }

    private Collection<Shape> condenseShapes(Set<Shape> shapes) {
        Map<String, Shape> shapeMap = new LinkedHashMap<>();

        // Check for colliding shapes and prune non-unique shapes
        for (Shape shape : shapes) {
            String shapeReference = shape.getType().toString() + shape.getId().asRelativeReference();

            if (shapeMap.containsKey(shapeReference)) {
                Shape knownShape = shapeMap.get(shapeReference);
                if (isShapeCollision(shape, knownShape)) {
                    throw new CodegenException(("Shape Collision: cannot condense " + shape + " and " + knownShape));
                }
            } else {
                shapeMap.put(shapeReference, shape);
            }
        }

        return shapeMap.values();
    }

    private boolean isShapeCollision(Shape shapeA, Shape shapeB) {
        // Check names match.
        if (!shapeA.getId().getName().equals(shapeB.getId().getName())) {
            return true;
        }

        // Check traits match.
        Map<ShapeId, Trait> traitsA = new HashMap<>(shapeA.getAllTraits());
        Map<ShapeId, Trait> traitsB = new HashMap<>(shapeB.getAllTraits());
        // Ignore the box trait since it has no effect in JavaScript.
        traitsA.remove(BoxTrait.ID);
        traitsB.remove(BoxTrait.ID);
        if (!traitsA.equals(traitsB)) {
            return false;
        }

        // Check members match.
        Collection<MemberShape> memberShapesA = shapeA.members();
        Collection<MemberShape> memberShapesB = shapeB.members();
        for (MemberShape memberShape : memberShapesA) {
            if (!memberShapesB.stream().anyMatch(s -> s.getMemberName().contains(memberShape.getMemberName()))) {
                return true;
            }
        }
        for (MemberShape otherMemberShape : memberShapesB) {
            if (!memberShapesA.stream().anyMatch(s -> s.getMemberName().contains(otherMemberShape.getMemberName()))) {
                return true;
            }
        }

        return false;
    }
}
