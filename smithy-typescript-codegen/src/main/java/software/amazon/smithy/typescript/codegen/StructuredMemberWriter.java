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

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.SimpleShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.IdempotencyTokenTrait;
import software.amazon.smithy.model.traits.SensitiveTrait;

/**
 * Generates objects, interfaces, enums, etc.
 *
 * TODO: Replace this with a builder for generating classes and interfaces.
 */
final class StructuredMemberWriter {

    Model model;
    SymbolProvider symbolProvider;
    Collection<MemberShape> members;
    String memberPrefix = "";
    boolean noDocs;
    final Set<String> skipMembers = new HashSet<>();

    StructuredMemberWriter(Model model, SymbolProvider symbolProvider, Collection<MemberShape> members) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.members = members;
    }

    void writeMembers(TypeScriptWriter writer, Shape shape) {
        int position = -1;
        for (MemberShape member : members) {
            if (skipMembers.contains(member.getMemberName())) {
                continue;
            }

            position++;
            boolean wroteDocs = !noDocs && writer.writeMemberDocs(model, member);
            String memberName = TypeScriptUtils.sanitizePropertyName(symbolProvider.toMemberName(member));
            String optionalSuffix = shape.isUnionShape() || !isRequiredMember(member) ? "?" : "";
            String typeSuffix = isRequiredMember(member) ? " | undefined" : "";
            writer.write("${L}${L}${L}: ${T}${L};", memberPrefix, memberName, optionalSuffix,
                         symbolProvider.toSymbol(member), typeSuffix);

            if (wroteDocs && position < members.size() - 1) {
                writer.write("");
            }
        }
    }

    /**
     * Recursively writes filterSensitiveLog for list members
     */
    void writeFilterSensitiveLogForArray(TypeScriptWriter writer, MemberShape arrayMember) {
        Shape memberShape = model.expectShape(arrayMember.getTarget());
        if (memberShape instanceof StructureShape) {
            // Call filterSensitiveLog on Structure
            writer.write("${T}.filterSensitiveLog", symbolProvider.toSymbol(arrayMember));
        } else if (memberShape instanceof ListShape || memberShape instanceof SetShape) {
            // Iterate over array items, and call array specific function on each member
            writer.openBlock("item => item.map(", ")",
                () -> {
                    MemberShape nestedArrayMember = ((CollectionShape) memberShape).getMember();
                    writeFilterSensitiveLogForArray(writer, nestedArrayMember);
                }
            );
        } else {
            // Function is inside another function, so just return item in else case
            writer.write("item => item");
        }
    }

    void writeFilterSensitiveLog(TypeScriptWriter writer, Shape shape) {
        writer.write("...obj,");
        for (MemberShape member : members) {
            Shape memberShape = model.expectShape(member.getTarget());
            String memberName = TypeScriptUtils.sanitizePropertyName(symbolProvider.toMemberName(member));
            if (member.getMemberTrait(model, SensitiveTrait.class).isPresent()) {
                // member is Sensitive, hide the value
                writer.write("...(obj.${L} && { ${L}: SENSITIVE_STRING }),", memberName, memberName);
            } else if (memberShape instanceof StructureShape) {
                // Call filterSensitiveLog on Structure
                writer.write("...(obj.${L} && { ${L}: ${T}.filterSensitiveLog(obj.${L})}),",
                    memberName, memberName, symbolProvider.toSymbol(member), memberName);
            } else if (memberShape instanceof ListShape || memberShape instanceof SetShape) {
                MemberShape arrayMember = ((CollectionShape) memberShape).getMember();
                if (!(model.expectShape(arrayMember.getTarget()) instanceof SimpleShape)) {
                    // Iterate over array items, and call array specific function on each member
                    writer.openBlock("...(obj.${L} && { ${L}: obj.${L}.map(", ")}),",
                        memberName, memberName, memberName,
                        () -> {
                            writeFilterSensitiveLogForArray(writer, arrayMember);
                        });
                }
            }
        }
    }

    /**
     * Identifies if a member should be required on the generated interface.
     *
     * Members that are idempotency tokens should have their required state
     * relaxed so the token can be auto-filled for end users. From docs:
     *
     * "Client implementations MAY automatically provide a value for a request
     * token member if and only if the member is not explicitly provided."
     *
     * @param member The member being generated for.
     * @return If the interface member should be treated as required.
     *
     * @see <a href="https://awslabs.github.io/smithy/spec/core.html#idempotencytoken-trait">Smithy idempotencyToken trait.</a>
     */
    private boolean isRequiredMember(MemberShape member) {
        return member.isRequired() && !member.hasTrait(IdempotencyTokenTrait.class);
    }
}
