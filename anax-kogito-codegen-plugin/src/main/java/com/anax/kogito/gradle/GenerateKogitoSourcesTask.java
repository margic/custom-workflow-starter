package com.anax.kogito.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Runs Kogito code generation via reflective invocation.
 *
 * Builds a URLClassLoader from kogitoCodegen + runtimeClasspath,
 * sets Thread.contextClassLoader, then invokes:
 * CodeGenManagerUtil.discoverKogitoRuntimeContext()
 * GenerateModelHelper.generateModelFiles()
 *
 * Based on the proven POC implementation in docs/0006-POC-REFERENCE.md §3.1.
 */
public abstract class GenerateKogitoSourcesTask extends DefaultTask {

    @Input
    public abstract Property<String> getKogitoVersion();

    @Classpath
    public abstract ConfigurableFileCollection getCodegenClasspath();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getWorkflowFiles();

    @OutputDirectory
    public abstract DirectoryProperty getOutputSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputResourceDir();

    @TaskAction
    public void generate() {
        getLogger().lifecycle("generateKogitoSources: running Kogito codegen (version {})",
                getKogitoVersion().get());

        URL[] urls = getCodegenClasspath().getFiles().stream()
                .filter(java.io.File::exists)
                .map(f -> {
                    try {
                        return f.toURI().toURL();
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(URL[]::new);

        if (urls.length == 0) {
            getLogger().warn("generateKogitoSources: no classpath entries found — skipping codegen.");
            return;
        }

        URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
        ClassLoader origCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);

        try {
            // AppPaths uses this property to detect Gradle build tool
            System.setProperty("org.gradle.appname", "gradle");

            Path projectBase = getProject().getProjectDir().toPath().toAbsolutePath();
            Path outputSources = getOutputSourceDir().get().getAsFile().toPath().toAbsolutePath();
            Path outputResources = getOutputResourceDir().get().getAsFile().toPath().toAbsolutePath();

            // Load Kogito classes reflectively from the codegen classpath
            Class<?> kogitoGAVClass = cl.loadClass(
                    "org.kie.kogito.KogitoGAV");
            Class<?> codeGenUtilClass = cl.loadClass(
                    "org.kie.kogito.codegen.manager.util.CodeGenManagerUtil");
            Class<?> frameworkEnum = cl.loadClass(
                    "org.kie.kogito.codegen.manager.util.CodeGenManagerUtil$Framework");
            Class<?> projectParamsClass = cl.loadClass(
                    "org.kie.kogito.codegen.manager.util.CodeGenManagerUtil$ProjectParameters");
            Class<?> generateModelHelperClass = cl.loadClass(
                    "org.kie.kogito.codegen.manager.GenerateModelHelper");
            Class<?> buildContextClass = cl.loadClass(
                    "org.kie.kogito.codegen.api.context.KogitoBuildContext");

            // Build GAV
            Object gav = kogitoGAVClass
                    .getConstructor(String.class, String.class, String.class)
                    .newInstance(
                            getProject().getGroup().toString(),
                            getProject().getName(),
                            getProject().getVersion().toString());

            // Build ProjectParameters(Framework.SPRING, null, null, null, null, false)
            Object springFramework = frameworkEnum.getField("SPRING").get(null);
            Object projectParams = projectParamsClass.getConstructors()[0]
                    .newInstance(springFramework, null, null, null, null, false);

            // Class availability predicate — checks if a class is loadable
            Predicate<String> classAvailability = className -> {
                try {
                    cl.loadClass(className);
                    return true;
                } catch (ClassNotFoundException e) {
                    return false;
                }
            };

            // Discover Kogito build context
            Object context = codeGenUtilClass.getMethod(
                    "discoverKogitoRuntimeContext",
                    ClassLoader.class, Path.class,
                    kogitoGAVClass, projectParamsClass,
                    Predicate.class)
                    .invoke(null, cl, projectBase, gav, projectParams, classAvailability);

            // Run code generation
            @SuppressWarnings("unchecked")
            Map<?, ?> result = (Map<?, ?>) generateModelHelperClass
                    .getMethod("generateModelFiles", buildContextClass, boolean.class)
                    .invoke(null, context, false);

            // Write generated source and resource files
            int sourceCount = 0;
            int resourceCount = 0;

            for (Map.Entry<?, ?> entry : result.entrySet()) {
                String categoryName = entry.getKey().toString();
                @SuppressWarnings("unchecked")
                Collection<?> files = (Collection<?>) entry.getValue();
                if (files == null)
                    continue;

                boolean isSource = categoryName.contains("SOURCE")
                        && !categoryName.contains("RESOURCE");
                Path targetDir = isSource ? outputSources : outputResources;

                for (Object gf : files) {
                    String relativePath = (String) gf.getClass()
                            .getMethod("relativePath").invoke(gf);
                    byte[] contents = (byte[]) gf.getClass()
                            .getMethod("contents").invoke(gf);

                    Path target = targetDir.resolve(relativePath);
                    Files.createDirectories(target.getParent());
                    Files.write(target, contents);

                    if (isSource)
                        sourceCount++;
                    else
                        resourceCount++;
                }
            }

            getLogger().lifecycle("[KogitoCodegen] Generated {} source files and {} resource files",
                    sourceCount, resourceCount);

        } catch (Exception e) {
            throw new GradleException("Kogito code generation failed: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(origCl);
            try {
                cl.close();
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }
}
