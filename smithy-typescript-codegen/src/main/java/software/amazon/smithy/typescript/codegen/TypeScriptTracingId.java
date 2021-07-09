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

import java.util.Objects;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Class that represents ShapeLink ids for TypeScript. IDs are similar to Java naming conventions:
 * packageName.filename.ClassName#methodName or packageName.filename.ClassName$fieldName. ClassName
 * is the name of an interface, type, class or namespace that is exported in a TypeScript file.
 */
public class TypeScriptTracingId implements ToSmithyBuilder<TypeScriptTracingId> {
    private String packageName;
    private String methodName;
    private String fieldName;

    public TypeScriptTracingId(Builder builder) {
        packageName = builder.packageName;
        methodName = builder.methodName;
        fieldName = builder.fieldName;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses TypeScriptTracingId from a string.
     * @param tracingId Id as a string to parse.
     * @return TypeScriptTracingId representation of the string.
     */
    public static TypeScriptTracingId fromString(String tracingId) {
        String fieldName = null;
        String methodName = null;
        String packageName;

        String[] fieldSplit = tracingId.split("\\$");
        String[] methodSplit = tracingId.split("#");

        if (fieldSplit.length > 1) {
            fieldName = fieldSplit[1];
            packageName = fieldSplit[0];
        } else if (methodSplit.length > 1) {
            methodName = methodSplit[1];
            packageName = methodSplit[0];
        } else {
            packageName = tracingId;
        }

        return builder().packageName(packageName).fieldName(fieldName).methodName(methodName).build();
    }

    public String toString() {
        StringBuilder builder = new StringBuilder()
                .append(packageName);

        if (Objects.nonNull(methodName)) {
            builder.append("#").append(methodName);
        } else if (Objects.nonNull(fieldName)) {
            builder.append("$").append(fieldName);
        }

        return builder.toString();
    }

    public String getPackageName() {
        return packageName;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public Builder toBuilder() {
        return builder()
                .packageName(packageName)
                .methodName(methodName);
    }

    /**
     * Builder for TypeScriptTracingId.
     */
    public static final class Builder implements SmithyBuilder<TypeScriptTracingId> {
        private String packageName = "";
        private String methodName;
        private String fieldName;

        public TypeScriptTracingId build() {
            return new TypeScriptTracingId(this);
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder fieldName(String fieldName) {
            this.fieldName = fieldName;
            return this;
        }

        public Builder appendToPackageName(String packageAddition) {
            if (this.packageName.length() == 0) {
                this.packageName = packageAddition;
            } else {
                this.packageName += "." + packageAddition;
            }
            return this;
        }
    }
}
