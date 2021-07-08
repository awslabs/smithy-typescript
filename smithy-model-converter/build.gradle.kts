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

extra["displayName"] = "Smithy :: Typescript :: Codegen :: Test"
extra["moduleName"] = "software.amazon.smithy.typescript.codegen.model.converter"

tasks["jar"].enabled = false

plugins {
    id("software.amazon.smithy").version("0.5.2")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":smithy-typescript-codegen"))
    implementation("software.amazon.smithy:smithy-aws-traits:[1.5.0, 2.0[")
    implementation("software.amazon.smithy:smithy-waiters:[1.5.0, 2.0[")
    implementation("software.amazon.smithy:smithy-protocol-test-traits:[1.5.0, 2.0[")
}

task("generateSmithyModelFromJson", JavaExec::class) {
    group = "convert"
    description = "Convert .json models from ./modelJson into .smithy models placed into ./modelSmithy"
    main = "SmithyModelGenerator"
    classpath = sourceSets["main"].runtimeClasspath
}
