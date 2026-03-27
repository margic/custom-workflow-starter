package com.anax.kogito.gradle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates META-INF/anax/catalog.json from resolved assets and workflow
 * definitions.
 *
 * Includes:
 * - Scheme definitions (dmn://, anax://, map://)
 * - Workflow inventory (from .sw.json files)
 * - DMN model entries (from resolved .dmn files)
 * - Function references extracted from workflows
 */
public abstract class CatalogManifestTask extends DefaultTask {

    private static final Pattern DMN_NAME = Pattern.compile("<definitions[^>]+name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern DMN_NAMESPACE = Pattern.compile("<definitions[^>]+namespace\\s*=\\s*\"([^\"]+)\"");

    @Input
    public abstract Property<String> getResourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void generate() throws IOException {
        Path resourcePath = Path.of(getResourceDir().get());
        Path outputPath = getOutputDir().get().getAsFile().toPath();
        Instant generatedAt = Instant.now();

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject catalog = new JsonObject();

        catalog.addProperty("schemaVersion", "2.0");
        catalog.addProperty("generatedAt", generatedAt.toString());
        catalog.add("assets", buildAssets(resourcePath, outputPath, generatedAt.toString()));

        Path catalogFile = outputPath.resolve("META-INF/anax/catalog.json");
        Files.createDirectories(catalogFile.getParent());
        Files.writeString(catalogFile, gson.toJson(catalog));
        getLogger().lifecycle("Generated {}", catalogFile);
    }

    private JsonArray buildAssets(Path resourceDir, Path outputDir, String resolvedAt) {
        Map<String, JsonObject> assets = new LinkedHashMap<>();
        scanDmnModels(resourceDir, assets, resolvedAt);
        scanDmnModels(outputDir, assets, resolvedAt);
        scanWorkflows(resourceDir, outputDir, assets, resolvedAt);

        JsonArray array = new JsonArray();
        assets.values().forEach(array::add);
        return array;
    }

    private void scanDmnModels(Path dir, Map<String, JsonObject> assets, String resolvedAt) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
            files.filter(path -> path.toString().endsWith(".dmn")).forEach(path -> {
                try {
                    String content = Files.readString(path);
                    String name = extractFirst(content, DMN_NAME);
                    String namespace = extractFirst(content, DMN_NAMESPACE);
                    if (name == null || namespace == null) {
                        getLogger().warn("Skipping DMN file without <definitions name|namespace>: {}", path);
                        return;
                    }
                    String uri = "dmn://" + namespace + "/" + name;
                    assets.put(assetKey("decision", uri), buildAsset(
                            uri,
                            slugify(name),
                            "decision",
                            sha256(path),
                            resolvedAt));
                } catch (IOException e) {
                    getLogger().warn("Failed to read DMN model {}", path, e);
                }
            });
        } catch (IOException e) {
            getLogger().warn("Failed to scan DMN models in {}", dir, e);
        }
    }

    private void scanWorkflows(Path resourceDir, Path outputDir, Map<String, JsonObject> assets, String resolvedAt) {
        if (!Files.isDirectory(resourceDir)) {
            return;
        }
        try (var files = Files.walk(resourceDir)) {
            files.filter(p -> p.toString().endsWith(".sw.json")).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    Gson gson = new Gson();
                    com.google.gson.JsonObject root = gson.fromJson(content, com.google.gson.JsonObject.class);

                    String workflowId = getJsonString(root, "id");
                    if (workflowId != null && !workflowId.isBlank()) {
                        String workflowUri = "workflow://" + workflowId;
                        assets.put(assetKey("workflow", workflowUri), buildAsset(
                                workflowUri,
                                workflowId,
                                "workflow",
                                sha256(p),
                                resolvedAt));
                    }

                    com.google.gson.JsonArray fns = root.getAsJsonArray("functions");
                    if (fns != null) {
                        for (var el : fns) {
                            com.google.gson.JsonObject fnObj = el.getAsJsonObject();
                            String operation = getJsonString(fnObj, "operation");
                            if (operation == null || operation.isBlank()) {
                                continue;
                            }
                            addFunctionAsset(operation, outputDir, resourceDir, assets, resolvedAt);
                        }
                    }
                } catch (IOException e) {
                    getLogger().warn("Failed to read workflow file: {}", p);
                }
            });
        } catch (IOException e) {
            getLogger().warn("Failed to scan workflows in {}", resourceDir);
        }
    }

    private String getJsonString(com.google.gson.JsonObject obj, String key) {
        var el = obj.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : null;
    }

    private void addFunctionAsset(String operation, Path outputDir, Path resourceDir,
            Map<String, JsonObject> assets, String resolvedAt) {
        try {
            UriParser.ParsedUri parsed = UriParser.parse(operation);
            switch (parsed.scheme()) {
                case "dmn" -> assets.put(assetKey("decision", operation), buildAsset(
                        operation,
                        slugify(parsed.secondary()),
                        "decision",
                        resolveDmnChecksum(parsed, outputDir, resourceDir),
                        resolvedAt));
                case "map" -> assets.put(assetKey("mapping", operation), buildAsset(
                        operation,
                        parsed.primary(),
                        "mapping",
                        resolveMappingChecksum(parsed.primary(), outputDir, resourceDir),
                        resolvedAt));
                case "anax" -> assets.put(assetKey("function", operation), buildAsset(
                        operation,
                        parsed.primary() + "/" + parsed.secondary(),
                        "function",
                        null,
                        null));
                default -> {
                }
            }
        } catch (IllegalArgumentException e) {
            getLogger().warn("Skipping unrecognized custom operation in catalog generation: {}", operation);
        }
    }

    private String resolveDmnChecksum(UriParser.ParsedUri parsed, Path outputDir, Path resourceDir) {
        Path outputMatch = findDmnByName(outputDir, parsed.secondary());
        if (outputMatch != null) {
            return sha256(outputMatch);
        }
        Path resourceMatch = findDmnByName(resourceDir, parsed.secondary());
        if (resourceMatch != null) {
            return sha256(resourceMatch);
        }
        getLogger().warn("No DMN file found for {} while generating catalog checksum", parsed);
        return null;
    }

    private String resolveMappingChecksum(String mappingName, Path outputDir, Path resourceDir) {
        Path outputFile = outputDir.resolve("META-INF/anax/mappings/" + mappingName + ".json");
        if (Files.exists(outputFile)) {
            return sha256(outputFile);
        }
        Path resourceFile = resourceDir.resolve("META-INF/anax/mappings/" + mappingName + ".json");
        if (Files.exists(resourceFile)) {
            return sha256(resourceFile);
        }
        getLogger().warn("No mapping file found for map://{} while generating catalog checksum", mappingName);
        return null;
    }

    private Path findDmnByName(Path dir, String modelName) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var files = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
            return files
                    .filter(path -> path.toString().endsWith(".dmn"))
                    .filter(path -> hasDmnName(path, modelName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            getLogger().warn("Failed to scan DMN files in {}", dir, e);
            return null;
        }
    }

    private boolean hasDmnName(Path path, String expectedName) {
        try {
            String content = Files.readString(path);
            String actualName = extractFirst(content, DMN_NAME);
            return expectedName.equals(actualName);
        } catch (IOException e) {
            getLogger().warn("Failed to read DMN model {}", path, e);
            return false;
        }
    }

    private JsonObject buildAsset(String uri, String assetId, String assetType, String checksum, String resolvedAt) {
        JsonObject asset = new JsonObject();
        asset.addProperty("uri", uri);
        asset.addProperty("assetId", assetId);
        asset.addProperty("assetType", assetType);
        if (checksum == null) {
            asset.add("checksum", null);
        } else {
            asset.addProperty("checksum", checksum);
        }
        if (resolvedAt == null) {
            asset.add("resolvedAt", null);
        } else {
            asset.addProperty("resolvedAt", resolvedAt);
        }
        return asset;
    }

    private String assetKey(String assetType, String uri) {
        return assetType + ":" + uri;
    }

    private String extractFirst(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String slugify(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            byte[] hash = digest.digest(bytes);
            StringBuilder result = new StringBuilder("sha256:");
            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file for checksum: " + path, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
