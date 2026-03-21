# Anax Kogito Custom URI Spring Boot Starter

## Project Overview

This is a Spring Boot starter that extracts Kogito Serverless Workflow custom URI scheme infrastructure into three publishable Gradle modules plus a sample app. It enables consuming applications to get full `dmn://`, `anax://`, and `map://` custom function support by adding a single Gradle dependency and plugin.

## Tech Stack

- **Java 17** — minimum target, no Java 21+ features
- **Spring Boot 3.3.7** — auto-configuration uses `META-INF/spring/AutoConfiguration.imports` (not deprecated `spring.factories`)
- **Gradle 8.10+** — Gradle-only, no Maven support
- **Kogito 10.1.0** — pinned version, managed centrally via `kogitoVersion` property
- **CNCF Serverless Workflow spec v0.8**

## Architecture: Two-Phase Execution Model

All code in this project participates in one of two phases:

1. **Build time (Gradle)** — `FunctionTypeHandler` SPI implementations (in `anax-kogito-codegen-extensions`) are discovered via `ServiceLoader` during Kogito codegen. They parse custom URIs and emit `WorkItemNode` entries in the generated process code.
2. **Runtime (Spring Boot)** — `DefaultKogitoWorkItemHandler` subclasses (in `anax-kogito-spring-boot-starter`) are registered by name with `WorkItemHandlerConfig`. When the process engine reaches a `WorkItemNode`, it dispatches to the handler matching the `workName`.

**Critical invariant:** The `workName(scheme)` set during codegen must exactly match the name used in `register(scheme, handler)` at runtime.

## Module Structure

| Module | Purpose | Phase |
|--------|---------|-------|
| `anax-kogito-codegen-extensions` | SPI `FunctionTypeHandler` implementations for `dmn://`, `anax://`, `map://` | Build time |
| `anax-kogito-spring-boot-starter` | Auto-configuration: `WorkItemHandler` beans + `WorkItemHandlerConfig` + metadata catalog REST endpoint | Runtime |
| `anax-kogito-codegen-plugin` | Gradle plugin encapsulating `generateKogitoSources` task, classpath wiring, BOM management, `catalog.json` generation | Build time |
| `anax-kogito-sample` | Example Spring Boot app demonstrating all features (not published) | Both |

## Custom URI Schemes

All custom functions use `"type": "custom"` in `.sw.json` definitions:

| Scheme | URI Pattern | Purpose | Handler |
|--------|-------------|---------|---------|
| `dmn://` | `dmn://{namespace}/{modelName}` | Evaluate a DMN decision model in-process | `DmnWorkItemHandler` |
| `anax://` | `anax://{beanName}/{methodName}` | Invoke a Spring bean method (default method: `execute`) | `AnaxWorkItemHandler` |
| `map://` | `map://{mappingName}` | Apply a data-mapping transformation via `Function<Map,Map>` bean | `MapWorkItemHandler` |

### Bean method contract for `anax://`

```java
public Map<String, Object> methodName(Map<String, Object> params)
```

### Mapping bean contract for `map://`

```java
@Component("mapping-name")
public class MyMapping implements Function<Map<String, Object>, Map<String, Object>> {
    public Map<String, Object> apply(Map<String, Object> input) { ... }
}
```

## Kogito Source Code References

When researching Kogito internals, use the correct branch:

| What | Branch / URL |
|------|-------------|
| **Gradle plugin for Kogito codegen** (does not exist in 10.1.0) | `main` branch: https://github.com/apache/incubator-kie-kogito-runtimes/tree/main |
| **Everything else** (codegen engine, SPI, runtime, APIs) | `10.1.x` branch: https://github.com/apache/incubator-kie-kogito-runtimes/tree/10.1.x |

This distinction is critical. The Gradle plugin does not exist in the 10.1.0 release — our `anax-kogito-codegen-plugin` fills that gap using reflective invocation of the codegen engine. The `main` branch has a reference Gradle plugin implementation we model ours after.

## Key Kogito API Patterns

### Codegen SPI (build time)

- Extend `WorkItemTypeHandler` from `org.kie.kogito.serverless.workflow.parser.types`
- Override `type()` → return the scheme name (e.g., `"dmn"`)
- Override `isCustom()` → return `true`
- Override `fillWorkItemHandler()` → parse the URI via `FunctionTypeHandlerFactory.trimCustomOperation(functionDef)`, set `workName()` and `workParameter()` on the factory
- Register in `META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler`

### Runtime handlers (Spring Boot)

- Extend `DefaultKogitoWorkItemHandler` from `org.kie.kogito.process.workitems.impl`
- Override `activateWorkItemHandler()` → extract parameters from `workItem.getParameter()`, execute logic, call `manager.completeWorkItem()`, return `Optional.empty()`
- Handlers are created as `@Bean` in `AnaxKogitoAutoConfiguration` (not `@Component`)
- Use constructor injection, not `@Autowired`

### Reflective codegen invocation (Gradle plugin)

The `generateKogitoSources` task builds a `URLClassLoader` from `kogitoCodegen` + `runtimeClasspath`, sets `Thread.contextClassLoader`, then invokes `CodeGenManagerUtil.discoverKogitoRuntimeContext()` and `GenerateModelHelper.generateModelFiles()` reflectively. See [docs/0006-POC-REFERENCE.md](docs/0006-POC-REFERENCE.md) §3.1 for the complete working implementation.

## Project Documentation

- [ADR 006 — Architecture](docs/0006-kogito-custom-uri-spring-boot-starter.md) — module structure, two-phase model, URI schemes, auto-configuration, metadata catalog
- [Implementation Plan](docs/0006-IMPLEMENTATION-PLAN.md) — step-by-step prompt sequence for scaffolding
- [POC Reference](docs/0006-POC-REFERENCE.md) — all 10 proven source files from the working prototype
- [README Template](docs/0006-README-TEMPLATE.md) — target README for the starter

## Conventions

- **Package names**: `com.anax.kogito.codegen` (codegen extensions), `com.anax.kogito.autoconfigure` (starter auto-config), `com.anax.kogito.catalog` (catalog), `com.anax.kogito.gradle` (plugin)
- **Group ID**: `com.anax`
- **Work-item parameter names** use PascalCase constants: `DmnNamespace`, `ModelName`, `BeanName`, `MethodName`, `MappingName`
- **Auto-configuration conditions**: `DmnWorkItemHandler` is `@ConditionalOnClass(DecisionModels.class)` so projects without DMN skip it. Other handlers use `@ConditionalOnMissingBean` for consumer override.
- **Catalog endpoint**: enabled by default at `/anax/catalog`, disabled via `anax.catalog.enabled=false`

## Build Commands

```bash
# Build all modules
./gradlew build

# Publish to local Maven repo
./gradlew publishToMavenLocal

# Run sample
./gradlew :anax-kogito-sample:bootRun
```
