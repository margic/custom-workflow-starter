# POC Reference: Proven Source Code for custom-workflow-starter

**Purpose:** This document packages the complete, working POC implementation from `order-routing-service` that validates the architecture described in [ADR 006](0006-kogito-custom-uri-spring-boot-starter.md). Every file below has been compiled, tested, and verified end-to-end. The implementation plan prompts reference this document — use it as the authoritative source for patterns, class structures, and Kogito API usage.

**Date:** March 2026

---

## Table of Contents

1. [Codegen-Time SPI Handlers](#1-codegen-time-spi-handlers)
   - 1.1 [DmnFunctionTypeHandler.java](#11-dmnfunctiontypehandlerjava)
   - 1.2 [AnaxFunctionTypeHandler.java](#12-anaxfunctiontypehandlerjava)
   - 1.3 [SPI Registration File](#13-spi-registration-file)
2. [Runtime Work-Item Handlers](#2-runtime-work-item-handlers)
   - 2.1 [DmnWorkItemHandler.java](#21-dmnworkitemhandlerjava)
   - 2.2 [AnaxWorkItemHandler.java](#22-anaxworkitemhandlerjava)
   - 2.3 [CustomWorkItemHandlerConfig.java](#23-customworkitemhandlerconfigjava)
3. [Build Configuration](#3-build-configuration)
   - 3.1 [build.gradle (order-routing-service)](#31-buildgradle)
4. [Workflow Definitions](#4-workflow-definitions)
   - 4.1 [hello-world.sw.json](#41-hello-worldswjson)
   - 4.2 [utility-order-mapping.sw.json](#42-utility-order-mappingswjson)
5. [Service Stubs](#5-service-stubs)
   - 5.1 [HelloService.java](#51-helloservicejava)

---

## Key Architecture Points

Before referencing the code, understand the two-phase execution model:

1. **Codegen-time** (Gradle build): `FunctionTypeHandler` SPI implementations are discovered via `ServiceLoader`. They teach the Kogito code generator to emit `WorkItemNode` entries (instead of empty lambdas) for custom URI schemes. The handler parses the URI and writes static parameters into the node.

2. **Runtime** (Spring Boot): `DefaultKogitoWorkItemHandler` subclasses are registered by name with `WorkItemHandlerConfig`. When the process engine reaches a `WorkItemNode`, it dispatches to the handler matching the `workName`. The handler reads the static parameters + dynamic arguments, performs its logic, and calls `manager.completeWorkItem()`.

**Critical invariant:** The `workName(scheme)` set during codegen must exactly match the name used in `register(scheme, handler)` at runtime.

---

## 1. Codegen-Time SPI Handlers

These classes are compiled into a separate source set (`codegenExtensions`) and placed on the codegen classpath via a `URLClassLoader`. They are **not** part of the runtime application — they only run during `generateKogitoSources`.

### 1.1 DmnFunctionTypeHandler.java

**Path in POC:** `order-routing-service/src/codegenExtensions/java/com/anax/routing/kogito/parser/DmnFunctionTypeHandler.java`
**Target in starter:** `anax-kogito-codegen-extensions/src/main/java/com/anax/kogito/codegen/DmnFunctionTypeHandler.java`

```java
package com.anax.routing.kogito.parser;

import io.serverlessworkflow.api.Workflow;
import io.serverlessworkflow.api.functions.FunctionDefinition;
import org.jbpm.ruleflow.core.RuleFlowNodeContainerFactory;
import org.jbpm.ruleflow.core.factory.WorkItemNodeFactory;
import org.kie.kogito.serverless.workflow.parser.FunctionTypeHandlerFactory;
import org.kie.kogito.serverless.workflow.parser.ParserContext;
import org.kie.kogito.serverless.workflow.parser.types.WorkItemTypeHandler;

/**
 * Codegen-time extension: teaches the Kogito codegen to emit a WorkItemNode
 * (instead of an empty lambda) for any {@code type: custom} function whose
 * {@code operation} uses the {@code dmn://} URI scheme.
 *
 * <p>
 * URI format: {@code dmn://namespace/Model Name}
 * <ul>
 * <li>namespace — the DMN model namespace (e.g.
 * {@code com.anax.decisions})</li>
 * <li>Model Name — the DMN model name (e.g. {@code Order Type Routing})</li>
 * </ul>
 *
 * <p>
 * Registered via SPI:
 * {@code META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler}
 *
 * <p>
 * At runtime the work item is dispatched to
 * {@link com.anax.routing.kogito.handler.DmnWorkItemHandler}.
 */
public class DmnFunctionTypeHandler extends WorkItemTypeHandler {

    /**
     * Work-item handler name — must match what is registered in
     * CustomWorkItemHandlerConfig.
     */
    public static final String DMN_SCHEME = "dmn";

    /** Work-item parameter carrying the DMN model namespace. */
    public static final String PARAM_NAMESPACE = "DmnNamespace";

    /** Work-item parameter carrying the DMN model name. */
    public static final String PARAM_MODEL_NAME = "ModelName";

    @Override
    public String type() {
        return DMN_SCHEME;
    }

    @Override
    public boolean isCustom() {
        return true;
    }

    /**
     * Sets the work-item handler name to {@code "dmn"} and enriches the node with
     * the static namespace and model-name parameters parsed from the operation URI.
     *
     * <p>
     * {@code FunctionTypeHandlerFactory.trimCustomOperation()} strips the scheme
     * prefix up to and including the first {@code ":"}, so for
     * {@code "dmn://com.anax.decisions/Order Type Routing"} it returns
     * {@code "//com.anax.decisions/Order Type Routing"}.
     */
    @Override
    protected <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T> fillWorkItemHandler(
            Workflow workflow, ParserContext context,
            WorkItemNodeFactory<T> factory, FunctionDefinition functionDef) {

        String operationPath = FunctionTypeHandlerFactory.trimCustomOperation(functionDef);
        if (operationPath.startsWith("//")) {
            operationPath = operationPath.substring(2);
        }

        int slash = operationPath.indexOf('/');
        String namespace = slash >= 0 ? operationPath.substring(0, slash) : "";
        String modelName = slash >= 0 ? operationPath.substring(slash + 1) : operationPath;

        return factory
                .workName(DMN_SCHEME)
                .workParameter(PARAM_NAMESPACE, namespace)
                .workParameter(PARAM_MODEL_NAME, modelName);
    }
}
```

### 1.2 AnaxFunctionTypeHandler.java

**Path in POC:** `order-routing-service/src/codegenExtensions/java/com/anax/routing/kogito/parser/AnaxFunctionTypeHandler.java`
**Target in starter:** `anax-kogito-codegen-extensions/src/main/java/com/anax/kogito/codegen/AnaxFunctionTypeHandler.java`

```java
package com.anax.routing.kogito.parser;

import io.serverlessworkflow.api.Workflow;
import io.serverlessworkflow.api.functions.FunctionDefinition;
import org.jbpm.ruleflow.core.RuleFlowNodeContainerFactory;
import org.jbpm.ruleflow.core.factory.WorkItemNodeFactory;
import org.kie.kogito.serverless.workflow.parser.FunctionTypeHandlerFactory;
import org.kie.kogito.serverless.workflow.parser.ParserContext;
import org.kie.kogito.serverless.workflow.parser.types.WorkItemTypeHandler;

/**
 * Codegen-time extension: teaches the Kogito codegen to emit a WorkItemNode
 * for any {@code type: custom} function whose {@code operation} uses the
 * {@code anax://} URI scheme.
 *
 * <p>
 * URI format: {@code anax://beanName/methodName}
 * <ul>
 * <li>beanName — the Spring bean name (e.g. {@code helloService})</li>
 * <li>methodName — the method to invoke; defaults to {@code execute} if
 * omitted</li>
 * </ul>
 *
 * <p>
 * Registered via SPI:
 * {@code META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler}
 *
 * <p>
 * At runtime the work item is dispatched to
 * {@link com.anax.routing.kogito.handler.AnaxWorkItemHandler}.
 */
public class AnaxFunctionTypeHandler extends WorkItemTypeHandler {

    /**
     * Work-item handler name — must match what is registered in
     * CustomWorkItemHandlerConfig.
     */
    public static final String ANAX_SCHEME = "anax";

    /** Work-item parameter carrying the Spring bean name. */
    public static final String PARAM_BEAN_NAME = "BeanName";

    /** Work-item parameter carrying the method name to invoke on the bean. */
    public static final String PARAM_METHOD_NAME = "MethodName";

    @Override
    public String type() {
        return ANAX_SCHEME;
    }

    @Override
    public boolean isCustom() {
        return true;
    }

    /**
     * Sets the work-item handler name to {@code "anax"} and enriches the node with
     * the static bean-name and method-name parsed from the operation URI.
     *
     * <p>
     * Example: {@code "anax://helloService/greet"}
     * → {@code BeanName = "helloService"}, {@code MethodName = "greet"}
     */
    @Override
    protected <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T> fillWorkItemHandler(
            Workflow workflow, ParserContext context,
            WorkItemNodeFactory<T> factory, FunctionDefinition functionDef) {

        String operationPath = FunctionTypeHandlerFactory.trimCustomOperation(functionDef);
        if (operationPath.startsWith("//")) {
            operationPath = operationPath.substring(2);
        }

        int slash = operationPath.indexOf('/');
        String beanName = slash >= 0 ? operationPath.substring(0, slash) : operationPath;
        String methodName = slash >= 0 ? operationPath.substring(slash + 1) : "execute";

        return factory
                .workName(ANAX_SCHEME)
                .workParameter(PARAM_BEAN_NAME, beanName)
                .workParameter(PARAM_METHOD_NAME, methodName);
    }
}
```

### 1.3 SPI Registration File

**Path in POC:** `order-routing-service/src/codegenExtensions/resources/META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler`
**Target in starter:** `anax-kogito-codegen-extensions/src/main/resources/META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler`

```
com.anax.routing.kogito.parser.DmnFunctionTypeHandler
com.anax.routing.kogito.parser.AnaxFunctionTypeHandler
```

> **Note:** In the starter, the `map://` handler must also be added to this file.

---

## 2. Runtime Work-Item Handlers

These are Spring-managed `@Component` beans that execute at runtime when the Kogito process engine dispatches a work item.

### 2.1 DmnWorkItemHandler.java

**Path in POC:** `order-routing-service/src/main/java/com/anax/routing/kogito/handler/DmnWorkItemHandler.java`
**Target in starter:** `anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/handler/DmnWorkItemHandler.java`

```java
package com.anax.routing.kogito.handler;

import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNResult;
import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;
import org.kie.kogito.internal.process.workitem.KogitoWorkItem;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemManager;
import org.kie.kogito.internal.process.workitem.Policy;
import org.kie.kogito.internal.process.workitem.WorkItemTransition;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runtime handler for {@code dmn://} custom functions.
 *
 * <p>
 * Evaluates a Kogito-managed DMN model entirely in-process — no network call.
 * The model namespace and name are passed as static work-item parameters set by
 * {@link com.anax.routing.kogito.parser.DmnFunctionTypeHandler} during codegen.
 * Any additional work-item parameters (originating from the {@code arguments}
 * block
 * in the sw.json) are forwarded as DMN input variables.
 *
 * <p>
 * Registered as the {@code "dmn"} handler in
 * {@link com.anax.routing.kogito.CustomWorkItemHandlerConfig}.
 */
@Component("dmnWorkItemHandler")
public class DmnWorkItemHandler extends DefaultKogitoWorkItemHandler {

        static final String PARAM_NAMESPACE = "DmnNamespace";
        static final String PARAM_MODEL_NAME = "ModelName";

        @Autowired
        private DecisionModels decisionModels;

        /**
         * Executes synchronously: evaluates the DMN model and immediately completes
         * the work item, injecting all decision results back into workflow data.
         */
        @Override
        public Optional<WorkItemTransition> activateWorkItemHandler(
                        KogitoWorkItemManager manager,
                        KogitoWorkItemHandler handler,
                        KogitoWorkItem workItem,
                        WorkItemTransition transition) {

                String namespace = (String) workItem.getParameter(PARAM_NAMESPACE);
                String modelName = (String) workItem.getParameter(PARAM_MODEL_NAME);

                // Build DMN input from work-item params, excluding Kogito-internal ones
                Map<String, Object> inputs = new HashMap<>(workItem.getParameters());
                inputs.remove(PARAM_NAMESPACE);
                inputs.remove(PARAM_MODEL_NAME);
                inputs.remove("TaskName");

                DecisionModel model = decisionModels.getDecisionModel(namespace, modelName);
                DMNContext ctx = model.newContext(inputs);
                DMNResult result = model.evaluateAll(ctx);

                // Flatten all decision results into the output map
                Map<String, Object> outputs = result.getDecisionResults().stream()
                                .filter(dr -> dr.getEvaluationStatus() == DMNDecisionResult.DecisionEvaluationStatus.SUCCEEDED)
                                .collect(Collectors.toMap(
                                                DMNDecisionResult::getDecisionName,
                                                dr -> dr.getResult() != null ? dr.getResult() : ""));

                manager.completeWorkItem(workItem.getStringId(), outputs,
                                transition.policies().toArray(Policy[]::new));
                return Optional.empty();
        }
}
```

### 2.2 AnaxWorkItemHandler.java

**Path in POC:** `order-routing-service/src/main/java/com/anax/routing/kogito/handler/AnaxWorkItemHandler.java`
**Target in starter:** `anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/handler/AnaxWorkItemHandler.java`

```java
package com.anax.routing.kogito.handler;

import org.kie.kogito.internal.process.workitem.KogitoWorkItem;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemManager;
import org.kie.kogito.internal.process.workitem.Policy;
import org.kie.kogito.internal.process.workitem.WorkItemTransition;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime handler for {@code anax://} custom functions.
 *
 * <p>
 * Resolves a Spring bean by name and invokes the specified method, passing
 * the remaining work-item parameters as a {@code Map<String, Object>}.
 * The bean name and method name are static work-item parameters injected by
 * {@link com.anax.routing.kogito.parser.AnaxFunctionTypeHandler} during
 * codegen.
 *
 * <p>
 * The target method must have the signature:
 *
 * <pre>{@code Map<String, Object> methodName(Map<String, Object> params)}</pre>
 *
 * <p>
 * Any map returned by the method is merged back into workflow data.
 *
 * <p>
 * Registered as the {@code "anax"} handler in
 * {@link com.anax.routing.kogito.CustomWorkItemHandlerConfig}.
 */
@Component("anaxWorkItemHandler")
public class AnaxWorkItemHandler extends DefaultKogitoWorkItemHandler {

    static final String PARAM_BEAN_NAME = "BeanName";
    static final String PARAM_METHOD_NAME = "MethodName";

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Resolves the Spring bean and invokes the named method synchronously.
     * The method return value (a {@code Map}) is written back as work-item output.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Optional<WorkItemTransition> activateWorkItemHandler(
            KogitoWorkItemManager manager,
            KogitoWorkItemHandler handler,
            KogitoWorkItem workItem,
            WorkItemTransition transition) {

        String beanName = (String) workItem.getParameter(PARAM_BEAN_NAME);
        String methodName = (String) workItem.getParameter(PARAM_METHOD_NAME);

        Map<String, Object> params = new HashMap<>(workItem.getParameters());
        params.remove(PARAM_BEAN_NAME);
        params.remove(PARAM_METHOD_NAME);
        params.remove("TaskName");

        Object bean = applicationContext.getBean(beanName);
        Map<String, Object> result;
        try {
            Method method = bean.getClass().getMethod(methodName, Map.class);
            result = (Map<String, Object>) method.invoke(bean, params);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "anax:// handler: bean '" + beanName + "' has no method '"
                            + methodName + "(Map<String,Object>)'",
                    e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "anax:// handler invocation failed for " + beanName + "/" + methodName, e);
        }

        manager.completeWorkItem(workItem.getStringId(),
                result != null ? result : Collections.emptyMap(),
                transition.policies().toArray(Policy[]::new));
        return Optional.empty();
    }
}
```

### 2.3 CustomWorkItemHandlerConfig.java

**Path in POC:** `order-routing-service/src/main/java/com/anax/routing/kogito/CustomWorkItemHandlerConfig.java`
**Target in starter:** `anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/AnaxWorkItemHandlerConfig.java`

```java
package com.anax.routing.kogito;

import com.anax.routing.kogito.handler.AnaxWorkItemHandler;
import com.anax.routing.kogito.handler.DmnWorkItemHandler;
import org.kie.kogito.process.impl.DefaultWorkItemHandlerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Registers all custom {@code WorkItemHandler} implementations with the
 * Kogito process engine.
 *
 * <p>
 * The generated {@link org.kie.kogito.app.ProcessConfig} bean accepts a
 * {@code List<WorkItemHandlerConfig>} via constructor injection. Spring
 * discovers this bean automatically, so the handlers are live as soon as the
 * application context starts.
 *
 * <p>
 * Handler names here must exactly match the {@code workName()} values set
 * by the corresponding codegen-time {@code FunctionTypeHandler}:
 * <ul>
 * <li>{@code "dmn"} → {@link DmnWorkItemHandler}</li>
 * <li>{@code "anax"} → {@link AnaxWorkItemHandler}</li>
 * </ul>
 */
@Component
public class CustomWorkItemHandlerConfig extends DefaultWorkItemHandlerConfig {

    @Autowired
    public CustomWorkItemHandlerConfig(DmnWorkItemHandler dmn, AnaxWorkItemHandler anax) {
        register("dmn", dmn);
        register("anax", anax);
    }
}
```

> **Note:** In the starter, this becomes an auto-configured `@Bean` inside `AnaxKogitoAutoConfiguration` rather than a `@Component`. It should also register the `map://` handler.

---

## 3. Build Configuration

### 3.1 build.gradle

**Path in POC:** `order-routing-service/build.gradle`

This is the complete, working build file. The critical sections are:

- `kogitoCodegen` configuration (build-time only)
- `codegenExtensions` source set (separate compilation unit for SPI handlers)
- `generateKogitoSources` task (reflective invocation of Kogito codegen with `URLClassLoader`)
- Main source set wiring (generated sources + codegenExtensions output)
- Task dependency chain

```groovy
plugins {
    id 'org.springframework.boot' version "${springBootVersion}"
    id 'io.spring.dependency-management' version '1.1.7'
}

ext {
    kogitoVersion = project.hasProperty('kogitoVersion') ? project.property('kogitoVersion') : '10.1.0'
}

dependencyManagement {
    imports {
        mavenBom "org.kie.kogito:kogito-bom:${kogitoVersion}"
    }
}

// ── Kogito Codegen: build-time only configuration ────────────────────────
// kogito-codegen-manager transitively brings in all four code generators
// (processes, decisions, predictions, rules) plus drools-compiler.
configurations {
    kogitoCodegen
}

dependencies {
    implementation project(':control-record-model')
    implementation project(':kogito-persistence-tracing')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'

    // ── Kogito / KIE Runtime ─────────────────────────────────────────────
    // Core Kogito interfaces (Application, Process, Model, Config, etc.)
    implementation 'org.kie.kogito:kogito-api'
    // DMN runtime engine + REST helpers (KogitoDMNResult, DMNJSONUtils, etc.)
    implementation 'org.kie.kogito:kogito-dmn'
    // Process runtime (RuleFlowProcessFactory, AbstractProcess, ProcessService)
    implementation 'org.kie.kogito:jbpm-flow'
    implementation 'org.kie.kogito:jbpm-flow-builder'
    // Serverless Workflow runtime support (JsonNodeModel, sw.json interpretation)
    implementation 'org.kie.kogito:kogito-serverless-workflow-builder'
    // Spring Boot process starter (includes CloudEvents / messaging support)
    implementation 'org.jbpm:jbpm-spring-boot-starter'
    // Spring Boot decisions starter (DMN runtime + REST)
    implementation 'org.drools:drools-decisions-spring-boot-starter'
    // Spring Boot messaging add-on (SpringMessageConsumer, EventReceiver)
    implementation 'org.kie:kie-addons-springboot-messaging'
    // Process management REST API (exposes /management/processes/)
    implementation 'org.kie:kie-addons-springboot-process-management'
    // Swagger/OpenAPI annotations used by generated REST resources
    implementation 'io.swagger.core.v3:swagger-annotations:2.2.20'
    // MicroProfile OpenAPI annotations used by generated DMN resource
    implementation 'org.eclipse.microprofile.openapi:microprofile-openapi-api:3.1.1'

    // ── Kogito Codegen (build-time only) ─────────────────────────────────
    kogitoCodegen "org.kie.kogito:kogito-codegen-manager:${kogitoVersion}"
    // SmallRye provides the OASFactoryResolver needed by the codegen at build time
    kogitoCodegen 'io.smallrye:smallrye-open-api-core:3.10.0'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.kafka:spring-kafka-test'
    testImplementation "org.kie.kogito:kogito-codegen-manager:${kogitoVersion}"
    testImplementation 'org.assertj:assertj-core:3.25.1'
}

// ── Source sets ─────────────────────────────────────────────────────────
// codegenExtensions: compiled BEFORE generateKogitoSources so that the
// FunctionTypeHandler SPI implementations are on the codegen classpath.
sourceSets {
    codegenExtensions {
        java      { setSrcDirs(['src/codegenExtensions/java']) }
        resources { setSrcDirs(['src/codegenExtensions/resources']) }
        // Inherit the same compile dependencies as main so that the
        // kogito-serverless-workflow-builder classes are available.
        compileClasspath += sourceSets.main.compileClasspath
    }
    main {
        java {
            setSrcDirs(["src/main/java", "${buildDir}/generated/sources/kogito"])
        }
        resources {
            setSrcDirs(["src/main/resources", "${buildDir}/generated/resources/kogito"])
        }
        // Make codegenExtensions classes available to main compilation and runtime
        compileClasspath += sourceSets.codegenExtensions.output
        runtimeClasspath += sourceSets.codegenExtensions.output
    }
}

// ── Kogito Code Generation Task ──────────────────────────────────────────
// Invokes kogito-codegen-manager to generate Java sources from sw.json / DMN
// resources. This replaces the kogito-gradle-plugin (coming in 10.2.0).
tasks.register('generateKogitoSources') {
    description = 'Generates Kogito Java sources from sw.json and DMN resources'
    group = 'kogito'

    inputs.files(fileTree('src/main/resources') { include '**/*.sw.json', '**/*.dmn' })
    outputs.dir("${buildDir}/generated/sources/kogito")
    outputs.dir("${buildDir}/generated/resources/kogito")

    // Must run after codegenExtensions are compiled so our FunctionTypeHandlers
    // are discoverable via ServiceLoader in the URLClassLoader.
    dependsOn 'compileCodegenExtensionsJava'
    inputs.files(sourceSets.codegenExtensions.output)

    doLast {
        // Combine codegen + runtime classpath + our compiled codegenExtensions
        // (FunctionTypeHandler SPI implementations + SPI registration files).
        def extensionFiles = sourceSets.codegenExtensions.output.classesDirs.files +
                             [sourceSets.codegenExtensions.output.resourcesDir]
        def codegenCp = (configurations.kogitoCodegen.resolve()
            + configurations.runtimeClasspath.resolve()
            + extensionFiles)
            .findAll { it.exists() }
            .collect { it.toURI().toURL() } as URL[]
        def cl = new URLClassLoader(codegenCp, ClassLoader.systemClassLoader)

        // ServiceLoader (used by MicroProfile OASFactoryResolver and others)
        // resolves SPI providers via Thread.contextClassLoader — point it at
        // our URLClassLoader so it can find SmallRye and other implementations.
        def origCl = Thread.currentThread().getContextClassLoader()
        Thread.currentThread().setContextClassLoader(cl)

        try {

        // Set Gradle system property so AppPaths detects Gradle build tool
        System.setProperty('org.gradle.appname', 'gradle')

        def projectBase = projectDir.toPath().toAbsolutePath()
        def outputSources = file("${buildDir}/generated/sources/kogito").toPath().toAbsolutePath()
        def outputResources = file("${buildDir}/generated/resources/kogito").toPath().toAbsolutePath()

        // --- Load classes via reflection from the codegen classpath ---
        def kogitoGAVClass = cl.loadClass('org.kie.kogito.KogitoGAV')
        def codeGenUtilClass = cl.loadClass('org.kie.kogito.codegen.manager.util.CodeGenManagerUtil')
        def frameworkEnum = cl.loadClass('org.kie.kogito.codegen.manager.util.CodeGenManagerUtil$Framework')
        def projectParamsClass = cl.loadClass('org.kie.kogito.codegen.manager.util.CodeGenManagerUtil$ProjectParameters')
        def generateModelHelperClass = cl.loadClass('org.kie.kogito.codegen.manager.GenerateModelHelper')

        // Build GAV
        def gav = kogitoGAVClass.getConstructor(String, String, String)
            .newInstance('com.anax', 'order-routing-service', '0.1.0-SNAPSHOT')

        // Build ProjectParameters(Framework.SPRING, null, null, null, null, false)
        def springFramework = frameworkEnum.getField('SPRING').get(null)
        def projectParams = projectParamsClass.getConstructors()[0]
            .newInstance(springFramework, null, null, null, null, false)

        // Class availability predicate
        def classAvailability = { String className ->
            try { cl.loadClass(className); return true }
            catch (ClassNotFoundException e) { return false }
        } as java.util.function.Predicate

        // Discover Kogito build context
        def context = codeGenUtilClass.getMethod('discoverKogitoRuntimeContext',
                ClassLoader, java.nio.file.Path,
                kogitoGAVClass, projectParamsClass,
                java.util.function.Predicate)
            .invoke(null, cl, projectBase, gav, projectParams, classAvailability)

        // Run code generation
        def result = generateModelHelperClass.getMethod('generateModelFiles',
                cl.loadClass('org.kie.kogito.codegen.api.context.KogitoBuildContext'),
                boolean)
            .invoke(null, context, false)

        // Write generated sources
        def sources = result.get('SOURCES')
        def resources = result.get('RESOURCES')
        int sourceCount = 0
        int resourceCount = 0

        if (sources) {
            java.nio.file.Files.createDirectories(outputSources)
            sources.each { gf ->
                def target = outputSources.resolve(gf.relativePath())
                java.nio.file.Files.createDirectories(target.parent)
                java.nio.file.Files.write(target, gf.contents())
                sourceCount++
            }
        }
        if (resources) {
            java.nio.file.Files.createDirectories(outputResources)
            resources.each { gf ->
                def target = outputResources.resolve(gf.relativePath())
                java.nio.file.Files.createDirectories(target.parent)
                java.nio.file.Files.write(target, gf.contents())
                resourceCount++
            }
        }

        logger.lifecycle("[KogitoCodegen] Generated ${sourceCount} source files and ${resourceCount} resource files")

        } finally {
            Thread.currentThread().setContextClassLoader(origCl)
        }
    }
}

// ── Dump the runtime classpath for KogitoCodegenIT ──────────────────────
tasks.register('writeRuntimeClasspath') {
    doLast {
        def cpFile = file('build/runtimeClasspath.txt')
        cpFile.parentFile.mkdirs()
        cpFile.text = configurations.runtimeClasspath.resolvedConfiguration
            .resolvedArtifacts
            .collect { it.file.absolutePath }
            .join('\n')
    }
}

// Wire codegen into the build lifecycle
tasks.named('compileJava') {
    dependsOn 'generateKogitoSources'
}
tasks.named('processResources') {
    dependsOn 'generateKogitoSources'
}
tasks.named('test') {
    dependsOn 'writeRuntimeClasspath'
}
```

> **Key lesson:** The `generateKogitoSources` task in the POC uses reflective invocation because no Gradle plugin exists yet. In the starter, `anax-kogito-codegen-plugin` will encapsulate this logic as a proper Gradle plugin with a `GenerateKogitoSourcesTask` and `CatalogManifestTask`.

---

## 4. Workflow Definitions

### 4.1 hello-world.sw.json

**Path in POC:** `order-routing-service/src/main/resources/hello-world.sw.json`

Minimal demo exercising both `anax://` and `dmn://` schemes.

```json
{
  "id": "hello-world",
  "name": "Hello World — dmn:// + anax:// Demo",
  "version": "1.0",
  "specVersion": "0.8",
  "start": "Greet",
  "functions": [
    {
      "name": "greetFunction",
      "type": "custom",
      "operation": "anax://helloService/greet"
    },
    {
      "name": "orderTypeDecisionFunction",
      "type": "custom",
      "operation": "dmn://com.anax.decisions/Order Type Routing"
    },
    {
      "name": "logFunction",
      "type": "custom",
      "operation": "sysout"
    }
  ],
  "states": [
    {
      "name": "Greet",
      "type": "operation",
      "actions": [
        {
          "name": "greetAction",
          "functionRef": {
            "refName": "greetFunction",
            "arguments": { "name": "${ .name }" }
          }
        }
      ],
      "transition": "EvaluateOrderType"
    },
    {
      "name": "EvaluateOrderType",
      "type": "operation",
      "actions": [
        {
          "name": "decideAction",
          "functionRef": {
            "refName": "orderTypeDecisionFunction",
            "arguments": { "orderType": "${ .orderType }" }
          }
        }
      ],
      "transition": "LogResult"
    },
    {
      "name": "LogResult",
      "type": "operation",
      "actions": [
        {
          "name": "logAction",
          "functionRef": {
            "refName": "logFunction",
            "arguments": {
              "message": "${ \"Workflow complete — greeting: \" + .greeting }"
            }
          }
        }
      ],
      "end": true
    }
  ]
}
```

### 4.2 utility-order-mapping.sw.json

**Path in POC:** `order-routing-service/src/main/resources/utility-order-mapping.sw.json`

Production-grade workflow demonstrating events, DMN decision gates, callback states, and Spring bean invocations.

```json
{
  "id": "utility-order-mapping",
  "name": "Automated X9 Mapping Pipeline with Party Lookup",
  "version": "3.0",
  "specVersion": "0.8",
  "start": "ReceiveControlRecord",
  "events": [
    {
      "name": "ControlRecordCreatedEvent",
      "source": "control-record-service",
      "type": "anax.controlrecord.created",
      "kind": "consumed"
    },
    {
      "name": "HumanCorrectionSubmittedEvent",
      "source": "control-record-service",
      "type": "anax.controlrecord.corrected",
      "kind": "consumed"
    }
  ],
  "functions": [
    {
      "name": "orderTypeDecisionFunction",
      "type": "custom",
      "operation": "dmn://com.anax.decisions/Order Type Routing"
    },
    {
      "name": "sysoutFunction",
      "type": "custom",
      "operation": "sysout"
    },
    {
      "name": "partyLookupFunction",
      "type": "custom",
      "operation": "anax://partyLookupService/lookup"
    },
    {
      "name": "startChildWorkflowFunction",
      "type": "custom",
      "operation": "anax://workflowRouterService/startChild"
    }
  ],
  "states": [
    {
      "name": "ReceiveControlRecord",
      "type": "event",
      "onEvents": [
        {
          "eventRefs": ["ControlRecordCreatedEvent"],
          "actionMode": "sequential",
          "actions": []
        }
      ],
      "transition": "EvaluateOrderType"
    },
    {
      "name": "EvaluateOrderType",
      "type": "operation",
      "actions": [
        {
          "name": "evaluateOrderTypeDecision",
          "functionRef": {
            "refName": "orderTypeDecisionFunction",
            "arguments": {
              "message": "Evaluating order type for: ${ .controlRecordId }"
            }
          }
        }
      ],
      "transition": "CheckOrderTypeRoutable"
    },
    {
      "name": "CheckOrderTypeRoutable",
      "type": "switch",
      "dataConditions": [
        {
          "condition": "${ .orderTypeRoutable == false }",
          "transition": "BypassToCaseMgmt"
        }
      ],
      "defaultCondition": {
        "transition": "AttemptMapping"
      }
    },
    {
      "name": "BypassToCaseMgmt",
      "type": "operation",
      "actions": [
        {
          "name": "logBypass",
          "functionRef": {
            "refName": "sysoutFunction",
            "arguments": {
              "message": "Order type not routable — bypassing to case management: ${ .controlRecordId }"
            }
          }
        }
      ],
      "end": true
    },
    {
      "name": "AttemptMapping",
      "type": "operation",
      "actions": [
        {
          "name": "mockAclCall",
          "functionRef": {
            "refName": "sysoutFunction",
            "arguments": {
              "message": "Attempting to map order: ${ .controlRecordId }"
            }
          }
        }
      ],
      "transition": "CheckForMissingTaxId"
    },
    {
      "name": "CheckForMissingTaxId",
      "type": "switch",
      "dataConditions": [
        {
          "condition": "${ (.taxId == null) or (.taxId == \"\") }",
          "transition": "RequireManualTaxIdCorrection"
        }
      ],
      "defaultCondition": {
        "transition": "PartyLookup"
      }
    },
    {
      "name": "RequireManualTaxIdCorrection",
      "type": "callback",
      "action": {
        "name": "publishExceptionToControlRecord",
        "functionRef": {
          "refName": "sysoutFunction",
          "arguments": {
            "message": "Missing Tax ID. Exception logged for ${ .controlRecordId }"
          }
        }
      },
      "eventRef": "HumanCorrectionSubmittedEvent",
      "transition": "PartyLookup"
    },
    {
      "name": "PartyLookup",
      "type": "operation",
      "actions": [
        {
          "name": "lookupParty",
          "functionRef": {
            "refName": "partyLookupFunction",
            "arguments": {
              "message": "Performing party lookup for: ${ .controlRecordId }"
            }
          }
        }
      ],
      "transition": "CheckPartyFound"
    },
    {
      "name": "CheckPartyFound",
      "type": "switch",
      "dataConditions": [
        {
          "condition": "${ .partyFound == false }",
          "transition": "RouteToNoPartyWorkflow"
        }
      ],
      "defaultCondition": {
        "transition": "DispatchOrder"
      }
    },
    {
      "name": "RouteToNoPartyWorkflow",
      "type": "operation",
      "actions": [
        {
          "name": "routeToChildWorkflow",
          "functionRef": {
            "refName": "startChildWorkflowFunction",
            "arguments": {
              "workflowId": "no-party-found",
              "message": "No party found — routing to manual resolution: ${ .controlRecordId }"
            }
          }
        }
      ],
      "end": true
    },
    {
      "name": "DispatchOrder",
      "type": "operation",
      "actions": [
        {
          "name": "logDispatch",
          "functionRef": {
            "refName": "sysoutFunction",
            "arguments": {
              "message": "Order Dispatched Successfully: ${ .controlRecordId }"
            }
          }
        }
      ],
      "end": true
    }
  ]
}
```

---

## 5. Service Stubs

### 5.1 HelloService.java

**Path in POC:** `order-routing-service/src/main/java/com/anax/routing/service/HelloService.java`

Demonstrates the `anax://` bean invocation contract: method accepts `Map<String, Object>`, returns `Map<String, Object>`.

```java
package com.anax.routing.service;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Example Spring service invoked via the {@code anax://helloService/greet} URI.
 *
 * <p>
 * Demonstrates the {@code anax://} protocol: the workflow passes the
 * {@code name}
 * field from its data context as an argument; the service returns a
 * {@code greeting}
 * that is merged back into workflow data.
 */
@Component("helloService")
public class HelloService {

    public Map<String, Object> greet(Map<String, Object> params) {
        String name = (String) params.getOrDefault("name", "World");
        return Map.of("greeting", "Hello, " + name + "!");
    }
}
```

---

## Appendix: File Inventory

| #   | File                               | Role                                         | Lines |
| --- | ---------------------------------- | -------------------------------------------- | ----- |
| 1   | `DmnFunctionTypeHandler.java`      | Codegen SPI — `dmn://` scheme                | ~88   |
| 2   | `AnaxFunctionTypeHandler.java`     | Codegen SPI — `anax://` scheme               | ~89   |
| 3   | `FunctionTypeHandler` (SPI file)   | ServiceLoader registration                   | 2     |
| 4   | `DmnWorkItemHandler.java`          | Runtime — in-process DMN evaluation          | ~75   |
| 5   | `AnaxWorkItemHandler.java`         | Runtime — Spring bean invocation             | ~80   |
| 6   | `CustomWorkItemHandlerConfig.java` | Runtime — handler registration               | ~35   |
| 7   | `build.gradle`                     | Full codegen wiring + dependencies           | ~200  |
| 8   | `hello-world.sw.json`              | Demo workflow (`anax://` + `dmn://`)         | ~60   |
| 9   | `utility-order-mapping.sw.json`    | Production workflow (events, DMN, callbacks) | ~180  |
| 10  | `HelloService.java`                | Example `anax://` bean                       | ~20   |
