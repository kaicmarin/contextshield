package com.contextshield.mlservice;

import com.contextshield.ml.SavedModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Carrega cada arquivo uma vez. O nome vem da core-api; caminho absoluto do cliente é recusado. */
@Component
public class ModelCatalog {
    private final Path directory;
    private final Map<String, SavedModel> loaded = new ConcurrentHashMap<>();

    public ModelCatalog(@Value("${contextshield.models-dir}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public SavedModel open(String fileName) {
        String safe = fileName(fileName);
        return loaded.computeIfAbsent(safe, name -> {
            Path file = directory.resolve(name).normalize();
            if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Modelo não encontrado.");
            }
            try {
                return SavedModel.load(file);
            } catch (IOException | ClassNotFoundException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível ler o modelo.");
            }
        });
    }

    public List<String> files() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".ser"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(names::add);
        }
        return names;
    }

    static String fileName(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("..") || raw.contains("/") || raw.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome de modelo inválido.");
        }
        return raw;
    }
}
