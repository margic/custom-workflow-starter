package com.anax.kogito.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Generates .vscode/mcp.json to enable GitHub Copilot Agent Mode
 * to connect to the metadata server's MCP endpoint.
 *
 * Only writes if the file is absent — never overwrites existing config.
 * Skipped when metadataServerUrl is "stub".
 */
public abstract class GenerateMcpConfigTask extends DefaultTask {

    @Input
    public abstract Property<String> getMetadataServerUrl();

    @OutputFile
    public File getOutputFile() {
        return getProject().file(".vscode/mcp.json");
    }

    @TaskAction
    public void generate() throws IOException {
        String url = getMetadataServerUrl().get();
        if ("stub".equalsIgnoreCase(url)) {
            getLogger().lifecycle("Skipping MCP config generation (stub mode)");
            return;
        }

        File output = getOutputFile();
        if (output.exists()) {
            getLogger().lifecycle("Skipping MCP config generation ({} already exists)",
                    output.getPath());
            return;
        }

        String mcpUrl = url.endsWith("/")
                ? url + "mcp/sse"
                : url + "/mcp/sse";

        String content = "{\n"
                + "  \"servers\": {\n"
                + "    \"anax-metadata\": {\n"
                + "      \"type\": \"sse\",\n"
                + "      \"url\": \"" + mcpUrl + "\"\n"
                + "    }\n"
                + "  }\n"
                + "}\n";

        Files.createDirectories(output.getParentFile().toPath());
        Files.writeString(output.toPath(), content);
        getLogger().lifecycle("Generated {}", output.getPath());
    }
}
