package com.contextshield.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;

/** Ponto de entrada do treino. Grava modelos e o relatório no diretório do módulo. */
public final class TrainApp {
    private TrainApp() {}

    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 4000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 42L;
        Path directory = args.length > 2 ? Path.of(args[2]) : Path.of("machine-learning");
        if (!Files.isDirectory(directory)) directory = Path.of(".");
        MapWriter.write(Experiment.run(count, seed, directory), directory.resolve("reports").resolve("training-report.json"));
    }

    static final class MapWriter {
        static void write(Object report, Path path) throws Exception {
            Files.createDirectories(path.getParent());
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), report);
            System.out.println("Relatório: " + path.toAbsolutePath());
        }
    }
}
