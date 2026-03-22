package com.anax.kogito.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads .sw.json workflow files and extracts custom function operation URIs.
 */
public final class SwJsonParser {

    private SwJsonParser() {}

    /**
     * Scan a directory for .sw.json files and extract all custom function operations.
     */
    public static List<String> extractCustomOperations(Path resourceDir) throws IOException {
        if (!Files.isDirectory(resourceDir)) {
            return Collections.emptyList();
        }

        List<String> operations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(resourceDir)) {
            files.filter(p -> p.toString().endsWith(".sw.json"))
                    .forEach(p -> operations.addAll(extractFromFile(p)));
        }
        return operations;
    }

    /**
     * Extract custom function operations from a single .sw.json file.
     */
    static List<String> extractFromFile(Path swJsonFile) {
        try {
            String content = Files.readString(swJsonFile);
            return extractFromJson(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read workflow file: " + swJsonFile, e);
        }
    }

    /**
     * Extract custom function operations from a sw.json JSON string.
     */
    public static List<String> extractFromJson(String json) {
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(json, JsonObject.class);
        JsonArray functions = root.getAsJsonArray("functions");
        if (functions == null) {
            return Collections.emptyList();
        }

        List<String> operations = new ArrayList<>();
        for (JsonElement el : functions) {
            JsonObject fn = el.getAsJsonObject();
            JsonElement typeEl = fn.get("type");
            if (typeEl == null || !"custom".equals(typeEl.getAsString())) {
                continue;
            }
            JsonElement opEl = fn.get("operation");
            if (opEl != null && !opEl.isJsonNull()) {
                operations.add(opEl.getAsString());
            }
        }
        return operations;
    }
}
