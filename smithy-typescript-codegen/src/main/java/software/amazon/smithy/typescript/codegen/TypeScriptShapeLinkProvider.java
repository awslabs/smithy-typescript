/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.trace.ShapeLink;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.StringUtils;

/**
 * BiFunction for TraceFile generation that defines a mapping from shapes and symbols to ShapeLinks.
 */
final class TypeScriptShapeLinkProvider implements BiFunction<Shape, Symbol, List<ShapeLink>> {
    static final String BASE_PACKAGE = "software.amazon.awssdk.services";
    // Definitions types.
    static final String FIELD_TYPE = "FIELD";
    static final String METHOD_TYPE = "METHOD";
    static final String TYPE_TYPE = "TYPE";
    // Definitions tags.
    static final String SERVICE_TAG = "service";
    static final String REQUEST_TAG = "request";
    static final String RESPONSE_TAG = "response";
    static final String SERIALIZER_TAG = "serializer";
    static final String DESERIALIZER_TAG = "deserializer";

    private Map<ShapeId, Symbol> cachedParentMap = new HashMap<>();
    private String serviceSymbolName = null;

    TypeScriptShapeLinkProvider() {
    }

    /**
     * Maps a Shape and a Symbol to a List of ShapeLinks. Mappings depend
     * on the ShapeType and the EnumTrait. All classes, interfaces, types
     * interface and class fields, and interface and class methods associated
     * with each Smithy Shape are included. Interface and class
     * fields that are not associated with a Smithy Shape, but are still
     * generated are not included. For example, the fields of the ClientDefaults
     * interface in the (ServiceName)Client.ts file are not included because
     * they are not associated with a Smithy Shape.
     *
     * @param shape  Smithy shape for ShapeLink mapping.
     * @param symbol Smithy symbol for ShapeLink mapping.
     * @return ShapeLink that contains a mapping
     */
    @Override
    public List<ShapeLink> apply(Shape shape, Symbol symbol) {
        switch (shape.getType()) {
            case OPERATION:
                return operationMapping(symbol, shape);
            case UNION:
                return unionMapping(symbol, shape);
            case STRUCTURE:
                return structureMapping(symbol, shape);
            case STRING:
                return stringMapping(symbol, shape);
            case SERVICE:
                return serviceMapping(symbol);
            case MEMBER:
                return memberMapping(symbol, shape);
            default:
                return ListUtils.copyOf(new ArrayList<ShapeLink>());
        }
    }

    private List<ShapeLink> stringMapping(Symbol symbol, Shape shape) {
        if (shape.getTrait(EnumTrait.class).isPresent()) {
            cachedParentMap.put(shape.getId(), symbol);
            return ListUtils.of(ShapeLink.builder()
                    .file(getFile(symbol))
                    .id(getBaseIdWithName(symbol).toString())
                    .type(TYPE_TYPE)
                    .build());
        }
        return ListUtils.copyOf(new ArrayList<>());
    }

    private List<ShapeLink> unionMapping(Symbol symbol, Shape shape) {
        cachedParentMap.put(shape.getId(), symbol);
        List<ShapeLink> shapeLinkList = new ArrayList<>();
        String file = getFile(symbol);
        TypeScriptTracingId baseId = getBaseIdWithName(symbol);

        // Get the SymbolProvider.
        SymbolProvider symbolProvider = (SymbolProvider) symbol.expectProperty("SymbolProvider");

        // Type has a matching namespace artifact that is not included.
        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder()
                        .build()
                        .toString())
                .type(TYPE_TYPE)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName("Visitor")
                        .build()
                        .toString())
                .type(TYPE_TYPE)
                .build());

        for (MemberShape memberShape: shape.asUnionShape().get().getAllMembers().values()) {
            String fieldName = symbolProvider.toMemberName(memberShape);
            shapeLinkList.add(ShapeLink.builder()
                    .file(file)
                    .id(baseId.toBuilder().appendToPackageName("Visitor")
                            .fieldName(fieldName)
                            .build()
                            .toString())
                    .type(FIELD_TYPE)
                    .build());
        }

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().methodName("visit")
                        .build()
                        .toString())
                .type(METHOD_TYPE)
                .build());

        return ListUtils.copyOf(shapeLinkList);
    }

    private List<ShapeLink> structureMapping(Symbol symbol, Shape shape) {
        cachedParentMap.put(shape.getId(), symbol);
        List<ShapeLink> shapeLinkList = new ArrayList<>();
        String type = TYPE_TYPE;
        String file = getFile(symbol);
        TypeScriptTracingId baseId = getBaseIdWithName(symbol);

        // Interface has a matching namespace artifact that was not included.
        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().build().toString())
                .type(type)
                .build());

        // Adding isa constant.
        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().methodName("isa").build().toString())
                .type(type)
                .build());

        // Adding filterSensitiveLog constant.
        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().methodName("filterSensitiveLog").build().toString())
                .type(type)
                .build());

        return ListUtils.copyOf(shapeLinkList);
    }

    private List<ShapeLink> memberMapping(Symbol symbol, Shape shape) {
        List<ShapeLink> shapeLinkList = new ArrayList<>();
        String file = getFile(symbol);

        // Get the model property.
        Model model = (Model) symbol.expectProperty("model");
        SymbolProvider symbolProvider = (SymbolProvider) symbol.expectProperty("SymbolProvider");
        // Get the member's parentShape and symbol.
        Shape parentShape = model.expectShape(shape.getId().withoutMember());
        Symbol parentSymbol = cachedParentMap.get(shape.getId().withoutMember());

        if (parentShape.isUnionShape()) {
            String memberName = StringUtils.capitalize(symbolProvider.toMemberName((MemberShape) shape)) + "Member";
            shapeLinkList.add(ShapeLink.builder()
                    .file(file)
                    .id(getBaseIdWithoutName(symbol).toBuilder()
                            .appendToPackageName(parentSymbol.getName())
                            .appendToPackageName(memberName)
                            .build()
                            .toString())
                    .type(TYPE_TYPE)
                    .build());
        } else {
            shapeLinkList.add(ShapeLink.builder()
                    .file(file)
                    .id(getBaseIdWithoutName(symbol).toBuilder()
                            .appendToPackageName(shape.getId().asRelativeReference())
                            .build()
                            .toString())
                    .type(FIELD_TYPE)
                    .build());
        }
        return ListUtils.copyOf(shapeLinkList);
    }

    private List<ShapeLink> operationMapping(Symbol symbol, Shape shape) {
        cachedParentMap.put(shape.getId(), symbol);
        List<ShapeLink> shapeLinkList = new ArrayList<>();

        String file = getFile(symbol);
        TypeScriptTracingId baseId = getBaseIdWithName(symbol);
        TypeScriptTracingId baseIdWithoutName = getBaseIdWithoutName(symbol);

        String inputType = ((Symbol) symbol.expectProperty("inputType")).getName();
        String outputType = ((Symbol) symbol.expectProperty("outputType")).getName();

        // Tracing CommandGenerator.
        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toString())
                .type(TYPE_TYPE)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseIdWithoutName.toBuilder().appendToPackageName(inputType).build().toString())
                .type(TYPE_TYPE)
                .tags(Collections.singletonList(REQUEST_TAG))
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseIdWithoutName.toBuilder().appendToPackageName(outputType).build().toString())
                .type(TYPE_TYPE)
                .tags(Collections.singletonList(RESPONSE_TAG))
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().methodName("constructor")
                        .build()
                        .toString())
                .type(METHOD_TYPE)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().methodName("serialize")
                        .build()
                        .toString())
                .type(METHOD_TYPE)
                .tags(new ArrayList<>(Collections.singletonList(SERIALIZER_TAG)))
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().methodName("deserialize")
                        .build()
                        .toString())
                .type(METHOD_TYPE)
                .tags(new ArrayList<>(Collections.singletonList(DESERIALIZER_TAG)))
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().methodName("resolveMiddleware")
                        .build()
                        .toString())
                .type(METHOD_TYPE)
                .build());

        // Tracing Unstructured Service Generator.
        String methodName = StringUtils.uncapitalize(
                symbol.getName().replaceAll("Command$", "")
        );

        // Different file without client at the end of service name.
        file = "./" + serviceSymbolName + ".ts";

        baseId = TypeScriptTracingId.builder().packageName(BASE_PACKAGE)
                .appendToPackageName(serviceSymbolName)
                .methodName(methodName)
                .build();
        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder()
                        .build()
                        .toString())
                .type(METHOD_TYPE)
                .build());

        return ListUtils.copyOf(shapeLinkList);
    }

    private List<ShapeLink> serviceMapping(Symbol symbol) {
        List<ShapeLink> shapeLinkList = new ArrayList<>();
        String type = TYPE_TYPE;
        String file = getFile(symbol);
        TypeScriptTracingId baseId = getBaseIdWithoutName(symbol);

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName("ServiceInputTypes").build().toString())
                .type(type)
                .tags(new ArrayList<>(Collections.singletonList(REQUEST_TAG)))
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName("ServiceOutputTypes").build().toString())
                .type(type)
                .tags(new ArrayList<>(Collections.singletonList(RESPONSE_TAG)))
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName("ClientDefaults").build().toString())
                .type(type)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName(symbol.getName() + "Config").build().toString())
                .type(type)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName(symbol.getName() + "ResolvedConfig").build().toString())
                .type(type)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName(symbol.getName()).build().toString())
                .tags(new ArrayList<>(Collections.singletonList(SERVICE_TAG)))
                .type(type)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName(symbol.getName())
                        .fieldName("config")
                        .build()
                        .toString())
                .type(type)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName(symbol.getName())
                        .methodName("constructor")
                        .build()
                        .toString())
                .type(METHOD_TYPE)
                .build());

        shapeLinkList.add(ShapeLink.builder()
                .file(file)
                .id(baseId.toBuilder().appendToPackageName(symbol.getName()).methodName("destroy").build().toString())
                .type(METHOD_TYPE)
                .build());

        // Additional ShapeLink from unstructured ServiceGenerator.
        String fileName = symbol.getName().replaceAll("Client", "");
        serviceSymbolName = fileName;
        shapeLinkList.add(ShapeLink.builder()
                .file("./" + fileName + ".ts")
                .id(TypeScriptTracingId.builder()
                        .packageName(BASE_PACKAGE)
                        .appendToPackageName(fileName)
                        .build()
                        .toString())
                .type(TYPE_TYPE)
                .build());

        return ListUtils.copyOf(shapeLinkList);
    }

    private TypeScriptTracingId getBaseIdWithName(Symbol symbol) {
        return getBaseIdWithoutName(symbol).toBuilder()
                .appendToPackageName(symbol.getName())
                .build();
    }

    private TypeScriptTracingId getBaseIdWithoutName(Symbol symbol) {
        return TypeScriptTracingId.builder()
                .packageName(BASE_PACKAGE)
                .appendToPackageName(getPackageNameAddition(symbol))
                .build();
    }

    private String getFile(Symbol symbol) {
        String file = symbol.getDefinitionFile();
        if (file.equals("")) {
            file = null;
        }
        return file;
    }

    private String getPackageNameAddition(Symbol symbol) {
        String namespace = symbol.getNamespace();
        if (namespace.length() == 0) {
            namespace = symbol.expectProperty("traceFileNamespace").toString();
        }

        String namespaceDelimiter = symbol.getNamespaceDelimiter();
        if (namespaceDelimiter.length() == 0) {
            namespaceDelimiter = symbol.expectProperty("traceFileNamespaceDelimiter").toString();
        }

        return namespace.replace(".", "")
                .replace(namespaceDelimiter, ".")
                .replaceFirst("\\.", "");
    }
}
