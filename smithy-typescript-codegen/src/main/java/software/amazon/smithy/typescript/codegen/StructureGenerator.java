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

import static software.amazon.smithy.typescript.codegen.CodegenUtils.getBlobStreamingMembers;
import static software.amazon.smithy.typescript.codegen.CodegenUtils.writeInlineStreamingMemberType;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.typescript.codegen.integration.HttpProtocolGeneratorUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Generates normal structures and error structures.
 */
@SmithyInternalApi
final class StructureGenerator implements Runnable {

    private final Model model;
    private final SymbolProvider symbolProvider;
    private final TypeScriptWriter writer;
    private final StructureShape shape;
    private final boolean includeValidation;

    /**
     * sets 'includeValidation' to 'false' for backwards compatibility.
     */
    StructureGenerator(Model model, SymbolProvider symbolProvider, TypeScriptWriter writer, StructureShape shape) {
        this(model, symbolProvider, writer, shape, false);
    }

    StructureGenerator(Model model,
                       SymbolProvider symbolProvider,
                       TypeScriptWriter writer,
                       StructureShape shape,
                       boolean includeValidation) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.shape = shape;
        this.includeValidation = includeValidation;
    }

    @Override
    public void run() {
        if (shape.hasTrait(ErrorTrait.class)) {
            renderErrorStructure();
        } else {
            renderNonErrorStructure();
        }
    }

    /**
     * Renders a normal, non-error structure.
     *
     * <p>For example, given the following Smithy model:
     *
     * <pre>{@code
     * namespace smithy.example
     *
     * structure Person {
     *     @required
     *     name: String,
     *     @range(min: 1)
     *     age: Integer,
     * }
     * }</pre>
     *
     * <p>The following TypeScript is rendered:
     *
     * <pre>{@code
     * export interface Person {
     *   name: string | undefined;
     *   age?: number | null;
     * }
     *
     * export namespace Person {
     *   export const filterSensitiveLog = (obj: Person): any => ({...obj});
     * }
     * }</pre>
     *
     * <p>If validation is enabled, it generates the following:
     *
     * <pre>{@code
     * export interface Person {
     *   name: string | undefined;
     *   age?: number | null;
     * }
     *
     * export namespace Person {
     *   export const filterSensitiveLog = (obj: Person): any => ({...obj});
     *   export const validate = (obj: Person): ValidationFailure[] => {
     *       // validation
     *   }
     * }
     * }</pre>
     */
    private void renderNonErrorStructure() {
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.writeShapeDocs(shape);

        // Find symbol references with the "extends" property.
        String extendsFrom = symbol.getReferences().stream()
                .filter(ref -> ref.getProperty(SymbolVisitor.IMPLEMENTS_INTERFACE_PROPERTY).isPresent())
                .map(SymbolReference::getAlias)
                .collect(Collectors.joining(", "));

        if (extendsFrom.isEmpty()) {
            writer.openBlock("export interface $L {", symbol.getName());
        } else {
            writer.openBlock("export interface $L extends $L {", symbol.getName(), extendsFrom);
        }

        StructuredMemberWriter config = new StructuredMemberWriter(
                model, symbolProvider, shape.getAllMembers().values());
        config.writeMembers(writer, shape);
        writer.closeBlock("}");
        writer.write("");
        renderStructureNamespace(config, includeValidation);
    }

    /**
     * Error structures generate interfaces that extend from SmithyException
     * and add the appropriate fault property.
     *
     * <p>Given the following Smithy structure:
     *
     * <pre>{@code
     * namespace smithy.example
     *
     * @error("client")
     * structure NoSuchResource {
     *     @required
     *     resourceType: String
     * }
     * }</pre>
     *
     * <p>The following TypeScript is generated:
     *
     * <pre>{@code
     * import {
     *     SmithyException as __SmithyException
     * } from "@aws-sdk/types";
     *
     * export interface NoSuchResource extends __SmithyException, $MetadataBearer {
     *   name: "NoSuchResource";
     *   $fault: "client";
     *   resourceType: string | undefined;
     * }
     *
     * export namespace NoSuchResource {
     *   export const filterSensitiveLog = (obj: NoSuchResource): any => ({...obj});
     * }
     * }</pre>
     */
    private void renderErrorStructure() {
        ErrorTrait errorTrait = shape.getTrait(ErrorTrait.class).orElseThrow(IllegalStateException::new);
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.writeShapeDocs(shape);

        // Find symbol references with the "extends" property, and add SmithyException.
        writer.addImport("SmithyException", "__SmithyException", "@aws-sdk/types");
        String extendsFrom = Stream.concat(
                Stream.of("__SmithyException"),
                symbol.getReferences().stream()
                        .filter(ref -> ref.getProperty(SymbolVisitor.IMPLEMENTS_INTERFACE_PROPERTY).isPresent())
                        .map(SymbolReference::getAlias)
                ).collect(Collectors.joining(", "));

        writer.openBlock("export interface $L extends $L {", symbol.getName(), extendsFrom);
        writer.write("name: $S;", shape.getId().getName());
        writer.write("$$fault: $S;", errorTrait.getValue());
        HttpProtocolGeneratorUtils.writeRetryableTrait(writer, shape, ";");
        StructuredMemberWriter structuredMemberWriter = new StructuredMemberWriter(
                model, symbolProvider, shape.getAllMembers().values());
        structuredMemberWriter.writeMembers(writer, shape);
        writer.closeBlock("}"); // interface
        writer.write("");
        renderStructureNamespace(structuredMemberWriter, false);
    }

    private void renderStructureNamespace(StructuredMemberWriter structuredMemberWriter, boolean includeValidation) {
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.openBlock("export namespace $L {", "}", symbol.getName(), () -> {
            String objectParam = "obj";
            writer.writeDocs("@internal");
            writer.openBlock("export const filterSensitiveLog = ($L: $L): any => ({", "})",
                objectParam, symbol.getName(),
                () -> {
                    structuredMemberWriter.writeFilterSensitiveLog(writer, objectParam);
                }
            );

            if (!includeValidation) {
                return;
            }

            structuredMemberWriter.writeMemberValidatorCache(writer, "memberValidators");

            writer.addImport("ValidationFailure", "__ValidationFailure", "@aws-smithy/server-common");
            writer.writeDocs("@internal");
            List<MemberShape> blobStreamingMembers = getBlobStreamingMembers(model, shape);
            writer.writeInline("export const validate = ($L: ", objectParam);
            if (blobStreamingMembers.isEmpty()) {
                writer.writeInline("$L", symbol.getName());
            } else {
                writeInlineStreamingMemberType(writer, symbol, blobStreamingMembers.get(0));
            }
            writer.openBlock(", path: string = \"\"): __ValidationFailure[] => {", "}", () -> {
                structuredMemberWriter.writeMemberValidatorFactory(writer, "memberValidators");
                structuredMemberWriter.writeValidateMethodContents(writer, objectParam);
            });
        });
    }
}
