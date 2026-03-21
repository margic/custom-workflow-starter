package com.anax.kogito.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.*;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Gradle task that generates Kogito sources and resources from workflow and DMN definitions.
 *
 * <p>This task:
 * <ul>
 *   <li>Scans src/main/resources for *.sw.json and *.dmn files</li>
 *   <li>Invokes kogito-codegen-manager reflectively via URLClassLoader</li>
 *   <li>Sets the thread context classloader for ServiceLoader SPI discovery</li>
 *   <li>Outputs generated Java sources to build/generated/sources/kogito</li>
 *   <li>Outputs generated resources to build/generated/resources/kogito</li>
 * </ul>
 *
 * <p>This implementation mirrors the POC build.gradle reflective codegen task,
 * isolating the codegen classpath to avoid polluting the project dependencies.
 */
public abstract class GenerateKogitoSourcesTask extends DefaultTask {

    /**
     * The classpath containing kogito-codegen-manager and all required dependencies.
     * This is populated from the 'kogitoCodegen' configuration.
     */
    @Classpath
    public abstract ConfigurableFileCollection getCodegenClasspath();

    /**
     * Input files: all *.sw.json and *.dmn files in src/main/resources.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getInputFiles() {
        return getProject().fileTree("src/main/resources", tree -> {
            tree.include("**/*.sw.json");
            tree.include("**/*.dmn");
        });
    }

    /**
     * Output directory for generated Java sources.
     * Default: build/generated/sources/kogito
     */
    @OutputDirectory
    public abstract DirectoryProperty getGeneratedSourcesDir();

    /**
     * Output directory for generated resources (e.g., process metadata).
     * Default: build/generated/resources/kogito
     */
    @OutputDirectory
    public abstract DirectoryProperty getGeneratedResourcesDir();

    public GenerateKogitoSourcesTask() {
        // Set default output directories
        getGeneratedSourcesDir().convention(
            getProject().getLayout().getBuildDirectory().dir("generated/sources/kogito"));
        getGeneratedResourcesDir().convention(
            getProject().getLayout().getBuildDirectory().dir("generated/resources/kogito"));
    }

    @TaskAction
    public void generate() throws Exception {
        File sourcesDir = getGeneratedSourcesDir().get().getAsFile();
        File resourcesDir = getGeneratedResourcesDir().get().getAsFile();
        File projectDir = getProject().getProjectDir();

        // Clean output directories
        getProject().delete(sourcesDir, resourcesDir);
        sourcesDir.mkdirs();
        resourcesDir.mkdirs();

        getLogger().lifecycle("Generating Kogito sources...");
        getLogger().lifecycle("  Input:  {}", getInputFiles().getFiles());
        getLogger().lifecycle("  Output: {} (sources)", sourcesDir);
        getLogger().lifecycle("  Output: {} (resources)", resourcesDir);

        // Build the codegen classpath
        URL[] classpathUrls = getCodegenClasspath().getFiles().stream()
            .map(file -> {
                try {
                    return file.toURI().toURL();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to convert file to URL: " + file, e);
                }
            })
            .toArray(URL[]::new);

        // Create an isolated classloader for codegen
        URLClassLoader codegenClassloader = new URLClassLoader(
            classpathUrls,
            ClassLoader.getSystemClassLoader().getParent() // parent = bootstrap, not project classpath
        );

        // Save and replace thread context classloader for ServiceLoader
        ClassLoader originalClassloader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(codegenClassloader);

        try {
            // Reflectively invoke: org.kie.kogito.codegen.CodegenManager.generate(...)
            // This matches the POC build.gradle pattern
            invokeCodegenManager(codegenClassloader, projectDir, sourcesDir, resourcesDir);

            getLogger().lifecycle("Kogito code generation complete.");

        } catch (Exception e) {
            throw new TaskExecutionException(this, new RuntimeException(
                "Kogito code generation failed. Check that *.sw.json and *.dmn files are valid.", e));
        } finally {
            // Restore original classloader
            Thread.currentThread().setContextClassLoader(originalClassloader);
            codegenClassloader.close();
        }
    }

    /**
     * Invokes kogito-codegen-manager reflectively.
     *
     * <p>Equivalent to:
     * <pre>{@code
     * ApplicationGenerator appGen = new ApplicationGenerator()
     *     .withProjectDirectory(projectDir)
     *     .withTargetDirectory(sourcesDir)
     *     .withDependencyPaths(List.of())
     *     .withWorkflowFiles(workflowFiles)
     *     .withDmnFiles(dmnFiles);
     * appGen.generate();
     * }</pre>
     */
    private void invokeCodegenManager(URLClassLoader classloader, File projectDir,
            File sourcesDir, File resourcesDir) throws Exception {

        // Load ApplicationGenerator class
        Class<?> appGenClass = classloader.loadClass(
            "org.kie.kogito.codegen.api.ApplicationGenerator");

        // Create instance: new ApplicationGenerator()
        Object appGen = appGenClass.getDeclaredConstructor().newInstance();

        // Set project directory
        Method withProjectDirectory = appGenClass.getMethod("withProjectDirectory", Path.class);
        appGen = withProjectDirectory.invoke(appGen, projectDir.toPath());

        // Set target directory (sources)
        Method withTargetDirectory = appGenClass.getMethod("withTargetDirectory", Path.class);
        appGen = withTargetDirectory.invoke(appGen, sourcesDir.toPath());

        // Set dependency paths (empty list - we don't need to scan jars)
        Method withDependencyPaths = appGenClass.getMethod("withDependencyPaths", List.class);
        appGen = withDependencyPaths.invoke(appGen, new ArrayList<>());

        // Collect workflow files (*.sw.json)
        List<Path> workflowFiles = new ArrayList<>();
        getProject().fileTree("src/main/resources", tree -> tree.include("**/*.sw.json"))
            .forEach(file -> workflowFiles.add(file.toPath()));

        if (!workflowFiles.isEmpty()) {
            Method withWorkflowFiles = appGenClass.getMethod("withWorkflowFiles", List.class);
            appGen = withWorkflowFiles.invoke(appGen, workflowFiles);
        }

        // Collect DMN files (*.dmn)
        List<Path> dmnFiles = new ArrayList<>();
        getProject().fileTree("src/main/resources", tree -> tree.include("**/*.dmn"))
            .forEach(file -> dmnFiles.add(file.toPath()));

        if (!dmnFiles.isEmpty()) {
            Method withDmnFiles = appGenClass.getMethod("withDmnFiles", List.class);
            appGen = withDmnFiles.invoke(appGen, dmnFiles);
        }

        // Generate
        Method generate = appGenClass.getMethod("generate");
        generate.invoke(appGen);

        getLogger().info("Invoked ApplicationGenerator.generate() successfully.");
    }
}
