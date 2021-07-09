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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.TopologicalIndex;
import software.amazon.smithy.codegen.core.trace.ArtifactDefinitions;
import software.amazon.smithy.codegen.core.trace.TraceMetadata;
import software.amazon.smithy.codegen.core.trace.TracingSymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.PaginatedTrait;
import software.amazon.smithy.typescript.codegen.TypeScriptSettings.ArtifactType;
import software.amazon.smithy.typescript.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.typescript.codegen.integration.TypeScriptIntegration;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.waiters.WaitableTrait;
import software.amazon.smithy.waiters.Waiter;

@SmithyInternalApi
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
    private static final ShapeId VALIDATION_EXCEPTION_SHAPE =
            ShapeId.fromParts("smithy.framework", "ValidationException");

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

    CodegenVisitor(PluginContext context, ArtifactType artifactType) {
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
        TypeScriptSettings typescriptSettings = TypeScriptSettings.from(
                context.getModel(), context.getSettings(), artifactType);
        for (TypeScriptIntegration integration : integrations) {
            Model modifiedModel = integration.preprocessModel(context, typescriptSettings);
            if (modifiedModel != context.getModel()) {
                context = context.toBuilder().model(modifiedModel).build();
                typescriptSettings = TypeScriptSettings.from(modifiedModel, context.getSettings(), artifactType);
            }
        }
        settings = typescriptSettings;
        model = context.getModel();
        nonTraits = context.getModelWithoutTraitShapes();
        service = settings.getService(model);
        fileManifest = context.getFileManifest();
        LOGGER.info(() -> String.format("Generating TypeScript %s for service %s",
                settings.generateClient() ? "client" : "server", service.getId()));

        // Decorate the symbol provider using integrations.
        SymbolProvider resolvedProvider = artifactType.createSymbolProvider(model, settings);
        for (TypeScriptIntegration integration : integrations) {
            resolvedProvider = integration.decorateSymbolProvider(settings, model, resolvedProvider);
        }

        // Resolve the nullable protocol generator and application protocol.
        protocolGenerator = resolveProtocolGenerator(integrations, service, settings);
        applicationProtocol = protocolGenerator == null
                ? ApplicationProtocol.createDefaultHttpApplicationProtocol()
                : protocolGenerator.getApplicationProtocol();
        // Make the symbol provider a cachingSymbolProvider.
        SymbolProvider cachedProvider = SymbolProvider.cache(resolvedProvider);
        // Defining Definitions for TraceFile Generation.
        ArtifactDefinitions artifactDefinitions = ArtifactDefinitions.builder()
                .addType(TypeScriptShapeLinkProvider.FIELD_TYPE,
                        "Field declaration (includes enum constants)")
                .addType(TypeScriptShapeLinkProvider.METHOD_TYPE, "Method declaration")
                .addType(TypeScriptShapeLinkProvider.TYPE_TYPE,
                        "Class, interface (including annotation type), or enum declaration")
                .addTag(TypeScriptShapeLinkProvider.SERVICE_TAG, "Service client")
                .addTag(TypeScriptShapeLinkProvider.REQUEST_TAG, "AWS SDK request type")
                .addTag(TypeScriptShapeLinkProvider.RESPONSE_TAG, "AWS SDK response type")
                .addTag(TypeScriptShapeLinkProvider.SERIALIZER_TAG, "Command serializer")
                .addTag(TypeScriptShapeLinkProvider.DESERIALIZER_TAG, "Command deserializer")
                .build();

        String serviceId = service.getId().getName();
        TraceMetadata artifactMetadata = TraceMetadata.builder()
                .setTimestampAsNow()
                .id(serviceId)
                .version(UUID.randomUUID().toString())
                .type("TypeScript")
                .build();


        // Decorate the symbol provider using the trace file generator.
        symbolProvider = TracingSymbolProvider.builder()
                .symbolProvider(cachedProvider)
                .metadata(artifactMetadata)
                .artifactDefinitions(artifactDefinitions)
                .shapeLinkCreator(new TypeScriptShapeLinkProvider())
                .build();

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
        // Walk the tree.
        Collection<Shape> shapeSet = new Walker(nonTraits).walkShapes(service);

        Model prunedModel = Model.builder().addShapes(shapeSet).build();

        // Generate models from shapes.
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
        if (settings.generateClient()) {
            // For now these are only generated for clients.
            // TODO: generate ssdk config
            RuntimeConfigGenerator configGenerator = new RuntimeConfigGenerator(
                    settings, model, symbolProvider, writers, integrations);
            for (LanguageTarget target : LanguageTarget.values()) {
                LOGGER.fine("Generating " + target + " runtime configuration");
                configGenerator.generate(target);
            }
        }

        // Write each custom file.
        for (TypeScriptIntegration integration : integrations) {
            LOGGER.finer(() -> "Calling writeAdditionalFiles on " + integration.getClass().getCanonicalName());
            integration.writeAdditionalFiles(settings, model, symbolProvider, writers::useFileWriter);
        }

        // Generate index for client.
        IndexGenerator.writeIndex(settings, model, symbolProvider, fileManifest, integrations, protocolGenerator);

        if (settings.generateServerSdk()) {
            checkValidationSettings();
            // Generate index for server
            IndexGenerator.writeServerIndex(settings, model, symbolProvider, fileManifest);
        }

        // Generate protocol tests IFF found in the model.
        if (protocolGenerator != null) {
            ShapeId protocol = protocolGenerator.getProtocol();
            ProtocolGenerator.GenerationContext context = new ProtocolGenerator.GenerationContext();
            context.setProtocolName(protocolGenerator.getName());
            context.setIntegrations(integrations);
            context.setModel(model);
            context.setService(service);
            context.setSettings(settings);
            context.setSymbolProvider(symbolProvider);
            String baseName = protocol.getName().toLowerCase(Locale.US)
                    .replace("-", "_")
                    .replace(".", "_");
            String protocolTestFileName = String.format("tests/functional/%s.spec.ts", baseName);
            context.setDeferredWriter(() -> writers.checkoutFileWriter(protocolTestFileName));
            protocolGenerator.generateProtocolTests(context);
        }

        // Write the TraceFile.
        TracingSymbolProvider traceProvider = (TracingSymbolProvider) symbolProvider;
        String traceName = symbolProvider.toSymbol(service).getName().replace("Client", "")
                .toLowerCase() + ".trace.json";
        fileManifest.writeFile(traceName,
                Node.prettyPrintJson(traceProvider.buildTraceFile().toNode()));

        // Write each pending writer.
        LOGGER.fine("Flushing TypeScript writers");
        List<SymbolDependency> dependencies = writers.getDependencies();
        writers.flushWriters();

        // Write the package.json file, including all symbol dependencies.
        LOGGER.fine("Generating package.json files");
        PackageJsonGenerator.writePackageJson(
                settings, fileManifest, SymbolDependency.gatherDependencies(dependencies.stream()));
    }

    private void checkValidationSettings() {
        if (settings.isDisableDefaultValidation()) {
            return;
        }

        final OperationIndex operationIndex = OperationIndex.of(model);

        List<String> unvalidatedOperations = TopDownIndex.of(model)
                .getContainedOperations(service)
                .stream()
                .filter(o -> operationIndex.getErrors(o).stream()
                                .noneMatch(e -> e.getId().equals(VALIDATION_EXCEPTION_SHAPE)))
                .map(s -> s.getId().toString())
                .sorted()
                .collect(Collectors.toList());

        if (!unvalidatedOperations.isEmpty()) {
            throw new CodegenException(String.format("Every operation must have the %s error attached unless %s is set "
                    + "to 'true' in the plugin settings. Operations without %s errors attached: %s",
                    VALIDATION_EXCEPTION_SHAPE,
                    TypeScriptSettings.DISABLE_DEFAULT_VALIDATION,
                    VALIDATION_EXCEPTION_SHAPE,
                    unvalidatedOperations));
        }
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
        writers.useShapeWriter(shape, writer ->
                new StructureGenerator(model, symbolProvider, writer, shape, settings.generateServerSdk()).run());
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
        writers.useShapeWriter(shape, writer ->
                new UnionGenerator(model, symbolProvider, writer, shape, settings.generateServerSdk()).run());
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
    public Void operationShape(OperationShape operation) {
        if (settings.generateServerSdk()) {
            writers.useShapeWriter(operation, symbolProvider, w -> {
                ServerGenerator.generateOperationHandler(symbolProvider, service, operation, w);
            });
        }
        return null;
    }

    @Override
    public Void serviceShape(ServiceShape shape) {
        if (!Objects.equals(service, shape)) {
            LOGGER.fine(() -> "Skipping `" + shape.getId() + "` because it is not `" + service.getId() + "`");
            return null;
        }

        if (settings.generateClient()) {
            generateClient(shape);
        }
        if (settings.generateClient() || settings.generateServerSdk()) {
            generateCommands(shape);
        }

        if (settings.generateServerSdk()) {
            generateServiceInterface(shape);
            generateServerErrors(shape);
        }

        if (protocolGenerator != null) {
            LOGGER.info("Generating serde for protocol " + protocolGenerator.getName() + " on " + shape.getId());
            String fileName = "protocols/" + ProtocolGenerator.getSanitizedName(protocolGenerator.getName()) + ".ts";
            writers.useFileWriter(fileName, writer -> {
                ProtocolGenerator.GenerationContext context = new ProtocolGenerator.GenerationContext();
                context.setProtocolName(protocolGenerator.getName());
                context.setIntegrations(integrations);
                context.setModel(model);
                context.setService(shape);
                context.setSettings(settings);
                context.setSymbolProvider(symbolProvider);
                context.setWriter(writer);
                if (context.getSettings().generateClient()) {
                    protocolGenerator.generateRequestSerializers(context);
                    protocolGenerator.generateResponseDeserializers(context);
                }
                if (context.getSettings().generateServerSdk()) {
                    ProtocolGenerator.GenerationContext serverContext =
                            context.withSymbolProvider(symbolProvider);
                    protocolGenerator.generateRequestDeserializers(serverContext);
                    protocolGenerator.generateResponseSerializers(serverContext);
                    protocolGenerator.generateFrameworkErrorSerializer(serverContext);
                    writers.useShapeWriter(shape, symbolProvider, w -> {
                        protocolGenerator.generateServiceHandlerFactory(serverContext.withWriter(w));
                    });
                    for (OperationShape operation: TopDownIndex.of(model).getContainedOperations(service)) {
                        writers.useShapeWriter(operation, symbolProvider, w -> {
                            protocolGenerator.generateOperationHandlerFactory(serverContext.withWriter(w), operation);
                        });
                    }
                }
                protocolGenerator.generateSharedComponents(context);
            });
        }

        return null;
    }

    private void generateClient(ServiceShape shape) {
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
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        Set<OperationShape> containedOperations = new TreeSet<>(topDownIndex.getContainedOperations(service));
        boolean hasPaginatedOperation = false;

        for (OperationShape operation : containedOperations) {
            if (operation.hasTrait(PaginatedTrait.ID)) {
                hasPaginatedOperation = true;
                String outputFilename = PaginationGenerator.getOutputFilelocation(operation);
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
            writers.useFileWriter(PaginationGenerator.PAGINATION_INTERFACE_FILE, paginationWriter ->
                    PaginationGenerator.generateServicePaginationInterfaces(
                            nonModularName,
                            serviceSymbol,
                            paginationWriter));
        }
    }

    private void generateServiceInterface(ServiceShape shape) {
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        Set<OperationShape> operations = new TreeSet<>(topDownIndex.getContainedOperations(shape));
        writers.useShapeWriter(shape, symbolProvider, writer -> {
            ServerGenerator.generateOperationsType(symbolProvider, shape, operations, writer);
            ServerGenerator.generateServerInterfaces(symbolProvider, shape, operations, writer);
            ServerGenerator.generateServiceHandler(symbolProvider, shape, operations, writer);
        });
    }

    private void generateServerErrors(ServiceShape service) {
        TopDownIndex.of(model)
                .getContainedOperations(service)
                .stream()
                .flatMap(o -> o.getErrors().stream())
                .distinct()
                .map(id -> model.expectShape(id).asStructureShape().orElseThrow(IllegalArgumentException::new))
                .sorted()
                .forEachOrdered(error -> writers.useShapeWriter(service, symbolProvider, writer -> {
                    new ServerErrorGenerator(settings, model, error, symbolProvider, writer).run();
                }));
    }

    private void generateCommands(ServiceShape shape) {
        // Generate each operation for the service.
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        Set<OperationShape> containedOperations = new TreeSet<>(topDownIndex.getContainedOperations(shape));
        for (OperationShape operation : containedOperations) {
            // Right now this only generates stubs.
            if (settings.generateClient()) {
                writers.useShapeWriter(operation, commandWriter -> new CommandGenerator(
                        settings, model, operation, symbolProvider, commandWriter,
                        runtimePlugins, protocolGenerator, applicationProtocol).run());
            }

            if (settings.generateServerSdk()) {
                writers.useShapeWriter(operation, symbolProvider, commandWriter -> new ServerCommandGenerator(
                        settings, model, operation, symbolProvider, commandWriter,
                        protocolGenerator, applicationProtocol).run());
            }
        }
    }
}
