package com.anax.kogito.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Gradle plugin that configures:
 * 1. resolveGovernanceAssets — fetches dmn:// and map:// assets from the
 * metadata server
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
            // kogito-codegen-manager includes CodeGenManagerUtil + all code generators
            p.getDependencies().add("kogitoCodegen",
                    "org.kie.kogito:kogito-codegen-manager:" + kogitoVersion);
            // Serverless workflow builder for FunctionTypeHandler SPI discovery
            p.getDependencies().add("kogitoCodegen",
                    "org.kie.kogito:kogito-serverless-workflow-builder");
            // SmallRye provides OASFactoryResolver needed by codegen at build time
            p.getDependencies().add("kogitoCodegen",
                    "io.smallrye:smallrye-open-api-core:3.10.0");
            // Codegen extensions with custom FunctionTypeHandler SPIs —
            // use project dependency when in the same build, GAV for external consumers
            Project extensionsProject = p.getRootProject()
                    .findProject(":anax-kogito-codegen-extensions");
            if (extensionsProject != null) {
                p.getDependencies().add("kogitoCodegen", extensionsProject);
            } else {
                p.getDependencies().add("kogitoCodegen",
                        "com.anax:anax-kogito-codegen-extensions:" + p.getVersion());
            }
        });

        // Generated output directories
        String genSrcDir = project.getLayout().getBuildDirectory()
                .dir("generated/sources/kogito").get().getAsFile().getAbsolutePath();
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

        // 2. generateKogitoSources task — reflective Kogito codegen invocation
        project.getTasks().register("generateKogitoSources", GenerateKogitoSourcesTask.class, task -> {
            task.setDescription("Run Kogito code generation via reflective invocation");
            task.setGroup("kogito");
            task.getKogitoVersion().set(extension.getKogitoVersion());
            // Codegen classpath = kogitoCodegen config + project runtimeClasspath
            task.getCodegenClasspath().from(kogitoCodegen);
            task.getCodegenClasspath().from(
                    project.getConfigurations().getByName("runtimeClasspath"));
            // Workflow input files (.sw.json and .dmn in src/main/resources)
            task.getWorkflowFiles().from(
                    project.fileTree("src/main/resources").matching(ft -> {
                        ft.include("**/*.sw.json", "**/*.dmn");
                    }));
            task.getOutputSourceDir().set(
                    project.getLayout().getBuildDirectory().dir("generated/sources/kogito"));
            task.getOutputResourceDir().set(
                    project.getLayout().getBuildDirectory().dir("generated/resources/kogito"));
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

        // Wire generated sources + resources into the main source set
        project.afterEvaluate(p -> {
            SourceSetContainer sourceSets = p.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.named("main", main -> {
                main.getJava().srcDir(genSrcDir);
                main.getResources().srcDir(genResDir);
            });
        });

        // Make compileJava depend on codegen, and processResources depend on all
        // generators
        project.getTasks().named("compileJava", task -> {
            task.dependsOn("generateKogitoSources");
        });
        project.getTasks().named("processResources", task -> {
            task.dependsOn("generateKogitoSources", "generateCatalogManifest", "generateMcpConfig");
        });
    }
}
