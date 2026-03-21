package com.anax.kogito.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;

/**
 * Gradle plugin for Anax Kogito Spring Boot Starter.
 *
 * <p>This plugin automates:
 * <ul>
 *   <li>Kogito code generation from *.sw.json and *.dmn files</li>
 *   <li>Catalog manifest generation (META-INF/anax/catalog.json)</li>
 *   <li>Source set wiring for generated sources and resources</li>
 *   <li>Dependency management for kogito-codegen-manager and extensions</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * plugins {
 *     id 'com.anax.kogito-codegen' version '0.1.0'
 * }
 * }</pre>
 *
 * <p>The plugin creates a 'kogitoCodegen' configuration for isolating
 * codegen-time dependencies and registers two tasks:
 * <ul>
 *   <li>{@code generateKogitoSources} - runs Kogito codegen reflectively</li>
 *   <li>{@code generateCatalogManifest} - generates catalog.json</li>
 * </ul>
 */
public class AnaxKogitoCodegenPlugin implements Plugin<Project> {

    private static final String KOGITO_VERSION = "10.1.0";
    private static final String CODEGEN_CONFIGURATION_NAME = "kogitoCodegen";
    private static final String GENERATE_SOURCES_TASK_NAME = "generateKogitoSources";
    private static final String GENERATE_CATALOG_TASK_NAME = "generateCatalogManifest";

    @Override
    public void apply(Project project) {
        // Apply the Java plugin if not already applied
        project.getPlugins().apply(JavaPlugin.class);

        // Create the kogitoCodegen configuration
        Configuration codegenConfig = createCodegenConfiguration(project);

        // Add default dependencies to kogitoCodegen configuration
        addDefaultDependencies(project, codegenConfig);

        // Register generateKogitoSources task
        TaskProvider<GenerateKogitoSourcesTask> generateSourcesTask =
            registerGenerateKogitoSourcesTask(project, codegenConfig);

        // Register generateCatalogManifest task
        TaskProvider<CatalogManifestTask> generateCatalogTask =
            registerCatalogManifestTask(project);

        // Wire source sets to include generated directories
        configureSourceSets(project, generateSourcesTask, generateCatalogTask);

        // Set up task dependencies
        configureTaskDependencies(project, generateSourcesTask, generateCatalogTask);
    }

    /**
     * Creates a detached configuration for Kogito codegen dependencies.
     * This isolates codegen-time dependencies from the runtime classpath.
     */
    private Configuration createCodegenConfiguration(Project project) {
        return project.getConfigurations().create(CODEGEN_CONFIGURATION_NAME, config -> {
            config.setVisible(false);
            config.setCanBeConsumed(false);
            config.setCanBeResolved(true);
            config.setDescription("Kogito codegen dependencies (build-time only)");
        });
    }

    /**
     * Adds default Kogito codegen dependencies:
     * - kogito-codegen-manager (orchestrator)
     * - kogito-serverless-workflow-builder (sw.json parser)
     * - kogito-dmn (DMN support, optional but common)
     * - anax-kogito-codegen-extensions (our custom URI scheme handlers)
     */
    private void addDefaultDependencies(Project project, Configuration codegenConfig) {
        project.getDependencies().add(codegenConfig.getName(),
            "org.kie.kogito:kogito-codegen-manager:" + KOGITO_VERSION);
        project.getDependencies().add(codegenConfig.getName(),
            "org.kie.kogito:kogito-serverless-workflow-builder:" + KOGITO_VERSION);
        project.getDependencies().add(codegenConfig.getName(),
            "org.kie.kogito:kogito-dmn:" + KOGITO_VERSION);

        // Add our custom codegen extensions (same version as this plugin)
        String projectVersion = project.getVersion().toString();
        project.getDependencies().add(codegenConfig.getName(),
            "com.anax:anax-kogito-codegen-extensions:" + projectVersion);
    }

    /**
     * Registers the generateKogitoSources task.
     * This task uses URLClassLoader and reflection to invoke kogito-codegen-manager.
     */
    private TaskProvider<GenerateKogitoSourcesTask> registerGenerateKogitoSourcesTask(
            Project project, Configuration codegenConfig) {
        return project.getTasks().register(GENERATE_SOURCES_TASK_NAME,
            GenerateKogitoSourcesTask.class, task -> {
                task.setGroup("build");
                task.setDescription("Generate Kogito sources from *.sw.json and *.dmn files");

                // Wire the kogitoCodegen configuration
                task.getCodegenClasspath().from(codegenConfig);
            });
    }

    /**
     * Registers the generateCatalogManifest task.
     * This task scans sources and generates META-INF/anax/catalog.json.
     */
    private TaskProvider<CatalogManifestTask> registerCatalogManifestTask(Project project) {
        return project.getTasks().register(GENERATE_CATALOG_TASK_NAME,
            CatalogManifestTask.class, task -> {
                task.setGroup("build");
                task.setDescription("Generate catalog.json manifest for Copilot discovery");
            });
    }

    /**
     * Configures the main source set to include generated sources and resources.
     *
     * <p>Adds:
     * <ul>
     *   <li>build/generated/sources/kogito → main java source set</li>
     *   <li>build/generated/resources/kogito → main resources source set</li>
     * </ul>
     */
    private void configureSourceSets(Project project,
            TaskProvider<GenerateKogitoSourcesTask> generateSourcesTask,
            TaskProvider<CatalogManifestTask> generateCatalogTask) {

        JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet mainSourceSet = javaExt.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        // Add generated sources directory
        mainSourceSet.getJava().srcDir(generateSourcesTask.map(task ->
            task.getGeneratedSourcesDir().get().getAsFile()));

        // Add generated resources directory
        mainSourceSet.getResources().srcDir(generateSourcesTask.map(task ->
            task.getGeneratedResourcesDir().get().getAsFile()));

        // Catalog manifest is also a resource
        mainSourceSet.getResources().srcDir(generateCatalogTask.map(task ->
            task.getOutputDir().get().getAsFile()));
    }

    /**
     * Sets up task dependencies:
     * - compileJava depends on generateKogitoSources
     * - processResources depends on generateKogitoSources and generateCatalogManifest
     * - generateCatalogManifest depends on generateKogitoSources (needs generated Java)
     */
    private void configureTaskDependencies(Project project,
            TaskProvider<GenerateKogitoSourcesTask> generateSourcesTask,
            TaskProvider<CatalogManifestTask> generateCatalogTask) {

        // Catalog generation needs the generated Java sources to scan
        generateCatalogTask.configure(task ->
            task.dependsOn(generateSourcesTask));

        // compileJava needs generated sources
        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, task ->
            task.dependsOn(generateSourcesTask));

        // processResources needs both generated resources and catalog
        project.getTasks().named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME, task -> {
            task.dependsOn(generateSourcesTask);
            task.dependsOn(generateCatalogTask);
        });
    }
}
