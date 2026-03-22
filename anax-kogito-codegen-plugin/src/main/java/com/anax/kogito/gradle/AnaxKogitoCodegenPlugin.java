package com.anax.kogito.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Gradle plugin that configures:
 * 1. resolveGovernanceAssets — fetches dmn:// and map:// assets from the metadata server
 * 2. generateKogitoSources — reflective Kogito codegen invocation
 * 3. generateCatalogManifest — builds catalog.json from resolved assets
 * 4. generateMcpConfig — writes .vscode/mcp.json for Copilot Agent Mode
 *
 * Consumers apply this plugin and configure the anaxKogito extension.
 */
public class AnaxKogitoCodegenPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");

        AnaxKogitoExtension extension = project.getExtensions()
                .create("anaxKogito", AnaxKogitoExtension.class);

        // Default metadataServerUrl from env or 'stub'
        extension.getMetadataServerUrl().convention(
                project.provider(() -> {
                    String envUrl = System.getenv("METADATA_SERVER_URL");
                    return envUrl != null ? envUrl : "stub";
                }));

        // Default kogitoVersion from gradle.properties
        extension.getKogitoVersion().convention(
                project.provider(() -> {
                    Object v = project.findProperty("kogitoVersion");
                    return v != null ? v.toString() : "10.1.0";
                }));

        // Create kogitoCodegen configuration for build-time codegen dependencies
        Configuration kogitoCodegen = project.getConfigurations()
                .create("kogitoCodegen", conf -> {
                    conf.setDescription("Kogito codegen engine dependencies (build-time only)");
                    conf.setCanBeConsumed(false);
                    conf.setCanBeResolved(true);
                });

        // Wire up Kogito BOM and codegen dependencies lazily
        project.afterEvaluate(p -> {
            String kogitoVersion = extension.getKogitoVersion().get();

            p.getDependencies().add("kogitoCodegen",
                    p.getDependencies().platform(
                            "org.kie.kogito:kogito-bom:" + kogitoVersion));
            p.getDependencies().add("kogitoCodegen",
                    "org.kie.kogito:kogito-codegen-processes");
            p.getDependencies().add("kogitoCodegen",
                    "org.kie.kogito:kogito-serverless-workflow-builder");
            p.getDependencies().add("kogitoCodegen",
                    "org.kie.kogito:kogito-addons-quarkus-serverless-workflow-utils");

            // Add codegen-extensions as a codegen dependency so SPI handlers are discovered
            p.getDependencies().add("kogitoCodegen",
                    "com.anax:anax-kogito-codegen-extensions:" + p.getVersion());
        });

        // Generated resources output directory
        String genResDir = project.getLayout().getBuildDirectory()
                .dir("generated/resources/kogito").get().getAsFile().getAbsolutePath();

        // 1. resolveGovernanceAssets task
        project.getTasks().register("resolveGovernanceAssets", ResolveGovernanceAssetsTask.class, task -> {
            task.setDescription("Fetch governance assets (DMN, Jolt specs) from the metadata server");
            task.setGroup("kogito");
            task.getMetadataServerUrl().set(extension.getMetadataServerUrl());
            task.getResourceDir().set(
                    project.provider(() -> project.file("src/main/resources").getAbsolutePath()));
            task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("generated/resources/kogito"));
        });

        // 2. generateKogitoSources task (reflective invocation — placeholder for now)
        project.getTasks().register("generateKogitoSources", GenerateKogitoSourcesTask.class, task -> {
            task.setDescription("Run Kogito code generation via reflective invocation");
            task.setGroup("kogito");
            task.getKogitoVersion().set(extension.getKogitoVersion());
            task.dependsOn("resolveGovernanceAssets");
        });

        // 3. generateCatalogManifest task
        project.getTasks().register("generateCatalogManifest", CatalogManifestTask.class, task -> {
            task.setDescription("Generate META-INF/anax/catalog.json from resolved assets");
            task.setGroup("kogito");
            task.getResourceDir().set(
                    project.provider(() -> project.file("src/main/resources").getAbsolutePath()));
            task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("generated/resources/kogito"));
            task.dependsOn("resolveGovernanceAssets");
        });

        // 4. generateMcpConfig task
        project.getTasks().register("generateMcpConfig", GenerateMcpConfigTask.class, task -> {
            task.setDescription("Generate .vscode/mcp.json for Copilot Agent Mode");
            task.setGroup("kogito");
            task.getMetadataServerUrl().set(extension.getMetadataServerUrl());
            task.dependsOn("resolveGovernanceAssets");
        });

        // Wire generated resources into the main source set
        project.afterEvaluate(p -> {
            SourceSetContainer sourceSets = p.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.named("main", main ->
                    main.getResources().srcDir(genResDir));
        });

        // Make compileJava depend on codegen, and processResources depend on catalog
        project.getTasks().named("compileJava", task -> {
            task.dependsOn("generateKogitoSources");
        });
        project.getTasks().named("processResources", task -> {
            task.dependsOn("generateCatalogManifest", "generateMcpConfig");
        });
    }
}
