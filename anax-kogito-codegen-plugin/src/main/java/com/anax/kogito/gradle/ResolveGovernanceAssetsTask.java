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
import java.util.List;
import java.util.Optional;

/**
 * Parses .sw.json workflow definitions, extracts dmn:// and map:// URIs,
 * and fetches the referenced assets from the metadata server.
 *
 * Build fails if any referenced asset is missing.
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
                case "dmn" -> resolveDmn(client, uri, outputPath);
                case "map" -> resolveMap(client, uri, outputPath);
                case "anax" -> getLogger().lifecycle("  anax://{}/{} — local Spring bean (no fetch)",
                        uri.primary(), uri.secondary());
                default -> getLogger().warn("  Unknown scheme: {}", uri.scheme());
            }
        }
    }

    private void resolveDmn(MetadataServerClient client, UriParser.ParsedUri uri, Path outputDir) throws IOException {
        String namespace = uri.primary();
        String modelName = uri.secondary();
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
}
