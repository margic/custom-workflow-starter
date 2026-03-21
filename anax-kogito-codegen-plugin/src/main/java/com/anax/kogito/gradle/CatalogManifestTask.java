package com.anax.kogito.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle task that generates a metadata catalog for Copilot/AI discovery.
 *
 * <p>Scans the project for:
 * <ul>
 *   <li>*.dmn files → extracts namespace, model name, inputs, outputs</li>
 *   <li>*.sw.json files → extracts workflow ID, name, events, function references</li>
 *   <li>*.java files → extracts @Component/@Service beans with Map-accepting methods</li>
 *   <li>Registered URI schemes → dmn://, anax://, map://</li>
 * </ul>
 *
 * <p>Output: {@code build/generated/resources/kogito/META-INF/anax/catalog.json}
 *
 * <p>This catalog enables GitHub Copilot to generate valid workflow definitions
 * by discovering available operations without manual lookup.
 */
public abstract class CatalogManifestTask extends DefaultTask {

    /**
     * Input files: all *.dmn, *.sw.json, and *.java files in src/main.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getInputFiles() {
        FileTree resources = getProject().fileTree("src/main/resources", tree -> {
            tree.include("**/*.dmn");
            tree.include("**/*.sw.json");
        });

        FileTree java = getProject().fileTree("src/main/java", tree ->
            tree.include("**/*.java"));

        FileTree generatedJava = getProject().fileTree(
            getProject().getLayout().getBuildDirectory().dir("generated/sources/kogito"),
            tree -> tree.include("**/*.java"));

        return resources.plus(java).plus(generatedJava);
    }

    /**
     * Output directory: build/generated/resources/kogito
     * The catalog.json will be written to META-INF/anax/catalog.json within this directory.
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    public CatalogManifestTask() {
        getOutputDir().convention(
            getProject().getLayout().getBuildDirectory().dir("generated/resources/kogito"));
    }

    @TaskAction
    public void generateCatalog() throws IOException {
        File outputDir = getOutputDir().get().getAsFile();
        File catalogFile = new File(outputDir, "META-INF/anax/catalog.json");

        catalogFile.getParentFile().mkdirs();

        getLogger().lifecycle("Generating catalog manifest...");
        getLogger().lifecycle("  Output: {}", catalogFile);

        // Build catalog structure
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("schemaVersion", "1.0");
        catalog.put("generatedAt", Instant.now().toString());
        catalog.put("schemes", buildSchemeDefinitions());
        catalog.put("dmnModels", scanDmnModels());
        catalog.put("workflows", scanWorkflows());
        catalog.put("springBeans", scanSpringBeans());
        catalog.put("formSchemas", List.of()); // Placeholder for future extension

        // Write JSON
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(catalogFile, catalog);

        getLogger().lifecycle("Catalog manifest generated: {} DMN models, {} workflows, {} beans",
            ((List<?>) catalog.get("dmnModels")).size(),
            ((List<?>) catalog.get("workflows")).size(),
            ((List<?>) catalog.get("springBeans")).size());
    }

    /**
     * Defines the three custom URI schemes: dmn://, anax://, map://.
     */
    private List<Map<String, Object>> buildSchemeDefinitions() {
        List<Map<String, Object>> schemes = new ArrayList<>();

        // dmn:// scheme
        schemes.add(Map.of(
            "scheme", "dmn",
            "description", "Evaluate a DMN decision model in-process",
            "uriPattern", "dmn://{namespace}/{modelName}",
            "parameters", List.of(
                Map.of("name", "DmnNamespace", "description", "DMN model namespace", "source", "uri-segment-1"),
                Map.of("name", "ModelName", "description", "DMN model name", "source", "uri-segment-2")
            ),
            "handler", "com.anax.kogito.autoconfigure.DmnWorkItemHandler"
        ));

        // anax:// scheme
        schemes.add(Map.of(
            "scheme", "anax",
            "description", "Invoke a method on a Spring bean in the application context",
            "uriPattern", "anax://{beanName}/{methodName}",
            "parameters", List.of(
                Map.of("name", "BeanName", "description", "Spring bean name", "source", "uri-segment-1"),
                Map.of("name", "MethodName", "description", "Method to invoke (default: execute)", "source", "uri-segment-2")
            ),
            "handler", "com.anax.kogito.autoconfigure.AnaxWorkItemHandler"
        ));

        // map:// scheme
        schemes.add(Map.of(
            "scheme", "map",
            "description", "Apply a named data-mapping transformation",
            "uriPattern", "map://{mappingName}",
            "parameters", List.of(
                Map.of("name", "MappingName", "description", "Registered mapping identifier", "source", "uri-segment-1")
            ),
            "handler", "com.anax.kogito.autoconfigure.MapWorkItemHandler"
        ));

        return schemes;
    }

    /**
     * Scans *.dmn files and extracts namespace, model name, inputs, outputs.
     */
    private List<Map<String, Object>> scanDmnModels() {
        List<Map<String, Object>> models = new ArrayList<>();

        getProject().fileTree("src/main/resources", tree -> tree.include("**/*.dmn"))
            .forEach(file -> {
                try {
                    String content = Files.readString(file.toPath());

                    // Extract namespace: <definitions ... namespace="com.anax.decisions">
                    String namespace = extractAttribute(content, "definitions", "namespace");

                    // Extract model name: <definitions ... name="Order Type Routing">
                    String modelName = extractAttribute(content, "definitions", "name");

                    if (namespace != null && modelName != null) {
                        models.add(Map.of(
                            "namespace", namespace,
                            "modelName", modelName,
                            "uri", "dmn://" + namespace + "/" + modelName,
                            "file", file.getName(),
                            "inputs", extractDmnInputs(content),
                            "outputs", extractDmnOutputs(content)
                        ));
                    }
                } catch (IOException e) {
                    getLogger().warn("Failed to parse DMN file: {}", file, e);
                }
            });

        return models;
    }

    /**
     * Scans *.sw.json files and extracts workflow metadata.
     */
    private List<Map<String, Object>> scanWorkflows() {
        List<Map<String, Object>> workflows = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        getProject().fileTree("src/main/resources", tree -> tree.include("**/*.sw.json"))
            .forEach(file -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> workflow = mapper.readValue(file, Map.class);

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", workflow.get("id"));
                    entry.put("name", workflow.get("name"));
                    entry.put("version", workflow.getOrDefault("version", "1.0"));
                    entry.put("file", file.getName());

                    // Extract events
                    if (workflow.containsKey("events")) {
                        entry.put("events", workflow.get("events"));
                    }

                    // Extract function references
                    if (workflow.containsKey("functions")) {
                        entry.put("functions", workflow.get("functions"));
                    }

                    workflows.add(entry);
                } catch (IOException e) {
                    getLogger().warn("Failed to parse workflow file: {}", file, e);
                }
            });

        return workflows;
    }

    /**
     * Scans *.java files for @Component/@Service beans with Map-accepting methods.
     */
    private List<Map<String, Object>> scanSpringBeans() {
        List<Map<String, Object>> beans = new ArrayList<>();

        // Pattern to match @Component("beanName") or @Service("beanName")
        Pattern componentPattern = Pattern.compile(
            "@(?:Component|Service)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

        // Pattern to match public Map<String,Object> methodName(Map<String,Object> ...)
        Pattern methodPattern = Pattern.compile(
            "public\\s+Map<String,\\s*Object>\\s+(\\w+)\\s*\\(\\s*Map<String,\\s*Object>");

        getProject().fileTree("src/main/java", tree -> tree.include("**/*.java"))
            .forEach(file -> {
                try {
                    String content = Files.readString(file.toPath());

                    Matcher componentMatcher = componentPattern.matcher(content);
                    if (componentMatcher.find()) {
                        String beanName = componentMatcher.group(1);

                        List<String> methods = new ArrayList<>();
                        Matcher methodMatcher = methodPattern.matcher(content);
                        while (methodMatcher.find()) {
                            methods.add(methodMatcher.group(1));
                        }

                        if (!methods.isEmpty()) {
                            Map<String, Object> bean = new LinkedHashMap<>();
                            bean.put("beanName", beanName);
                            bean.put("file", file.getName());
                            bean.put("methods", methods);

                            // Generate URIs for each method
                            List<String> uris = new ArrayList<>();
                            for (String method : methods) {
                                uris.add("anax://" + beanName + "/" + method);
                            }
                            bean.put("uris", uris);

                            beans.add(bean);
                        }
                    }
                } catch (IOException e) {
                    getLogger().warn("Failed to scan Java file: {}", file, e);
                }
            });

        return beans;
    }

    /**
     * Extracts DMN input variable names from inputData definitions.
     */
    private List<String> extractDmnInputs(String dmnXml) {
        List<String> inputs = new ArrayList<>();
        Pattern pattern = Pattern.compile("<inputData[^>]*\\s+name=\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(dmnXml);
        while (matcher.find()) {
            inputs.add(matcher.group(1));
        }
        return inputs;
    }

    /**
     * Extracts DMN output variable names from decision definitions.
     */
    private List<String> extractDmnOutputs(String dmnXml) {
        List<String> outputs = new ArrayList<>();
        Pattern pattern = Pattern.compile("<decision[^>]*\\s+name=\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(dmnXml);
        while (matcher.find()) {
            outputs.add(matcher.group(1));
        }
        return outputs;
    }

    /**
     * Extracts an XML attribute value.
     * Example: extractAttribute(xml, "definitions", "namespace") → "com.anax.decisions"
     */
    private String extractAttribute(String xml, String elementName, String attributeName) {
        Pattern pattern = Pattern.compile(
            "<" + elementName + "[^>]*\\s+" + attributeName + "=\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }
}
