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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Generates META-INF/anax/catalog.json from resolved assets and workflow definitions.
 *
 * Includes:
 * - Scheme definitions (dmn://, anax://, map://)
 * - Workflow inventory (from .sw.json files)
 * - DMN model entries (from resolved .dmn files)
 * - Function references extracted from workflows
 */
public abstract class CatalogManifestTask extends DefaultTask {

    @Input
    public abstract Property<String> getResourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void generate() throws IOException {
        Path resourcePath = Path.of(getResourceDir().get());
        Path outputPath = getOutputDir().get().getAsFile().toPath();

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject catalog = new JsonObject();

        catalog.addProperty("schemaVersion", "1.0");
        catalog.addProperty("generatedAt", Instant.now().toString());

        // Scheme definitions
        catalog.add("schemes", buildSchemeDefinitions());

        // DMN models from resolved assets
        catalog.add("dmnModels", scanDmnModels(outputPath));

        // Workflows from .sw.json files
        catalog.add("workflows", scanWorkflows(resourcePath));

        // Spring beans (static placeholder — augmented at runtime by AnaxCatalogService)
        catalog.add("springBeans", new JsonArray());

        // Form schemas (placeholder for future)
        catalog.add("formSchemas", new JsonArray());

        Path catalogFile = outputPath.resolve("META-INF/anax/catalog.json");
        Files.createDirectories(catalogFile.getParent());
        Files.writeString(catalogFile, gson.toJson(catalog));
        getLogger().lifecycle("Generated {}", catalogFile);
    }

    private JsonArray buildSchemeDefinitions() {
        JsonArray schemes = new JsonArray();

        schemes.add(buildScheme("dmn", "Evaluate a DMN decision model in-process",
                "dmn://{namespace}/{modelName}", "DmnWorkItemHandler",
                new String[]{"DmnNamespace", "DMN model namespace", "uri"},
                new String[]{"ModelName", "DMN model name", "uri"}));

        schemes.add(buildScheme("anax", "Invoke a Spring bean method",
                "anax://{beanName}/{methodName}", "AnaxWorkItemHandler",
                new String[]{"BeanName", "Spring bean name", "uri"},
                new String[]{"MethodName", "Method to invoke (default: execute)", "uri"}));

        schemes.add(buildScheme("map", "Apply a Jolt data transformation",
                "map://{mappingName}", "MapWorkItemHandler",
                new String[]{"MappingName", "Jolt mapping spec name", "uri"}));

        return schemes;
    }

    private JsonObject buildScheme(String scheme, String description, String uriPattern,
                                   String handler, String[]... params) {
        JsonObject obj = new JsonObject();
        obj.addProperty("scheme", scheme);
        obj.addProperty("description", description);
        obj.addProperty("uriPattern", uriPattern);
        obj.addProperty("handler", handler);

        JsonArray parameters = new JsonArray();
        for (String[] p : params) {
            JsonObject param = new JsonObject();
            param.addProperty("name", p[0]);
            param.addProperty("description", p[1]);
            param.addProperty("source", p[2]);
            parameters.add(param);
        }
        obj.add("parameters", parameters);
        return obj;
    }

    private JsonArray scanDmnModels(Path outputDir) {
        JsonArray models = new JsonArray();
        Path kogitoDir = outputDir;
        if (!Files.isDirectory(kogitoDir)) {
            return models;
        }
        try (var files = Files.list(kogitoDir)) {
            files.filter(p -> p.toString().endsWith(".dmn")).forEach(p -> {
                JsonObject model = new JsonObject();
                String filename = p.getFileName().toString();
                model.addProperty("name", filename.replace(".dmn", ""));
                model.addProperty("resource", filename);
                model.addProperty("uri", "dmn://" + filename.replace(".dmn", ""));
                model.add("inputs", new JsonArray());
                model.add("outputs", new JsonArray());
                models.add(model);
            });
        } catch (IOException e) {
            getLogger().warn("Failed to scan DMN models in {}", kogitoDir);
        }
        return models;
    }

    private JsonArray scanWorkflows(Path resourceDir) {
        JsonArray workflows = new JsonArray();
        if (!Files.isDirectory(resourceDir)) {
            return workflows;
        }
        try (var files = Files.walk(resourceDir)) {
            files.filter(p -> p.toString().endsWith(".sw.json")).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    Gson gson = new Gson();
                    com.google.gson.JsonObject root = gson.fromJson(content, com.google.gson.JsonObject.class);

                    JsonObject wf = new JsonObject();
                    wf.addProperty("id", getJsonString(root, "id"));
                    wf.addProperty("name", getJsonString(root, "name"));
                    wf.addProperty("resource", resourceDir.relativize(p).toString());

                    // Functions
                    JsonArray functions = new JsonArray();
                    com.google.gson.JsonArray fns = root.getAsJsonArray("functions");
                    if (fns != null) {
                        for (var el : fns) {
                            JsonObject fn = new JsonObject();
                            com.google.gson.JsonObject fnObj = el.getAsJsonObject();
                            fn.addProperty("name", getJsonString(fnObj, "name"));
                            fn.addProperty("operation", getJsonString(fnObj, "operation"));
                            functions.add(fn);
                        }
                    }
                    wf.add("functions", functions);

                    // Events
                    JsonArray events = new JsonArray();
                    com.google.gson.JsonArray evts = root.getAsJsonArray("events");
                    if (evts != null) {
                        for (var el : evts) {
                            JsonObject evt = new JsonObject();
                            com.google.gson.JsonObject evtObj = el.getAsJsonObject();
                            evt.addProperty("name", getJsonString(evtObj, "name"));
                            evt.addProperty("type", getJsonString(evtObj, "type"));
                            evt.addProperty("kind", getJsonString(evtObj, "kind"));
                            events.add(evt);
                        }
                    }
                    wf.add("events", events);

                    workflows.add(wf);
                } catch (IOException e) {
                    getLogger().warn("Failed to read workflow file: {}", p);
                }
            });
        } catch (IOException e) {
            getLogger().warn("Failed to scan workflows in {}", resourceDir);
        }
        return workflows;
    }

    private String getJsonString(com.google.gson.JsonObject obj, String key) {
        var el = obj.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : null;
    }
}
