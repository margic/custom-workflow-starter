package com.anax.kogito.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

/**
 * Runs Kogito code generation via reflective invocation.
 *
 * Builds a URLClassLoader from kogitoCodegen + runtimeClasspath,
 * sets Thread.contextClassLoader, then invokes:
 *   CodeGenManagerUtil.discoverKogitoRuntimeContext()
 *   GenerateModelHelper.generateModelFiles()
 *
 * This is a placeholder — the reflective invocation will be wired
 * in a later iteration following POC Reference §3.1.
 */
public abstract class GenerateKogitoSourcesTask extends DefaultTask {

    @Input
    public abstract Property<String> getKogitoVersion();

    @TaskAction
    public void generate() {
        getLogger().lifecycle("generateKogitoSources: Kogito codegen (version {}) — "
                + "placeholder task. Full reflective invocation to be wired per POC Reference §3.1.",
                getKogitoVersion().get());
        // TODO: Implement reflective Kogito codegen invocation:
        // 1. Build URLClassLoader from kogitoCodegen + runtimeClasspath
        // 2. Set Thread.currentThread().setContextClassLoader(classLoader)
        // 3. Reflectively invoke CodeGenManagerUtil.discoverKogitoRuntimeContext()
        // 4. Reflectively invoke GenerateModelHelper.generateModelFiles()
        // 5. Copy generated .java files to build/generated/sources/kogito
    }
}
