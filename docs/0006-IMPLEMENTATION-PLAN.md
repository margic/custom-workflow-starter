# Implementation Plan: Custom Workflow Spring Boot Starter

**Parent ADR:** [0006-kogito-custom-uri-spring-boot-starter.md](0006-kogito-custom-uri-spring-boot-starter.md)

**Date:** March 2026

---

## Progress Tracker

| Phase | Description | Status | Commit |
|-------|-------------|--------|--------|
| 0 | Repository Bootstrap | ✅ Done | `c04f9bf` |
| 1 | Codegen Extensions Module | ✅ Done | `dce85b7` |
| 2 | Spring Boot Starter Module | ✅ Done | `512c609` |
| 3 | Gradle Plugin Module | ✅ Done | `19b4d5c` |
| 4 | Sample Application | ✅ Done | `52da409` |
| 5 | Copilot Integration & Docs | ✅ Done | *(this commit)* |

**Branch:** `feature/spring-boot-starter`
**Last updated:** 2026-03-22

---

> **Note on Phase 3:** Prompts 3.1–3.6 below provide a high-level overview of the Gradle plugin module. For implementation, follow the detailed [Phase 3 Specification](0007-phase3-plugin-spec.md) §8 checklist (items 3.1–3.13), which supersedes these prompts with confirmed API contracts, a `MetadataServerClient` abstraction, utility classes (`UriParser`, `SwJsonParser`), the `GenerateMcpConfigTask`, and a comprehensive test strategy.

## Overview

This document provides a step-by-step prompt sequence for scaffolding the `custom-workflow-starter` repository using Copilot. Each prompt is self-contained with enough context for an AI agent to execute it correctly in a fresh conversation.

**Constraints carried forward:**

- Java 17, Spring Boot 3.3.7, Gradle 8.10+, Kogito 10.1.0
- Three publishable modules + one sample/integration-test module
- No Maven support needed
- Consumers declare zero `org.kie.kogito` dependencies — the starter manages all Kogito deps transitively
- Governance assets (DMN models, Jolt specs) are committed to `src/main/resources/` — builds are self-contained with no external service dependencies
- `map://` uses Jolt transformation specs committed at `src/main/resources/META-INF/anax/mappings/` (Jolt engine wired in a later iteration; initial handler is a stub)
- Runtime registration: the starter publishes the catalog to the metadata server on application startup for observability and drift detection

---

## Reference Materials

Before starting, the agent should be provided with these four documents (in order of priority):

| #   | Document                                                                                       | Purpose                                                                                                                                                                                     |
| --- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | [0006-kogito-custom-uri-spring-boot-starter.md](0006-kogito-custom-uri-spring-boot-starter.md) | **Architecture** — The ADR defining what to build: module structure, governance asset lifecycle, URI schemes, auto-configuration design, metadata catalog, runtime registration |
| 2   | [0006-IMPLEMENTATION-PLAN.md](0006-IMPLEMENTATION-PLAN.md)                                     | **Prompts** — This file. Step-by-step instructions for each source file                                                                                                                     |
| 3   | [0006-POC-REFERENCE.md](0006-POC-REFERENCE.md)                                                 | **Proven code** — All 10 working POC source files from `order-routing-service`, verbatim. Use as the authoritative reference for Kogito API patterns, class structures, and parameter names |
| 4   | [0006-README-TEMPLATE.md](0006-README-TEMPLATE.md)                                             | **Documentation** — The target README for the starter repository                                                                                                                            |

The POC reference (`0006-POC-REFERENCE.md`) contains:

- **Codegen SPI**: `DmnFunctionTypeHandler`, `AnaxFunctionTypeHandler`, SPI registration file
- **Runtime handlers**: `DmnWorkItemHandler`, `AnaxWorkItemHandler`, `CustomWorkItemHandlerConfig`
- **Build config**: Complete `build.gradle` with `generateKogitoSources` task (reflective Kogito codegen invocation)
- **Workflows**: `hello-world.sw.json` (demo) and `utility-order-mapping.sw.json` (production-grade)
- **Service stub**: `HelloService.java` (example `anax://` bean contract)

**Note:** The POC does not include `MapFunctionTypeHandler` or `MapWorkItemHandler` — these are new. The `map://` scheme follows the same SPI pattern as `dmn://` and `anax://` but uses Jolt transformation specs committed in the project. See prompts 1.4 and 2.4 below.

---

## Phase 0: Repository Bootstrap ✅

### Prompt 0.1 — Create the multi-module Gradle project ✅ `c04f9bf`

```
Create a new multi-module Gradle project at /workspaces/custom-workflow-starter with these specifications:

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

Also in settings.gradle, add a composite build inclusion for the plugin
so the sample module can apply it by plugin ID during local development:
  includeBuild('anax-kogito-codegen-plugin')

Root build.gradle:
  - Apply 'java-library' to all subprojects except anax-kogito-codegen-plugin
  - Set Java toolchain to 17
  - Set all subprojects to use group 'com.anax' and version from gradle.properties
  - Apply maven-publish to all subprojects

Create empty build.gradle files in each subproject directory.
Do NOT add dependencies yet — those come in later prompts.
```

---

## Phase 1: Codegen Extensions Module ✅

### Prompt 1.1 — Build file for codegen extensions ✅

```
In /workspaces/custom-workflow-starter/anax-kogito-codegen-extensions/build.gradle:

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

### Prompt 1.2 — DmnFunctionTypeHandler ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-codegen-extensions/src/main/java/com/anax/kogito/codegen/DmnFunctionTypeHandler.java

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

### Prompt 1.3 — AnaxFunctionTypeHandler ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-codegen-extensions/src/main/java/com/anax/kogito/codegen/AnaxFunctionTypeHandler.java

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

### Prompt 1.4 — MapFunctionTypeHandler ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-codegen-extensions/src/main/java/com/anax/kogito/codegen/MapFunctionTypeHandler.java

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

### Prompt 1.5 — SPI registration file ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-codegen-extensions/src/main/resources/META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler

Contents (three lines, no blank lines at end):
com.anax.kogito.codegen.DmnFunctionTypeHandler
com.anax.kogito.codegen.AnaxFunctionTypeHandler
com.anax.kogito.codegen.MapFunctionTypeHandler
```

### Prompt 1.6 — Verify codegen extensions compile ✅ `dce85b7`

```
Run: gradle :anax-kogito-codegen-extensions:compileJava
Verify BUILD SUCCESSFUL with no errors.
```

---

## Phase 2: Spring Boot Starter Module ✅

### Prompt 2.1 — Build file for the starter ✅

```
In /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/build.gradle:

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

### Prompt 2.2 — DmnWorkItemHandler ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/DmnWorkItemHandler.java

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

### Prompt 2.3 — AnaxWorkItemHandler ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/AnaxWorkItemHandler.java

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

### Prompt 2.4 — MapWorkItemHandler ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/MapWorkItemHandler.java

Runtime work-item handler for the "map" work-item type.
NOT annotated with @Component.

This is a STUB implementation. The Jolt transformation engine will be
wired in a later iteration. For now, the handler loads a Jolt spec from
the classpath and passes input data through unchanged. This establishes
the URI pattern, metadata server fetch pipeline, and handler registration.

Behavior:
- Extends DefaultKogitoWorkItemHandler
- Constructor takes ResourceLoader as parameter
- Override activateWorkItemHandler():
  1. Extract "MappingName" from workItem parameters
  2. Build input map (excluding MappingName, TaskName)
  3. Load the Jolt spec from classpath:
     META-INF/anax/mappings/{mappingName}.json
     (this file is committed to src/main/resources/ via Copilot + MCP tools
     or the metadata server UI)
  4. STUB: Log a message indicating the Jolt spec was found but
     Jolt execution is not yet implemented. Return the input map
     unchanged as the output.
  5. Call manager.completeWorkItem() with the (pass-through) result
  6. Return Optional.empty()

Future: Replace step 4 with actual Jolt transformation:
  - Add com.bazaarvoice.jolt:jolt-core dependency
  - Parse the spec JSON into a Jolt Chainr
  - Transform the input map and return the Jolt output

Package: com.anax.kogito.autoconfigure
Constructor injection, no @Autowired.
```

### Prompt 2.5 — Auto-configuration class ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/AnaxKogitoAutoConfiguration.java

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

### Prompt 2.6 — AnaxKogitoProperties ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/autoconfigure/AnaxKogitoProperties.java

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

### Prompt 2.7 — CatalogModel ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/catalog/CatalogModel.java

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

### Prompt 2.8 — AnaxCatalogService ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/catalog/AnaxCatalogService.java

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

### Prompt 2.9 — AnaxCatalogController ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/catalog/AnaxCatalogController.java

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

### Prompt 2.10 — AutoConfiguration.imports registration ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

Contents (single line):
com.anax.kogito.autoconfigure.AnaxKogitoAutoConfiguration
```

### Prompt 2.11 — Verify starter compiles ✅ `512c609`

```
Run: gradle :anax-kogito-spring-boot-starter:compileJava
Verify BUILD SUCCESSFUL.
```

### Prompt 2.12 — MetadataServerRegistrationService (NEW)

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/registration/MetadataServerRegistrationService.java

Service that publishes the catalog to the metadata server on application startup.
This enables runtime observability and drift detection.

Behavior:
- Listens for ApplicationReadyEvent
- Loads catalog from AnaxCatalogService
- Constructs a registration payload with:
  - Full catalog (schemes, DMN models, workflows, beans)
  - Instance metadata: spring.application.name, version, host, port, startedAt
- POSTs to {metadataServerUrl}/api/registrations using java.net.http.HttpClient
- If heartbeat-interval is configured, schedules periodic re-registrations
- All failures are logged at WARN level — never throw or prevent startup

Configuration properties:
- anax.metadata-server.url → base URL (required to enable)
- anax.metadata-server.registration.enabled → boolean, default true
- anax.metadata-server.registration.heartbeat-interval → Duration (optional)

Auto-configuration condition:
- @ConditionalOnProperty(prefix = "anax.metadata-server", name = "url")
  — only active when a metadata server URL is configured

Package: com.anax.kogito.registration
Constructor injection: AnaxCatalogService, AnaxMetadataServerProperties,
                       Environment (for app name, port, etc.)
```

### Prompt 2.13 — RegistrationPayload

```
Create /workspaces/custom-workflow-starter/anax-kogito-spring-boot-starter/src/main/java/com/anax/kogito/registration/RegistrationPayload.java

Java record for the registration payload:

  public record RegistrationPayload(
      String applicationName,
      String applicationVersion,
      String host,
      int port,
      String startedAt,            // ISO-8601 timestamp
      CatalogModel.Catalog catalog
  ) {}

Package: com.anax.kogito.registration
```

### Prompt 2.14 — AnaxMetadataServerProperties

```
Create or update AnaxKogitoProperties to add metadata-server configuration:

@ConfigurationProperties(prefix = "anax")
public class AnaxKogitoProperties {
    private CatalogProperties catalog = new CatalogProperties();
    private MetadataServerProperties metadataServer = new MetadataServerProperties();

    public static class MetadataServerProperties {
        private String url;                        // base URL, e.g. http://metadata-platform:3001
        private RegistrationProperties registration = new RegistrationProperties();
    }

    public static class RegistrationProperties {
        private boolean enabled = true;
        private Duration heartbeatInterval;        // optional, e.g. PT60S
    }
}

Package: com.anax.kogito.autoconfigure
```
```

---

## Phase 3: Gradle Plugin Module ✅

> **Status:** Complete. All checklist items 3.1–3.13 implemented. See [Phase 3 Specification §8](0007-phase3-plugin-spec.md) for reference.

### Prompt 3.1 — Build file for the Gradle plugin ✅

```
In /workspaces/custom-workflow-starter/anax-kogito-codegen-plugin/build.gradle:

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

### Prompt 3.2 — AnaxKogitoExtension ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-codegen-plugin/src/main/java/com/anax/kogito/gradle/AnaxKogitoExtension.java

Plugin extension for configuring the Anax Kogito codegen plugin.

Properties:
  - kogitoVersion (String) — defaults to "10.1.0"

> **Design change (March 2026):** The `metadataServerUrl` property has been
> removed from the plugin extension. The build has no dependency on the metadata
> server. Governance assets are committed to `src/main/resources/` and read
> locally. Runtime registration with the metadata server is configured in the
> Spring Boot starter via `anax.metadata-server.url` in `application.yml`.

Usage in consuming build.gradle:
  anaxKogito {
      kogitoVersion = '10.1.0'  // optional, defaults to 10.1.0
  }

Package: com.anax.kogito.gradle
```

### Prompt 3.3 — ResolveGovernanceAssetsTask ✅ *(SUPERSEDED)*

> **Design change (March 2026):** This task has been superseded by the self-contained
> build model. Governance assets (DMN models, Jolt mapping specs) are committed to
> `src/main/resources/` by the developer (via Copilot + MCP tools) and read locally
> at build time. There is no build-time dependency on the metadata server.
>
> The `ResolveGovernanceAssetsTask` code may be retained in the codebase for reference
> but is no longer registered in the plugin's `apply()` method. The metadata server
> connection is established at **runtime** via the starter's
> `MetadataServerRegistrationService` (see Prompt 2.12).

```
(Original prompt preserved for historical reference — see git history for details.)
```

### Prompt 3.4 — AnaxKogitoCodegenPlugin ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-codegen-plugin/src/main/java/com/anax/kogito/gradle/AnaxKogitoCodegenPlugin.java

This is a Gradle Plugin<Project> that automates Kogito code generation
and metadata server asset resolution.

apply(Project project):

1. Apply 'java' plugin if not already applied

2. Create AnaxKogitoExtension: project.extensions.create("anaxKogito", ...)

3. Create 'kogitoCodegen' configuration (not visible to consumers)

4. Add default dependencies to kogitoCodegen:
   - org.kie.kogito:kogito-codegen-manager:${kogitoVersion}
   - com.anax:anax-kogito-codegen-extensions:${project.version}
   - io.smallrye:smallrye-open-api-core:3.10.0

5. Read kogitoVersion from extension or project.findProperty("kogitoVersion")
   with default "10.1.0"

6. Register 'generateKogitoSources' task:
   - inputs: src/main/resources/**/*.sw.json, src/main/resources/**/*.dmn
   - outputs: build/generated/sources/kogito, build/generated/resources/kogito
   - doLast: Build URLClassLoader from kogitoCodegen + runtimeClasspath,
     set Thread.contextClassLoader, invoke Kogito codegen reflectively
     (same logic as the POC generateKogitoSources task)
   - Uses consuming project's group, name, version for Kogito GAV

7. Wire main sourceSet:
   - Add build/generated/sources/kogito to java srcDirs (append, don't replace)
   - Add build/generated/resources/kogito to resources srcDirs (append)

9. Wire task dependencies:
   - compileJava dependsOn generateKogitoSources
   - processResources dependsOn generateKogitoSources

> **Design change (March 2026):** The `resolveGovernanceAssets` task and
> `metadataServerUrl` configuration have been removed. The build pipeline reads
> governance assets (DMN, Jolt specs) directly from `src/main/resources/`.

Package: com.anax.kogito.gradle
```

### Prompt 3.5 — CatalogManifestTask ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-codegen-plugin/src/main/java/com/anax/kogito/gradle/CatalogManifestTask.java

A Gradle DefaultTask that generates META-INF/anax/catalog.json by
combining data from project resources committed in src/main/resources/.

Inputs:
  - src/main/resources/**/*.sw.json
  - src/main/resources/**/*.dmn (committed governance assets)
  - src/main/resources/META-INF/anax/mappings/*.json (committed Jolt specs)
  - src/main/java/**/*.java (for Spring bean scanning)

Outputs:
  - build/generated/resources/kogito/META-INF/anax/catalog.json

@TaskAction generate():
  1. Initialize catalog with static scheme definitions for dmn://, anax://, map://
     (these are always present — they come from the codegen extensions)

  2. Scan committed DMN files (src/main/resources/**/*.dmn):
     - Parse XML to extract <definitions namespace="..." name="...">
     - Extract <inputData> and <decision> names for inputs/outputs
     - Build DmnModelEntry with constructed URI: dmn://{namespace}/{name}

  3. Scan committed Jolt mapping specs (src/main/resources/META-INF/anax/mappings/*.json):
     - Extract mapping name from filename
     - Build MappingEntry with constructed URI: map://{mappingName}

  4. Scan *.sw.json files (src/main/resources):
     - Parse JSON to extract id, name, events[], functions[]
     - Build WorkflowEntry for each workflow

  5. Scan *.java files (basic regex, not full AST):
     - Look for classes annotated with @Component or @Service
     - Look for public methods with Map<String, Object> parameter
     - Extract bean name from annotation value or lowercase class name
     - Build SpringBeanEntry with constructed URI: anax://{beanName}/{methodName}

  6. Write catalog.json with schemaVersion "1.0" and ISO-8601 generatedAt

Register this task in the plugin's apply() method:
  - Named 'generateAnaxCatalog'
  - dependsOn generateKogitoSources
  - processResources dependsOn generateAnaxCatalog

> **Design change (runtime registration):** DMN models and Jolt specs are now
> committed directly to `src/main/resources/` via Copilot + MCP tools.
> The catalog task scans committed files — no build-time server dependency.

Package: com.anax.kogito.gradle

Note: The Java scanning in step 5 is best-effort for the static manifest.
The runtime AnaxCatalogService augments this with a live ApplicationContext
scan, so missing beans in the static manifest are still discoverable at
runtime.
```

### Prompt 3.6 — Verify plugin compiles ✅ `19b4d5c`

```
Run: gradle :anax-kogito-codegen-plugin:compileJava
Verify BUILD SUCCESSFUL.
```

---

## Phase 4: Sample Application ✅

### Prompt 4.1 — Sample build file ✅

```
In /workspaces/custom-workflow-starter/anax-kogito-sample/build.gradle:

This is a Spring Boot application that demonstrates the starter.

Apply plugins:
  - org.springframework.boot (version from gradle.properties)
  - io.spring.dependency-management
  - com.anax.kogito-codegen (from the sibling module — use includeBuild or
    buildSrc approach for local dev)

anaxKogito {
    metadataServerUrl = 'http://localhost:3001'  // metadata server for build-time assets
}

Dependencies:
  implementation project(':anax-kogito-spring-boot-starter')
  implementation 'org.springframework.boot:spring-boot-starter-web'
  // No org.kie.kogito dependencies! The starter and plugin manage all Kogito deps.
  // The plugin auto-adds kogito-dmn if dmn:// URIs are resolved.

Note: For local development with the plugin from a sibling module,
add to settings.gradle:
  includeBuild('anax-kogito-codegen-plugin') {
      dependencySubstitution { ... }
  }
Or use composite builds. The prompt for settings.gradle should handle this.
```

### Prompt 4.2 — Sample application class ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-sample/src/main/java/com/example/demo/DemoApplication.java

Standard @SpringBootApplication.
scanBasePackages: "com.example", "org.kie.kogito"

Also create a simple GreetingService:
/workspaces/custom-workflow-starter/anax-kogito-sample/src/main/java/com/example/demo/GreetingService.java

@Component("greetingService")
public class GreetingService {
    public Map<String, Object> greet(Map<String, Object> params) {
        String name = (String) params.getOrDefault("name", "World");
        return Map.of("greeting", "Hello, " + name + "!");
    }
}
```

### Prompt 4.3 — Sample workflow ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-sample/src/main/resources/hello-world.sw.json

A simple Serverless Workflow (specVersion 0.8) with:
  - id: "hello-world"
  - Two custom functions:
    1. greetFunction: "anax://greetingService/greet"
    2. logFunction: "sysout"
  - Two states:
    1. "Greet" — calls greetFunction with arguments { "name": "${ .name }" }
    2. "LogResult" — logs "${ .greeting }" via sysout, then ends

Also note: to test the dmn:// scheme, the DMN model must be committed
to src/main/resources/. Governance assets are authored via Copilot + MCP
tools or the metadata server UI and committed to git. The hello-world.sw.json
is sufficient for a minimal demo without any DMN models (it only uses anax://).
```

### Prompt 4.4 — Sample application.yml ✅

```
Create /workspaces/custom-workflow-starter/anax-kogito-sample/src/main/resources/application.yml

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

### Prompt 4.5 — Build and run the sample ✅ `52da409`

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

## Phase 5: Copilot Integration & Documentation ✅

### Prompt 5.1 — Copilot instructions template ✅

````
Create /workspaces/custom-workflow-starter/anax-kogito-sample/.github/copilot-instructions.md

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
| `map://`  | Apply a Jolt data mapping      | `map://{mappingName}`           |

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

### Prompt 5.2 — README.md ✅

```

Create /workspaces/custom-workflow-starter/README.md

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
| 3 | 3.1–3.6 | Gradle plugin (extension, asset resolution, codegen, catalog manifest) |
| 4 | 4.1–4.5 | Sample application |
| 5 | 5.1–5.2 | Copilot instructions + README |
| 6 | 6.1 | POC cleanup |
```
