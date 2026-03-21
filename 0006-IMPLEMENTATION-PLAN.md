# Implementation Plan: Kogito Custom URI Spring Boot Starter

**Parent ADR:** [0006-kogito-custom-uri-spring-boot-starter.md](0006-kogito-custom-uri-spring-boot-starter.md)

**Date:** March 2026

---

## Overview

This document provides a step-by-step prompt sequence for scaffolding the `anax-kogito-starter` repository using Copilot. Each prompt is self-contained with enough context for an AI agent to execute it correctly in a fresh conversation.

**Constraints carried forward:**

- Java 17, Spring Boot 3.3.7, Gradle 8.10+, Kogito 10.1.0
- Three publishable modules + one sample/integration-test module
- No Maven support needed

---

## Reference Materials

Before starting, the agent should be provided with these four documents (in order of priority):

| #   | Document                                                                                       | Purpose                                                                                                                                                                                     |
| --- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | [0006-kogito-custom-uri-spring-boot-starter.md](0006-kogito-custom-uri-spring-boot-starter.md) | **Architecture** — The ADR defining what to build: module structure, two-phase execution model, URI schemes, auto-configuration design, metadata catalog                                    |
| 2   | [0006-IMPLEMENTATION-PLAN.md](0006-IMPLEMENTATION-PLAN.md)                                     | **Prompts** — This file. Step-by-step instructions for each source file                                                                                                                     |
| 3   | [0006-POC-REFERENCE.md](0006-POC-REFERENCE.md)                                                 | **Proven code** — All 10 working POC source files from `order-routing-service`, verbatim. Use as the authoritative reference for Kogito API patterns, class structures, and parameter names |
| 4   | [0006-README-TEMPLATE.md](0006-README-TEMPLATE.md)                                             | **Documentation** — The target README for the starter repository                                                                                                                            |

The POC reference (`0006-POC-REFERENCE.md`) contains:

- **Codegen SPI**: `DmnFunctionTypeHandler`, `AnaxFunctionTypeHandler`, SPI registration file
- **Runtime handlers**: `DmnWorkItemHandler`, `AnaxWorkItemHandler`, `CustomWorkItemHandlerConfig`
- **Build config**: Complete `build.gradle` with `generateKogitoSources` task (reflective Kogito codegen invocation)
- **Workflows**: `hello-world.sw.json` (demo) and `utility-order-mapping.sw.json` (production-grade)
- **Service stub**: `HelloService.java` (example `anax://` bean contract)

Each prompt below may reference specific POC files — consult the POC reference for the complete source.

---

## Phase 0: Repository Bootstrap

### Prompt 0.1 — Create the multi-module Gradle project

```
Create a new multi-module Gradle project at /workspaces/anax-kogito-starter with these specifications:

- Gradle wrapper version 8.10
- Java 17 (toolchain)
- Group: com.anax
- Version: 0.1.0-SNAPSHOT

gradle.properties:
  kogitoVersion=10.1.0
  springBootVersion=3.3.7
  springDependencyManagementVersion=1.1.7

settings.gradle with four subprojects:
  - anax-kogito-codegen-extensions
  - anax-kogito-spring-boot-starter
  - anax-kogito-codegen-plugin
  - anax-kogito-sample

Root build.gradle:
  - Apply 'java-library' to all subprojects except anax-kogito-codegen-plugin
  - Set Java toolchain to 17
  - Set all subprojects to use group 'com.anax' and version from gradle.properties
  - Apply maven-publish to all subprojects

Create empty build.gradle files in each subproject directory.
Do NOT add dependencies yet — those come in later prompts.
```

---

## Phase 1: Codegen Extensions Module

### Prompt 1.1 — Build file for codegen extensions

```
In /workspaces/anax-kogito-starter/anax-kogito-codegen-extensions/build.gradle:

This is a plain Java library (not Spring Boot). It compiles against Kogito codegen
APIs and is consumed at BUILD TIME by the Kogito code generator via ServiceLoader.
It must NOT have any Spring Boot dependencies.

Dependencies (all 'compileOnly' — the codegen engine provides them at runtime):
  - org.kie.kogito:kogito-serverless-workflow-builder  (from Kogito BOM)
  - io.serverlessworkflow:serverlessworkflow-api        (from Kogito BOM transitives)

Import the Kogito BOM for version management:
  implementation platform("org.kie.kogito:kogito-bom:${kogitoVersion}")

The jar must include the META-INF/services file so ServiceLoader discovers the handlers.
```

### Prompt 1.2 — DmnFunctionTypeHandler

```
Create /workspaces/anax-kogito-starter/anax-kogito-codegen-extensions/src/main/java/com/anax/kogito/codegen/DmnFunctionTypeHandler.java

This is a Kogito codegen-time SPI extension. It extends WorkItemTypeHandler and
teaches the code generator to emit a WorkItemNode for custom functions using the
"dmn://" URI scheme.

Behavior:
- type() returns "dmn"
- isCustom() returns true
- fillWorkItemHandler() parses "dmn://namespace/Model Name" from
  FunctionTypeHandlerFactory.trimCustomOperation(functionDef), stripping the
  leading "//" if present
- Sets workName("dmn") on the factory
- Sets workParameter("DmnNamespace", namespace) and
  workParameter("ModelName", modelName)

Package: com.anax.kogito.codegen

Imports needed:
  - io.serverlessworkflow.api.Workflow
  - io.serverlessworkflow.api.functions.FunctionDefinition
  - org.jbpm.ruleflow.core.RuleFlowNodeContainerFactory
  - org.jbpm.ruleflow.core.factory.WorkItemNodeFactory
  - org.kie.kogito.serverless.workflow.parser.FunctionTypeHandlerFactory
  - org.kie.kogito.serverless.workflow.parser.ParserContext
  - org.kie.kogito.serverless.workflow.parser.types.WorkItemTypeHandler

Use public static final constants for the parameter names:
  PARAM_NAMESPACE = "DmnNamespace"
  PARAM_MODEL_NAME = "ModelName"
  DMN_SCHEME = "dmn"
```

### Prompt 1.3 — AnaxFunctionTypeHandler

```
Create /workspaces/anax-kogito-starter/anax-kogito-codegen-extensions/src/main/java/com/anax/kogito/codegen/AnaxFunctionTypeHandler.java

Same pattern as DmnFunctionTypeHandler but for the "anax://" URI scheme.

Behavior:
- type() returns "anax"
- isCustom() returns true
- fillWorkItemHandler() parses "anax://beanName/methodName" from
  FunctionTypeHandlerFactory.trimCustomOperation(functionDef), stripping "//"
- If no method segment, defaults to "execute"
- Sets workName("anax"), workParameter("BeanName", beanName),
  workParameter("MethodName", methodName)

Package: com.anax.kogito.codegen

Use public static final constants:
  PARAM_BEAN_NAME = "BeanName"
  PARAM_METHOD_NAME = "MethodName"
  ANAX_SCHEME = "anax"
```

### Prompt 1.4 — MapFunctionTypeHandler

```
Create /workspaces/anax-kogito-starter/anax-kogito-codegen-extensions/src/main/java/com/anax/kogito/codegen/MapFunctionTypeHandler.java

Same pattern as DmnFunctionTypeHandler but for the "map://" URI scheme.
This handler teaches codegen to emit a WorkItemNode for data-mapping
transformation functions.

Behavior:
- type() returns "map"
- isCustom() returns true
- fillWorkItemHandler() parses "map://mappingName" from
  FunctionTypeHandlerFactory.trimCustomOperation(functionDef), stripping "//"
- Sets workName("map"), workParameter("MappingName", mappingName)

Package: com.anax.kogito.codegen

Use public static final constants:
  PARAM_MAPPING_NAME = "MappingName"
  MAP_SCHEME = "map"
```

### Prompt 1.5 — SPI registration file

```
Create /workspaces/anax-kogito-starter/anax-kogito-codegen-extensions/src/main/resources/META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler

Contents (three lines, no blank lines at end):
com.anax.kogito.codegen.DmnFunctionTypeHandler
com.anax.kogito.codegen.AnaxFunctionTypeHandler
com.anax.kogito.codegen.MapFunctionTypeHandler
```

### Prompt 1.6 — Verify codegen extensions compile

```
Run: gradle :anax-kogito-codegen-extensions:compileJava
Verify BUILD SUCCESSFUL with no errors.
```

---

## Phase 2: Spring Boot Starter Module

### Prompt 2.1 — Build file for the starter

```
In /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/build.gradle:

This is a Spring Boot starter library (not an executable app). Apply:
  - java-library plugin (already from root)
  - Do NOT apply the Spring Boot plugin (no bootJar)
  - DO apply io.spring.dependency-management and import the Spring Boot BOM
    and the Kogito BOM

Dependencies:
  api:
    - org.kie.kogito:kogito-api
    - org.kie.kogito:jbpm-flow
    - org.kie.kogito:process-workitems  (provides DefaultKogitoWorkItemHandler)
  compileOnly:
    - org.kie.kogito:kogito-dmn  (DMN handler is conditional)
    - org.springframework.boot:spring-boot-autoconfigure
    - org.springframework:spring-context
  annotationProcessor:
    - org.springframework.boot:spring-boot-autoconfigure-processor

This jar must NOT be a fat jar. It's consumed as a library dependency.
```

### Prompt 2.2 — DmnWorkItemHandler

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/DmnWorkItemHandler.java

This is a runtime work-item handler for the "dmn" work-item type.
It is NOT annotated with @Component — it will be created as a @Bean
in the auto-configuration class.

Behavior:
- Extends DefaultKogitoWorkItemHandler
- Constructor takes DecisionModels as parameter (stored as field)
- Override activateWorkItemHandler():
  1. Extract "DmnNamespace" and "ModelName" from workItem parameters
  2. Build input map from remaining parameters (excluding DmnNamespace, ModelName, TaskName)
  3. Get DecisionModel from decisionModels.getDecisionModel(namespace, modelName)
  4. Create DMN context, evaluateAll()
  5. Collect successful decision results into output map
  6. Call manager.completeWorkItem() with results
  7. Return Optional.empty()

Package: com.anax.kogito.autoconfigure

Do NOT use @Autowired — use constructor injection. The bean wiring
happens in the auto-configuration class.
```

### Prompt 2.3 — AnaxWorkItemHandler

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/AnaxWorkItemHandler.java

Runtime work-item handler for the "anax" work-item type.
NOT annotated with @Component.

Behavior:
- Extends DefaultKogitoWorkItemHandler
- Constructor takes ApplicationContext as parameter
- Override activateWorkItemHandler():
  1. Extract "BeanName" and "MethodName" from workItem parameters
  2. Build param map (excluding BeanName, MethodName, TaskName)
  3. Look up Spring bean by name: applicationContext.getBean(beanName)
  4. Find method: bean.getClass().getMethod(methodName, Map.class)
  5. Invoke method, cast result to Map<String, Object>
  6. Call manager.completeWorkItem() with result
  7. Return Optional.empty()
  8. Throw IllegalArgumentException for NoSuchMethodException,
     RuntimeException for other invocation errors

Package: com.anax.kogito.autoconfigure
Constructor injection, no @Autowired.
```

### Prompt 2.4 — MapWorkItemHandler

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/MapWorkItemHandler.java

Runtime work-item handler for the "map" work-item type.
NOT annotated with @Component.

Behavior:
- Extends DefaultKogitoWorkItemHandler
- Constructor takes ApplicationContext as parameter
- Override activateWorkItemHandler():
  1. Extract "MappingName" from workItem parameters
  2. Build param map (excluding MappingName, TaskName)
  3. Look up a Spring bean named mappingName from applicationContext
  4. The bean must implement java.util.function.Function<Map<String,Object>, Map<String,Object>>
  5. Apply the function to the param map
  6. Call manager.completeWorkItem() with the result
  7. Return Optional.empty()

Package: com.anax.kogito.autoconfigure
Constructor injection, no @Autowired.
```

### Prompt 2.5 — Auto-configuration class

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/AnaxKogitoAutoConfiguration.java

Spring Boot 3 auto-configuration class.

Annotations:
  @AutoConfiguration
  @ConditionalOnClass(name = "org.kie.kogito.process.Process")

Beans:
  1. @Bean @ConditionalOnMissingBean
     AnaxWorkItemHandler anaxWorkItemHandler(ApplicationContext ctx)

  2. @Bean @ConditionalOnMissingBean
     @ConditionalOnClass(name = "org.kie.kogito.decision.DecisionModels")
     DmnWorkItemHandler dmnWorkItemHandler(DecisionModels decisionModels)

  3. @Bean @ConditionalOnMissingBean
     MapWorkItemHandler mapWorkItemHandler(ApplicationContext ctx)

  4. @Bean @ConditionalOnMissingBean(WorkItemHandlerConfig.class)
     DefaultWorkItemHandlerConfig anaxKogitoWorkItemHandlerConfig(
         AnaxWorkItemHandler anaxHandler,
         Optional<DmnWorkItemHandler> dmnHandler,
         MapWorkItemHandler mapHandler)
     — Always registers "anax" and "map" handlers
     — Registers "dmn" handler if present (Optional)

Package: com.anax.kogito.autoconfigure

Import:
  - org.springframework.boot.autoconfigure.AutoConfiguration
  - org.springframework.boot.autoconfigure.condition.*
  - org.kie.kogito.process.impl.DefaultWorkItemHandlerConfig
```

### Prompt 2.6 — AnaxKogitoProperties

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/AnaxKogitoProperties.java

@ConfigurationProperties(prefix = "anax")
public class AnaxKogitoProperties {
    private CatalogProperties catalog = new CatalogProperties();

    public static class CatalogProperties {
        private boolean enabled = true;
        private String formSchemasUrl;  // optional external forms-service URL

        // getters + setters
    }

    // getters + setters
}

Package: com.anax.kogito.autoconfigure
```

### Prompt 2.7 — CatalogModel

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/catalog/CatalogModel.java

Java records representing the catalog.json structure:

  public record Catalog(
      String schemaVersion,
      String generatedAt,
      List<SchemeEntry> schemes,
      List<DmnModelEntry> dmnModels,
      List<WorkflowEntry> workflows,
      List<SpringBeanEntry> springBeans,
      List<FormSchemaEntry> formSchemas
  ) {}

  public record SchemeEntry(
      String scheme, String description, String uriPattern,
      List<ParameterEntry> parameters, String handler
  ) {}

  public record ParameterEntry(
      String name, String description, String source
  ) {}

  public record DmnModelEntry(
      String namespace, String name, String uri,
      String resource, List<String> inputs, List<String> outputs
  ) {}

  public record WorkflowEntry(
      String id, String name, String resource,
      List<EventEntry> events, List<FunctionEntry> functions
  ) {}

  public record EventEntry(
      String name, String type, String kind
  ) {}

  public record FunctionEntry(
      String name, String operation
  ) {}

  public record SpringBeanEntry(
      String beanName, String className,
      List<MethodEntry> methods, String uri
  ) {}

  public record MethodEntry(
      String name, String parameterType, String returnType
  ) {}

  public record FormSchemaEntry(
      String formId, String title, String source, List<String> fields
  ) {}

Package: com.anax.kogito.catalog
```

### Prompt 2.8 — AnaxCatalogService

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/catalog/AnaxCatalogService.java

@Service that loads and serves the metadata catalog.

Behavior:
- On construction, reads META-INF/anax/catalog.json from the classpath
  using ObjectMapper into a CatalogModel.Catalog record
- If the file is missing, builds a minimal catalog with just the scheme
  definitions (dmn://, anax://, map://)
- Provides methods:
  - getCatalog() → full Catalog
  - getSchemes() → List<SchemeEntry>
  - getDmnModels() → List<DmnModelEntry>
  - getWorkflows() → List<WorkflowEntry>
  - getSpringBeans() → List<SpringBeanEntry>
- At startup (@PostConstruct), augments the static catalog with a live
  ApplicationContext scan:
  - Finds all beans whose class has a public method matching
    (Map<String,Object>) → Map<String,Object> signature
  - Adds them to springBeans if not already present in the static manifest

Package: com.anax.kogito.catalog
Constructor injection: ObjectMapper, ApplicationContext, ResourceLoader
```

### Prompt 2.9 — AnaxCatalogController

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/catalog/AnaxCatalogController.java

@RestController @RequestMapping("/anax/catalog")
@ConditionalOnProperty(prefix = "anax.catalog", name = "enabled",
                       matchIfMissing = true)

Endpoints:
  GET /anax/catalog           → full Catalog JSON
  GET /anax/catalog/schemes   → List<SchemeEntry>
  GET /anax/catalog/dmn       → List<DmnModelEntry>
  GET /anax/catalog/workflows → List<WorkflowEntry>
  GET /anax/catalog/beans     → List<SpringBeanEntry>

All endpoints return 200 with JSON. Inject AnaxCatalogService.

Package: com.anax.kogito.catalog
```

### Prompt 2.10 — AutoConfiguration.imports registration

```
Create /workspaces/anax-kogito-starter/anax-kogito-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

Contents (single line):
com.anax.kogito.autoconfigure.AnaxKogitoAutoConfiguration
```

### Prompt 2.11 — Verify starter compiles

```
Run: gradle :anax-kogito-spring-boot-starter:compileJava
Verify BUILD SUCCESSFUL.
```

---

## Phase 3: Gradle Plugin Module

### Prompt 3.1 — Build file for the Gradle plugin

```
In /workspaces/anax-kogito-starter/anax-kogito-codegen-plugin/build.gradle:

Apply plugins:
  - java-gradle-plugin
  - maven-publish

gradlePlugin {
    plugins {
        anaxKogitoCodegen {
            id = 'com.anax.kogito-codegen'
            implementationClass = 'com.anax.kogito.gradle.AnaxKogitoCodegenPlugin'
        }
    }
}

Dependencies:
  implementation gradleApi()
  // No Kogito dependencies here — the plugin resolves them dynamically
  // via a 'kogitoCodegen' configuration it creates in the consuming project
```

### Prompt 3.2 — AnaxKogitoCodegenPlugin

```
Create /workspaces/anax-kogito-starter/anax-kogito-codegen-plugin/src/main/java/com/anax/kogito/gradle/AnaxKogitoCodegenPlugin.java

This is a Gradle Plugin<Project> that automates Kogito code generation.

apply(Project project):

1. Apply 'java' plugin if not already applied

2. Create 'kogitoCodegen' configuration (not visible to consumers)

3. Add default dependencies to kogitoCodegen:
   - org.kie.kogito:kogito-codegen-manager:${kogitoVersion}
   - com.anax:anax-kogito-codegen-extensions:${project.version}
   - io.smallrye:smallrye-open-api-core:3.10.0

4. Read kogitoVersion from project.findProperty("kogitoVersion") with
   default "10.1.0"

5. Register 'generateKogitoSources' task:
   - inputs: src/main/resources/**/*.sw.json, **/*.dmn
   - outputs: build/generated/sources/kogito, build/generated/resources/kogito
   - doLast: Build URLClassLoader from kogitoCodegen + runtimeClasspath,
     set Thread.contextClassLoader, invoke Kogito codegen reflectively
     (same logic as the POC generateKogitoSources task)

6. Wire main sourceSet:
   - Add build/generated/sources/kogito to java srcDirs
   - Add build/generated/resources/kogito to resources srcDirs

7. Wire task dependencies:
   - compileJava dependsOn generateKogitoSources
   - processResources dependsOn generateKogitoSources

Package: com.anax.kogito.gradle

IMPORTANT: Use setSrcDirs() (not srcDir/srcDirs) when adding generated
source directories to avoid duplicate resource errors in Gradle 9+.
Actually, for the plugin, use srcDir() to APPEND the generated dirs
to the consuming project's existing source dirs (don't replace their dirs).
But ensure duplicatesStrategy is set to EXCLUDE on processResources.
```

### Prompt 3.3 — CatalogManifestTask

```
Create /workspaces/anax-kogito-starter/anax-kogito-codegen-plugin/src/main/java/com/anax/kogito/gradle/CatalogManifestTask.java

A Gradle DefaultTask that generates META-INF/anax/catalog.json by
scanning project resources.

Inputs:
  - src/main/resources/**/*.sw.json
  - src/main/resources/**/*.dmn
  - src/main/java/**/*.java (for Spring bean scanning)

Outputs:
  - build/generated/resources/kogito/META-INF/anax/catalog.json

@TaskAction generate():
  1. Initialize catalog with static scheme definitions for dmn://, anax://, map://
     (these are always present — they come from the codegen extensions)

  2. Scan *.dmn files:
     - Parse XML to extract <definitions namespace="..." name="...">
     - Extract <inputData> and <decision> names for inputs/outputs
     - Build DmnModelEntry with constructed URI: dmn://{namespace}/{name}

  3. Scan *.sw.json files:
     - Parse JSON to extract id, name, events[], functions[]
     - Build WorkflowEntry for each workflow

  4. Scan *.java files (basic regex, not full AST):
     - Look for classes annotated with @Component or @Service
     - Look for public methods with Map<String, Object> parameter
     - Extract bean name from annotation value or lowercase class name
     - Build SpringBeanEntry with constructed URI: anax://{beanName}/{methodName}

  5. Write catalog.json with schemaVersion "1.0" and ISO-8601 generatedAt

Register this task in the plugin's apply() method:
  - Named 'generateAnaxCatalog'
  - Runs after generateKogitoSources
  - processResources dependsOn generateAnaxCatalog

Package: com.anax.kogito.gradle

Note: The Java scanning in step 4 is best-effort for the static manifest.
The runtime AnaxCatalogService augments this with a live ApplicationContext
scan, so missing beans in the static manifest are still discoverable at
runtime.
```

### Prompt 3.4 — Verify plugin compiles

```
Run: gradle :anax-kogito-codegen-plugin:compileJava
Verify BUILD SUCCESSFUL.
```

---

## Phase 4: Sample Application

### Prompt 4.1 — Sample build file

```
In /workspaces/anax-kogito-starter/anax-kogito-sample/build.gradle:

This is a Spring Boot application that demonstrates the starter.

Apply plugins:
  - org.springframework.boot (version from gradle.properties)
  - io.spring.dependency-management
  - com.anax.kogito-codegen (from the sibling module — use includeBuild or
    buildSrc approach for local dev)

Dependencies:
  implementation project(':anax-kogito-spring-boot-starter')
  implementation 'org.springframework.boot:spring-boot-starter-web'
  implementation 'org.kie.kogito:kogito-dmn'
  implementation 'org.kie.kogito:kogito-serverless-workflow-builder'
  implementation 'org.jbpm:jbpm-spring-boot-starter'
  implementation 'org.drools:drools-decisions-spring-boot-starter'

Note: For local development with the plugin from a sibling module,
add to settings.gradle:
  includeBuild('anax-kogito-codegen-plugin') {
      dependencySubstitution { ... }
  }
Or use composite builds. The prompt for settings.gradle should handle this.
```

### Prompt 4.2 — Sample application class

```
Create /workspaces/anax-kogito-starter/anax-kogito-sample/src/main/java/com/example/demo/DemoApplication.java

Standard @SpringBootApplication.
scanBasePackages: "com.example", "org.kie.kogito"

Also create a simple GreetingService:
/workspaces/anax-kogito-starter/anax-kogito-sample/src/main/java/com/example/demo/GreetingService.java

@Component("greetingService")
public class GreetingService {
    public Map<String, Object> greet(Map<String, Object> params) {
        String name = (String) params.getOrDefault("name", "World");
        return Map.of("greeting", "Hello, " + name + "!");
    }
}
```

### Prompt 4.3 — Sample workflow

```
Create /workspaces/anax-kogito-starter/anax-kogito-sample/src/main/resources/hello-world.sw.json

A simple Serverless Workflow (specVersion 0.8) with:
  - id: "hello-world"
  - Two custom functions:
    1. greetFunction: "anax://greetingService/greet"
    2. logFunction: "sysout"
  - Two states:
    1. "Greet" — calls greetFunction with arguments { "name": "${ .name }" }
    2. "LogResult" — logs "${ .greeting }" via sysout, then ends

Also copy the order-type-routing.dmn from the POC if you want to test
the dmn:// scheme. Otherwise the hello-world.sw.json is sufficient for
a minimal demo.
```

### Prompt 4.4 — Sample application.yml

```
Create /workspaces/anax-kogito-starter/anax-kogito-sample/src/main/resources/application.yml

server:
  port: 8085

spring:
  application:
    name: anax-kogito-sample
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration

kogito:
  workflow:
    version-strategy: workflow
```

### Prompt 4.5 — Build and run the sample

```
Run:
  gradle :anax-kogito-sample:bootRun

Then test:
  curl -s -X POST http://localhost:8085/hello-world \
    -H "Content-Type: application/json" \
    -d '{"name": "World"}' | jq .

Expected: workflow instance created, AnaxWorkItemHandler calls
greetingService.greet(), adds greeting to workflowdata.

Also test the catalog endpoint:
  curl -s http://localhost:8085/anax/catalog | jq .

Expected: catalog JSON listing the dmn://, anax://, map:// schemes,
the hello-world workflow, and the greetingService bean.
```

---

## Phase 5: Copilot Integration & Documentation

### Prompt 5.1 — Copilot instructions template

````
Create /workspaces/anax-kogito-starter/anax-kogito-sample/.github/copilot-instructions.md

This file teaches GitHub Copilot how to author CNCF Serverless Workflow
definitions using the Anax starter's custom URI schemes. It will be
read automatically by Copilot when a developer works in the project.

Contents:

## Anax Serverless Workflow Conventions

This project uses the Anax Kogito Spring Boot Starter to build
event-driven CNCF Serverless Workflows (spec v0.8).

### Custom Function URI Schemes

All custom functions use `"type": "custom"` in sw.json:

| Scheme    | Purpose                        | URI Format                      |
|-----------|---------------------------------|---------------------------------|
| `dmn://`  | Evaluate a DMN decision model  | `dmn://{namespace}/{modelName}` |
| `anax://` | Invoke a Spring bean method    | `anax://{beanName}/{methodName}`|
| `map://`  | Apply a data mapping           | `map://{mappingName}`           |

### Discovering Available Operations

Project metadata is in `build/generated/resources/kogito/META-INF/anax/catalog.json`.
At runtime, query `GET /anax/catalog` for the full inventory of:
- DMN models (namespace, name, inputs, outputs)
- Spring beans callable via anax://
- Deployed workflows with events and function references

### Event-Driven Patterns

- Workflows consume CloudEvents via Kafka (`"kind": "consumed"`)
- Callback states implement human-in-the-loop with `eventRef`
- Event `type` must match the CloudEvent type from upstream services

### Function Definition Template

```json
{
  "name": "myFunction",
  "type": "custom",
  "operation": "anax://myService/myMethod"
}
````

```

### Prompt 5.2 — README.md

```

Create /workspaces/anax-kogito-starter/README.md

Comprehensive README with:

1. Project title, badges (build status, version, Java 17, Spring Boot 3)
2. One-paragraph description: what the starter does
3. Quick Start (3-step setup: plugin, dependency, sw.json)
4. Module overview table
5. Custom URI Scheme reference (dmn://, anax://, map://)
6. Metadata Catalog section (build-time manifest + runtime endpoint)
7. Copilot Integration section
8. Configuration properties table
9. Building from source instructions
10. Detailed usage examples

See the README content created alongside this plan for exact text.

```

---

## Phase 6: Clean Up the POC

### Prompt 5.1 — Remove POC code from order-routing-service

```

In the original /workspaces/serverless-workflow/order-routing-service:

1. Delete src/codegenExtensions/ directory entirely
2. Delete src/main/java/com/anax/routing/kogito/ directory entirely
3. Delete src/main/java/com/anax/routing/service/HelloService.java
4. Delete src/main/resources/hello-world.sw.json

5. Update build.gradle:
   - Remove the 'codegenExtensions' sourceSet
   - Remove 'compileClasspath += sourceSets.codegenExtensions.output' from main
   - Apply the new plugin: id 'com.anax.kogito-codegen'
   - Add: implementation 'com.anax:anax-kogito-spring-boot-starter:0.1.0'
   - Remove the hand-written generateKogitoSources task

6. Keep:
   - PartyLookupService.java (domain code)
   - WorkflowRouterService.java (domain code)
   - utility-order-mapping.sw.json (still uses dmn:// and anax:// — now powered by starter)
   - no-party-found.sw.json
   - order-type-routing.dmn

```

---

## Execution Notes

- **Run prompts in order** — each phase depends on the previous phase compiling successfully
- **Verify after each phase** — run the compile command before moving to the next phase
- **Phase 3 is the hardest** — the Gradle plugin has complex classloader and reflection logic; expect iteration
- **Phase 5 creates documentation** — the copilot-instructions.md and README.md are essential for discoverability; do not skip
- **Phase 6 is optional** — do it only after the starter is published (or use composite builds for local dev)
- **Testing** — each prompt can include a "verify" step; add JUnit tests as a follow-up phase

## Prompt Summary

| Phase | Prompts | Focus |
|-------|---------|-------|
| 0 | 0.1 | Repository bootstrap |
| 1 | 1.1–1.6 | Codegen extensions (dmn://, anax://, map://) |
| 2 | 2.1–2.11 | Spring Boot starter (handlers + catalog) |
| 3 | 3.1–3.4 | Gradle plugin (codegen + manifest generation) |
| 4 | 4.1–4.5 | Sample application |
| 5 | 5.1–5.2 | Copilot instructions + README |
| 6 | 6.1 | POC cleanup |
```
