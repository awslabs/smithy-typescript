/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;


public class SmithyModelGenerator {

    File[] jsonModels;
    String outputFolder;

    SmithyModelGenerator(String inputLocation, String output) throws IOException {
        File folder = new File(inputLocation);
        jsonModels = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (jsonModels.length == 0) {
            throw new IOException("Did not file any *.json models at ./" + inputLocation);
        }
        outputFolder = output;
    }

    void run() throws IOException {

        for (File jsonModel: jsonModels) {
            String serviceName = jsonModel.getName().split("\\.")[0];
            Path path = Paths.get(jsonModel.getAbsolutePath());
            String content = Files.readString(path, StandardCharsets.UTF_8);

            ModelAssembler modelAssembler = Model.assembler().addUnparsedModel(jsonModel.getAbsolutePath(), content);
            modelAssembler = modelAssembler.discoverModels();
            Model model = modelAssembler.assemble().getResult().orElseThrow();
            SmithyIdlModelSerializer serializer = new SmithyIdlModelSerializer.Builder().build();

            Map<Path, String> serializedSmithyModels = serializer.serialize(model);

            File outputLocation = new File(outputFolder + "/" + serviceName);
            outputLocation.mkdirs();

            for (Map.Entry<Path, String> serialization : serializedSmithyModels.entrySet()) {
                String outputFile = outputLocation.getAbsolutePath() + "/" + serialization.getKey();
                FileWriter smithy = new FileWriter(outputFile);
                smithy.write(serialization.getValue());
                smithy.close();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        String inputModels = "modelJson";
        String outputModels = "modelSmithy";
        SmithyModelGenerator smithyModelGenerator = new SmithyModelGenerator(inputModels, outputModels);
        smithyModelGenerator.run();
    }
}
