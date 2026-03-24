package com.anax.kogito.gradle;

import com.anax.kogito.gradle.metadata.DecisionSearchResult;
import com.anax.kogito.gradle.metadata.MetadataServerClient;
import com.anax.kogito.gradle.metadata.MetadataServerClientFactory;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses .sw.json workflow definitions, extracts dmn:// and map:// URIs,
 * and fetches the referenced assets from the metadata server.
 *
 * If a local .dmn file in src/main/resources/ has a matching model name,
 * the server fetch is skipped and the local file is used instead.
 *
 * Build fails if any referenced asset is missing (and no local override exists).
 */
public abstract class ResolveGovernanceAssetsTask extends DefaultTask {

    @Input
    public abstract Property<String> getMetadataServerUrl();

    @Input
    public abstract Property<String> getResourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void resolve() throws IOException {
        String metadataUrl = getMetadataServerUrl().get();
        Path resourcePath = Path.of(getResourceDir().get());
        Path outputPath = getOutputDir().get().getAsFile().toPath();

        // Pre-scan local .dmn files — local files override server fetch
        Map<String, Path> localDmnByName = scanLocalDmnFiles(resourcePath);
        if (!localDmnByName.isEmpty()) {
            getLogger().lifecycle("Found {} local DMN file(s): {}", localDmnByName.size(), localDmnByName.keySet());
        }

        MetadataServerClient client = MetadataServerClientFactory.create(metadataUrl);

        List<String> operations = SwJsonParser.extractCustomOperations(resourcePath);
        if (operations.isEmpty()) {
            getLogger().lifecycle("No custom function operations found in .sw.json files");
            return;
        }

        getLogger().lifecycle("Found {} custom function operation(s)", operations.size());

        for (String operation : operations) {
            UriParser.ParsedUri uri;
            try {
                uri = UriParser.parse(operation);
            } catch (IllegalArgumentException e) {
                getLogger().warn("Skipping unrecognized operation: {}", operation);
                continue;
            }

            switch (uri.scheme()) {
                case "dmn" -> resolveDmn(client, uri, outputPath, localDmnByName);
                case "map" -> resolveMap(client, uri, outputPath);
                case "anax" -> getLogger().lifecycle("  anax://{}/{} — local Spring bean (no fetch)",
                        uri.primary(), uri.secondary());
                default -> getLogger().warn("  Unknown scheme: {}", uri.scheme());
            }
        }
    }

    private void resolveDmn(MetadataServerClient client, UriParser.ParsedUri uri, Path outputDir,
            Map<String, Path> localDmnByName) throws IOException {
        String namespace = uri.primary();
        String modelName = uri.secondary();

        // Check for local override before making any server call
        Path localFile = localDmnByName.get(modelName);
        if (localFile != null) {
            getLogger().lifecycle("  dmn://{}/{} — using local file {} (skipping server fetch)",
                    namespace, modelName, localFile);
            return;
        }

        getLogger().lifecycle("  Resolving dmn://{}/{}", namespace, modelName);

        List<DecisionSearchResult> results = client.findDecisions(namespace, modelName);
        if (results.isEmpty()) {
            throw new GradleException(
                    "BUILD FAILED: No active decision found for dmn://" + namespace + "/" + modelName
                            + " on metadata server");
        }
        if (results.size() > 1) {
            throw new GradleException(
                    "BUILD FAILED: Ambiguous — multiple active decisions match dmn://" + namespace + "/"
                            + modelName);
        }

        DecisionSearchResult decision = results.get(0);
        Optional<byte[]> dmnXml = client.exportDecisionDmn(decision.decisionId());
        if (dmnXml.isEmpty()) {
            throw new GradleException(
                    "BUILD FAILED: Decision '" + decision.decisionId()
                            + "' exists but has no DMN export (decision may have been authored in UI without uploading a .dmn file)");
        }

        Path dmnFile = outputDir.resolve(decision.decisionId() + ".dmn");
        Files.createDirectories(dmnFile.getParent());
        Files.write(dmnFile, dmnXml.get());
        getLogger().lifecycle("    → wrote {}", dmnFile);
    }

    private void resolveMap(MetadataServerClient client, UriParser.ParsedUri uri, Path outputDir) throws IOException {
        String mappingName = uri.primary();
        getLogger().lifecycle("  Resolving map://{}", mappingName);

        Optional<byte[]> joltSpec = client.exportMappingJolt(mappingName);
        if (joltSpec.isEmpty()) {
            throw new GradleException(
                    "BUILD FAILED: Mapping asset not found for map://" + mappingName
                            + " on metadata server");
        }

        Path mappingFile = outputDir.resolve("META-INF/anax/mappings/" + mappingName + ".json");
        Files.createDirectories(mappingFile.getParent());
        Files.write(mappingFile, joltSpec.get());
        getLogger().lifecycle("    → wrote {}", mappingFile);
    }

    private static final Pattern DEFINITIONS_NAME = Pattern.compile(
            "<definitions[^>]+name\\s*=\\s*\"([^\"]+)\"");

    /**
     * Scan src/main/resources for .dmn files and extract the definitions name attribute.
     * Returns a map of DMN model name → local file path.
     */
    private Map<String, Path> scanLocalDmnFiles(Path resourceDir) throws IOException {
        Map<String, Path> result = new HashMap<>();
        if (!Files.isDirectory(resourceDir)) {
            return result;
        }
        try (Stream<Path> files = Files.walk(resourceDir)) {
            files.filter(p -> p.toString().endsWith(".dmn"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            Matcher m = DEFINITIONS_NAME.matcher(content);
                            if (m.find()) {
                                result.put(m.group(1), p);
                            }
                        } catch (IOException e) {
                            getLogger().warn("Failed to read local DMN file: {}", p);
                        }
                    });
        }
        return result;
    }
}
