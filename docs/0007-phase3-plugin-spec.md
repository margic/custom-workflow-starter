# Phase 3 Specification: Gradle Plugin, URI Resolution & Metadata Server Client

**Status:** DRAFT — For Review  
**Date:** March 2026  
**Parent:** [ADR 006](0006-kogito-custom-uri-spring-boot-starter.md) · [Implementation Plan](0006-IMPLEMENTATION-PLAN.md)

---

## 1. Purpose

This document provides the **concrete specification** for Phase 3 of the Custom Workflow Spring Boot Starter — the Gradle plugin module. Phase 3 is the heart of the project: it bridges the metadata server, the Kogito codegen engine, and the consuming application's build pipeline.

This spec addresses three identified blockers:

1. **URI Resolution Schema** — How custom URIs (`dmn://`, `map://`) map to metadata server API calls and local build artifacts
2. **Reflective Kogito Codegen Invocation** — The proven classloader + reflection pattern from the POC
3. **Metadata Server Client** — An interface-based abstraction with a stub, designed for testability and future evolution

It also includes a **comprehensive test strategy** covering all four modules.

---

## 2. URI Resolution Schema

### 2.1 URI Grammar

All custom URIs in `.sw.json` `functions[].operation` fields conform to this grammar:

```
custom-uri     = scheme "://" authority [ "/" path ]
scheme         = "dmn" | "anax" | "map"
authority      = segment
path           = segment *( "/" segment )
segment        = 1*( unreserved / pct-encoded / "." / "-" / "_" )
unreserved     = ALPHA / DIGIT
```

### 2.2 Scheme Definitions

#### `dmn://{namespace}/{modelName}`

| Component | Description | Extraction Rule | Example |
|-----------|-------------|-----------------|---------|
| `namespace` | DMN model namespace (maps to `<definitions namespace="...">`) | URI authority + first path segments before last `/` | `com.anax.decisions` |
| `modelName` | DMN model name (maps to `<definitions name="...">`) | Last URI path segment | `Order Type Routing` |
| `decisionId` (derived) | Metadata server lookup key | Slug of `{namespace}/{modelName}` or **direct mapping via metadata server search** | `order-type-routing` |

**Resolution algorithm:**

```
Input:  "dmn://com.anax.decisions/Order Type Routing"
         ├── strip "dmn://" prefix
         └── remainder: "com.anax.decisions/Order Type Routing"
              ├── namespace = "com.anax.decisions"  (everything before last '/')
              └── modelName = "Order Type Routing"   (everything after last '/')

API call: GET {metadataServerUrl}/api/decisions?namespace={namespace}&name={modelName}&status=active
          OR
          GET {metadataServerUrl}/api/decisions/{decisionId}  (if decisionId is known)

Output:  DMN XML → build/generated/resources/kogito/{slugified-modelName}.dmn
```

**Critical gap identified:** The current metadata server API (`GET /api/decisions/:decisionId`) returns a **JSON decision table representation**, NOT raw DMN XML. Kogito codegen requires actual DMN XML files as input.

**Resolution options (choose one):**

| Option | Description | Impact |
|--------|-------------|--------|
| **A. DMN export endpoint** | Add `GET /api/decisions/:decisionId/export?format=dmn` to the metadata server that returns raw DMN XML | Requires metadata server change; clean separation |
| **B. Client-side DMN generation** | The Gradle plugin generates DMN XML from the JSON decision table definition | Complex; fragile; duplicates DMN authoring logic |
| **C. Store DMN XML as attachment** | Metadata server stores the original `.dmn` file as a binary attachment alongside the parsed definition | Simplest; metadata server becomes a file store for DMN |

**Recommendation: Option A** — Add an export endpoint. The metadata server already parses DMN for display; generating XML from its internal model is the server's responsibility. Option C is acceptable as a first iteration.

#### `map://{mappingName}`

| Component | Description | Extraction Rule | Example |
|-----------|-------------|-----------------|---------|
| `mappingName` | Mapping identifier on the metadata server | Strip `map://` prefix; remainder is the mapping ID | `x9-field-mapping` |

**Resolution algorithm:**

```
Input:  "map://x9-field-mapping"
         ├── strip "map://" prefix
         └── mappingId = "x9-field-mapping"

API call: GET {metadataServerUrl}/api/mappings/{mappingId}

Output:  Jolt spec JSON → build/generated/resources/kogito/META-INF/anax/mappings/{mappingName}.json
```

**Critical gap identified:** The current metadata server API returns a **field-level mapping representation** with custom transform expressions (e.g., `parseNumber`, `parseDate`), NOT a Jolt spec. The `MapWorkItemHandler` expects a Jolt-compatible JSON spec.

**Resolution options (choose one):**

| Option | Description | Impact |
|--------|-------------|--------|
| **A. Jolt export endpoint** | Add `GET /api/mappings/:mappingId/export?format=jolt` that converts the field mapping to Jolt chainr spec | Requires metadata server change; server owns the format translation |
| **B. Client-side Jolt generation** | The Gradle plugin converts the field-mapping JSON to Jolt spec during `resolveGovernanceAssets` | Plugin owns the translation; mapping format becomes a build concern |
| **C. Store Jolt spec directly** | Metadata server stores a raw Jolt spec JSON alongside the field mapping | Requires Jolt authoring in the metadata server UI; most flexible |

**Recommendation: Option B for now** — The plugin converts at build time. The field-mapping format from the metadata server is well-defined, and the Jolt conversion is deterministic. The `MapWorkItemHandler` is a stub anyway — the Jolt engine comes later. For now, the plugin can write the raw field-mapping JSON and the handler passes data through unchanged.

#### `anax://{beanName}/{methodName}`

| Component | Description | Extraction Rule | Example |
|-----------|-------------|-----------------|---------|
| `beanName` | Spring bean name | URI authority (segment before first `/`) | `partyLookupService` |
| `methodName` | Method to invoke | First path segment (default: `execute`) | `lookup` |

**No metadata server resolution.** `anax://` URIs reference local Spring beans. Existence is validated at runtime by Spring's `ApplicationContext`.

### 2.3 URI Resolution Sequence Diagram

```
┌──────────────┐    ┌──────────────────┐    ┌─────────────────┐    ┌──────────────┐
│  .sw.json    │    │ resolveGovernance │    │ MetadataServer  │    │  Build Output│
│  (local)     │    │ AssetsTask        │    │ Client          │    │              │
└──────┬───────┘    └────────┬─────────┘    └────────┬────────┘    └──────┬───────┘
       │                     │                       │                    │
       │  1. Parse functions[]                       │                    │
       │  where type=="custom"                       │                    │
       │────────────────────►│                       │                    │
       │                     │                       │                    │
       │                     │  2a. dmn:// URI found │                    │
       │                     │──────────────────────►│                    │
       │                     │  GET /api/decisions/  │                    │
       │                     │  {decisionId}/export  │                    │
       │                     │                       │                    │
       │                     │  3a. DMN XML response │                    │
       │                     │◄──────────────────────│                    │
       │                     │                       │                    │
       │                     │  4a. Write .dmn file  │                    │
       │                     │───────────────────────────────────────────►│
       │                     │                       │                    │
       │                     │  2b. map:// URI found │                    │
       │                     │──────────────────────►│                    │
       │                     │  GET /api/mappings/   │                    │
       │                     │  {mappingId}          │                    │
       │                     │                       │                    │
       │                     │  3b. Mapping JSON     │                    │
       │                     │◄──────────────────────│                    │
       │                     │                       │                    │
       │                     │  4b. Write Jolt spec  │                    │
       │                     │───────────────────────────────────────────►│
       │                     │                       │                    │
       │                     │  2c. anax:// URI      │                    │
       │                     │  → SKIP (local bean)  │                    │
       │                     │                       │                    │
       │                     │  5. ALL resolved?     │                    │
       │                     │  YES → continue       │                    │
       │                     │  NO  → FAIL BUILD     │                    │
       │                     │                       │                    │
```

### 2.4 Resolution Output Contract

After `resolveGovernanceAssets` completes successfully:

```
build/generated/resources/kogito/
├── order-type-routing.dmn                          ← from dmn://com.anax.decisions/Order Type Routing
├── risk-assessment.dmn                             ← from dmn://com.anax.decisions/Risk Assessment
└── META-INF/
    └── anax/
        └── mappings/
            ├── x9-field-mapping.json               ← from map://x9-field-mapping
            └── pdf-extract-to-legal-order.json     ← from map://pdf-extract-to-legal-order
```

**File naming rules:**
- DMN files: `{modelName}` slugified (spaces → hyphens, lowercase) + `.dmn`
- Mapping files: `{mappingName}` used as-is (already a slug) + `.json`

---

## 3. Reflective Kogito Codegen Invocation

This section documents the **proven, working** reflection pattern from the POC. This is not speculative — it runs in production.

### 3.1 Why Reflection?

Kogito 10.1.0 does not ship a Gradle plugin. The codegen engine is designed for Maven (`kogito-maven-plugin`). Our Gradle plugin fills this gap by invoking the same codegen engine reflectively via a `URLClassLoader`. The `main` branch of `incubator-kie-kogito-runtimes` has a reference Gradle plugin we model ours after.

### 3.2 Classpath Assembly

The codegen engine requires a specific classpath that combines:

1. **Kogito codegen manager** — `org.kie.kogito:kogito-codegen-manager:10.1.0`
2. **Application runtime classpath** — the consuming project's `runtimeClasspath` (Spring Boot, Kogito API, etc.)
3. **Codegen extensions** — `com.anax:anax-kogito-codegen-extensions` (our SPI handlers)
4. **OpenAPI support** — `io.smallrye:smallrye-open-api-core:3.10.0` (required by Kogito codegen internals)

```java
// Assemble classpath URLs
Set<File> codegenFiles = project.getConfigurations()
    .getByName("kogitoCodegen").resolve();
Set<File> runtimeFiles = project.getConfigurations()
    .getByName("runtimeClasspath").resolve();

URL[] classpathUrls = Stream.concat(
        codegenFiles.stream(),
        runtimeFiles.stream()
    )
    .filter(File::exists)
    .map(f -> f.toURI().toURL())
    .toArray(URL[]::new);

URLClassLoader codegenClassLoader = new URLClassLoader(
    classpathUrls,
    ClassLoader.getSystemClassLoader()  // parent = system, NOT app classloader
);
```

**Critical:** The parent classloader must be `ClassLoader.getSystemClassLoader()`, not `getClass().getClassLoader()`. Using the Gradle classloader as parent causes class conflicts with Kogito's internal dependency versions.

### 3.3 Reflective Invocation Sequence

```java
ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
Thread.currentThread().setContextClassLoader(codegenClassLoader);

try {
    // 1. Load classes
    Class<?> kogitoGAVClass = codegenClassLoader.loadClass(
        "org.kie.kogito.KogitoGAV");
    Class<?> codeGenUtilClass = codegenClassLoader.loadClass(
        "org.kie.kogito.codegen.manager.util.CodeGenManagerUtil");
    Class<?> frameworkEnum = codegenClassLoader.loadClass(
        "org.kie.kogito.codegen.manager.util.CodeGenManagerUtil$Framework");
    Class<?> projectParamsClass = codegenClassLoader.loadClass(
        "org.kie.kogito.codegen.manager.util.CodeGenManagerUtil$ProjectParameters");
    Class<?> generateModelHelperClass = codegenClassLoader.loadClass(
        "org.kie.kogito.codegen.manager.GenerateModelHelper");

    // 2. Construct KogitoGAV from consuming project's coordinates
    Object gav = kogitoGAVClass
        .getConstructor(String.class, String.class, String.class)
        .newInstance(
            project.getGroup().toString(),
            project.getName(),
            project.getVersion().toString()
        );

    // 3. Get Spring framework enum constant
    Object springFramework = frameworkEnum.getField("SPRING").get(null);

    // 4. Construct ProjectParameters
    //    ProjectParameters(Framework, String, String, String, String, boolean)
    //    Only framework is required; others are null/false
    Object projectParams = projectParamsClass.getConstructors()[0]
        .newInstance(springFramework, null, null, null, null, false);

    // 5. Class availability predicate (for conditional codegen features)
    Predicate<String> classAvailability = className -> {
        try {
            codegenClassLoader.loadClass(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    };

    // 6. Discover Kogito runtime context
    Path projectBase = project.getProjectDir().toPath();
    Object context = codeGenUtilClass.getMethod(
            "discoverKogitoRuntimeContext",
            ClassLoader.class,
            java.nio.file.Path.class,
            kogitoGAVClass,
            projectParamsClass,
            java.util.function.Predicate.class
        )
        .invoke(null, codegenClassLoader, projectBase, gav,
                projectParams, classAvailability);

    // 7. Generate model files
    Object result = generateModelHelperClass.getMethod(
            "generateModelFiles",
            codegenClassLoader.loadClass(
                "org.kie.kogito.codegen.api.context.KogitoBuildContext"),
            boolean.class
        )
        .invoke(null, context, false);

    // 8. Write generated sources and resources
    writeGeneratedArtifacts(result, project);

} finally {
    Thread.currentThread().setContextClassLoader(originalCl);
    codegenClassLoader.close();
}
```

### 3.4 Writing Generated Artifacts

The `result` from `GenerateModelHelper.generateModelFiles()` is a `Map<String, List<GeneratedFile>>` with keys:

| Key | Content | Output Directory |
|-----|---------|------------------|
| `"SOURCES"` | Generated `.java` files (process classes, REST endpoints) | `build/generated/sources/kogito/` |
| `"RESOURCES"` | Generated resources (process metadata JSON, etc.) | `build/generated/resources/kogito/` |

```java
@SuppressWarnings("unchecked")
private void writeGeneratedArtifacts(Object result, Project project) {
    Map<String, ?> resultMap = (Map<String, ?>) result;

    Path sourcesDir = project.getBuildDir().toPath()
        .resolve("generated/sources/kogito");
    Path resourcesDir = project.getBuildDir().toPath()
        .resolve("generated/resources/kogito");

    writeFiles((List<?>) resultMap.get("SOURCES"), sourcesDir);
    writeFiles((List<?>) resultMap.get("RESOURCES"), resourcesDir);
}

private void writeFiles(List<?> generatedFiles, Path outputDir) {
    for (Object gf : generatedFiles) {
        // GeneratedFile has relativePath() and contents()
        String relativePath = (String) gf.getClass()
            .getMethod("relativePath").invoke(gf);
        byte[] contents = (byte[]) gf.getClass()
            .getMethod("contents").invoke(gf);

        Path target = outputDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, contents);
    }
}
```

### 3.5 Kogito Codegen Dependencies

These are the exact Maven coordinates required on the `kogitoCodegen` classpath:

```gradle
kogitoCodegen "org.kie.kogito:kogito-codegen-manager:${kogitoVersion}"
kogitoCodegen "com.anax:anax-kogito-codegen-extensions:${project.version}"
kogitoCodegen "io.smallrye:smallrye-open-api-core:3.10.0"
```

The `runtimeClasspath` must include (managed by the starter):

```gradle
implementation "org.kie.kogito:kogito-api"
implementation "org.kie.kogito:jbpm-flow"
implementation "org.kie.kogito:jbpm-flow-builder"
implementation "org.kie.kogito:kogito-serverless-workflow-builder"
implementation "org.jbpm:jbpm-spring-boot-starter"
```

Additionally, if `dmn://` URIs are resolved:
```gradle
implementation "org.kie.kogito:kogito-dmn"
implementation "org.drools:drools-decisions-spring-boot-starter"
```

---

## 4. Metadata Server Client

### 4.1 Design Principles

- **Interface-first** — All metadata server access goes through `MetadataServerClient` interface
- **Testable** — Stub implementation for unit/integration tests; no metadata server required
- **Gradle-compatible** — Uses `java.net.http.HttpClient` (Java 11+); no Spring dependencies in the plugin
- **Fail-fast** — Any fetch failure fails the build with a clear, actionable error message

### 4.2 Interface Definition

```java
package com.anax.kogito.gradle.metadata;

import java.util.Optional;

/**
 * Client interface for the Metadata Management Platform.
 * Used at build time by the Gradle plugin to resolve governance assets
 * referenced by custom URI schemes in .sw.json workflow definitions.
 */
public interface MetadataServerClient {

    /**
     * Fetch a DMN decision model by its identifier.
     *
     * @param decisionId the decision identifier (derived from dmn:// URI)
     * @return the resolved decision, or empty if not found (404)
     * @throws MetadataServerException on communication errors (non-404)
     */
    Optional<ResolvedDecision> fetchDecision(String decisionId);

    /**
     * Search for a decision by namespace and model name.
     * Used when the decisionId is not known directly and must be
     * resolved from the dmn:// URI segments.
     *
     * @param namespace the DMN namespace (first URI segment)
     * @param modelName the DMN model name (second URI segment)
     * @return the resolved decision, or empty if not found
     * @throws MetadataServerException on communication errors
     */
    Optional<ResolvedDecision> findDecision(String namespace, String modelName);

    /**
     * Fetch a mapping specification by its identifier.
     *
     * @param mappingId the mapping identifier (from map:// URI)
     * @return the resolved mapping, or empty if not found (404)
     * @throws MetadataServerException on communication errors (non-404)
     */
    Optional<ResolvedMapping> fetchMapping(String mappingId);

    /**
     * Health check — verify the metadata server is reachable.
     *
     * @return true if the server responds to health check
     */
    boolean isAvailable();
}
```

### 4.3 Data Transfer Objects

```java
package com.anax.kogito.gradle.metadata;

/**
 * A resolved DMN decision artifact ready to be written to the build output.
 */
public record ResolvedDecision(
    String decisionId,
    String name,
    String namespace,
    String version,
    byte[] dmnXml,         // Raw DMN XML content (for Kogito codegen)
    String rawJson         // Original JSON response (for catalog generation)
) {}

/**
 * A resolved mapping artifact ready to be written to the build output.
 */
public record ResolvedMapping(
    String mappingId,
    String name,
    String version,
    String specJson        // Mapping spec JSON (field mapping or Jolt spec)
) {}

/**
 * Thrown when the metadata server returns an unexpected error (not 404).
 */
public class MetadataServerException extends RuntimeException {
    private final int statusCode;
    private final String url;

    public MetadataServerException(String message, int statusCode, String url) {
        super(message);
        this.statusCode = statusCode;
        this.url = url;
    }

    public int getStatusCode() { return statusCode; }
    public String getUrl() { return url; }
}
```

### 4.4 HTTP Implementation

```java
package com.anax.kogito.gradle.metadata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP-based implementation of MetadataServerClient.
 * Uses java.net.http.HttpClient (Java 11+) — no external dependencies.
 */
public class HttpMetadataServerClient implements MetadataServerClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final HttpClient httpClient;

    public HttpMetadataServerClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    }

    @Override
    public Optional<ResolvedDecision> fetchDecision(String decisionId) {
        String url = baseUrl + "/api/decisions/"
            + URI.create("").resolve(decisionId).toString();
        HttpResponse<String> response = doGet(url);

        if (response.statusCode() == 404) return Optional.empty();
        validateResponse(response, url);

        // Parse JSON response and extract DMN content
        // (Implementation depends on resolution of §2.2 Option A/B/C)
        return Optional.of(parseDecisionResponse(response.body()));
    }

    @Override
    public Optional<ResolvedDecision> findDecision(
            String namespace, String modelName) {
        String url = baseUrl + "/api/decisions?namespace="
            + URLEncoder.encode(namespace, StandardCharsets.UTF_8)
            + "&name="
            + URLEncoder.encode(modelName, StandardCharsets.UTF_8)
            + "&status=active";
        HttpResponse<String> response = doGet(url);

        if (response.statusCode() == 404) return Optional.empty();
        validateResponse(response, url);

        // Parse search results, return first match
        return parseDecisionSearchResponse(response.body());
    }

    @Override
    public Optional<ResolvedMapping> fetchMapping(String mappingId) {
        String url = baseUrl + "/api/mappings/"
            + URI.create("").resolve(mappingId).toString();
        HttpResponse<String> response = doGet(url);

        if (response.statusCode() == 404) return Optional.empty();
        validateResponse(response, url);

        return Optional.of(parseMappingResponse(response.body()));
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpResponse<Void> response = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .HEAD()
                    .build(),
                HttpResponse.BodyHandlers.discarding()
            );
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpResponse<String> doGet(String url) {
        try {
            return httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
        } catch (Exception e) {
            throw new MetadataServerException(
                "Failed to connect to metadata server: " + e.getMessage(),
                0, url
            );
        }
    }

    private void validateResponse(HttpResponse<String> response, String url) {
        if (response.statusCode() >= 400) {
            throw new MetadataServerException(
                "Metadata server returned HTTP " + response.statusCode()
                    + " for " + url,
                response.statusCode(), url
            );
        }
    }
}
```

### 4.5 Stub Implementation (for tests and offline builds)

```java
package com.anax.kogito.gradle.metadata;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory stub for testing and offline development.
 * Pre-populated with test fixtures or loaded from local files.
 */
public class StubMetadataServerClient implements MetadataServerClient {

    private final Map<String, ResolvedDecision> decisions = new HashMap<>();
    private final Map<String, ResolvedMapping> mappings = new HashMap<>();

    /** Register a decision for testing */
    public StubMetadataServerClient withDecision(ResolvedDecision decision) {
        decisions.put(decision.decisionId(), decision);
        return this;
    }

    /** Register a mapping for testing */
    public StubMetadataServerClient withMapping(ResolvedMapping mapping) {
        mappings.put(mapping.mappingId(), mapping);
        return this;
    }

    @Override
    public Optional<ResolvedDecision> fetchDecision(String decisionId) {
        return Optional.ofNullable(decisions.get(decisionId));
    }

    @Override
    public Optional<ResolvedDecision> findDecision(
            String namespace, String modelName) {
        return decisions.values().stream()
            .filter(d -> namespace.equals(d.namespace())
                && modelName.equals(d.name()))
            .findFirst();
    }

    @Override
    public Optional<ResolvedMapping> fetchMapping(String mappingId) {
        return Optional.ofNullable(mappings.get(mappingId));
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
```

### 4.6 Client Factory (Plugin Integration)

```java
package com.anax.kogito.gradle.metadata;

/**
 * Creates the appropriate MetadataServerClient based on plugin configuration.
 */
public class MetadataServerClientFactory {

    public static MetadataServerClient create(String metadataServerUrl) {
        if (metadataServerUrl == null || metadataServerUrl.isBlank()) {
            throw new IllegalStateException(
                "Metadata server URL is not configured. Set 'anaxKogito.metadataServerUrl' "
                + "in build.gradle or the METADATA_SERVER_URL environment variable."
            );
        }

        if ("stub".equalsIgnoreCase(metadataServerUrl)) {
            return new StubMetadataServerClient();
        }

        return new HttpMetadataServerClient(metadataServerUrl);
    }
}
```

**Plugin extension config supports `stub` mode for offline/test builds:**

```gradle
anaxKogito {
    metadataServerUrl = 'stub'  // uses StubMetadataServerClient — no network calls
}
```

---

## 5. Open Questions Requiring Decision

These must be resolved before Phase 3 implementation begins:

### Q1: DMN Artifact Format

The metadata server returns decisions as JSON decision tables. Kogito codegen requires DMN XML.

| Option | Description | Who Changes | Effort |
|--------|-------------|-------------|--------|
| **A. Export endpoint** | `GET /api/decisions/:id/export?format=dmn` returns DMN XML | Metadata server team | Medium — server generates DMN XML from internal model |
| **B. Store DMN as attachment** | Metadata server stores the original uploaded `.dmn` file and serves it as-is | Metadata server team | Low — add binary storage column |
| **C. Plugin generates DMN** | Gradle plugin converts JSON decision table → DMN XML | Starter team | High — fragile, duplicates server logic |

**Recommendation: B** (lowest risk for first iteration). When authors upload a DMN file to the metadata server, it stores the original XML. The export endpoint returns the stored XML. The JSON representation is for the UI only.

### Q2: Mapping Artifact Format

The metadata server returns mappings as field-level JSON (source/target/transform). The `MapWorkItemHandler` is a stub that passes data through. When the Jolt engine is wired later, it needs a Jolt-compatible spec.

| Option | Description | Who Changes | Effort |
|--------|-------------|-------------|--------|
| **A. Jolt export endpoint** | Server converts field mapping → Jolt chainr spec | Metadata server team | Medium |
| **B. Plugin converts** | Gradle plugin converts field mapping → Jolt spec at build time | Starter team | Medium |
| **C. Pass through raw JSON** | Write field-mapping JSON as-is; handler stub ignores format | Nobody (for now) | Zero |

**Recommendation: C** (for now). The handler is a stub. When the Jolt engine is implemented, revisit with Option A or B. The field-mapping JSON is written to the classpath as the "spec" — the format translation becomes relevant only when the Jolt engine is active.

### Q3: Decision ID Resolution Strategy

How does the plugin map `dmn://com.anax.decisions/Order Type Routing` to an API call?

| Option | Description | Trade-off |
|--------|-------------|-----------|
| **A. Search endpoint** | `GET /api/decisions?namespace=com.anax.decisions&name=Order Type Routing&status=active` | Requires search API support; handles display names with spaces |
| **B. Slugified ID** | Convert namespace+name to slug: `com-anax-decisions--order-type-routing` | Fragile; naming convention coupling |
| **C. URI is the ID** | `decisionId` on the metadata server IS the full URI path: `com.anax.decisions/Order Type Routing` | Requires metadata server to accept URI-encoded IDs |

**Recommendation: A** — Use the search endpoint with namespace + name query parameters. This is the most robust approach and doesn't require the metadata server to change its ID scheme. The client interface already includes `findDecision(namespace, modelName)` for this reason.

---

## 6. Test Strategy

### 6.1 Guiding Principles

- **Every module is independently testable** — no module requires a running metadata server or Kogito engine to run its unit tests
- **The metadata server client interface enables test doubles** — stub for unit tests, WireMock for integration tests
- **Kogito's test infrastructure is leveraged** where it provides value — `kogito-spring-boot-test` for runtime handler tests
- **Gradle TestKit (`GradleRunner`)** for plugin functional tests — these are inherently slower but critical
- **No test requires network access** — metadata server interactions are stubbed or mocked at every level

### 6.2 Test Pyramid by Module

```
                    ┌───────────────────────┐
                    │   E2E / Smoke Tests    │  ← anax-kogito-sample
                    │   (full build + run)   │     bootRun + curl
                    ├───────────────────────┤
                    │  Plugin Functional     │  ← anax-kogito-codegen-plugin
                    │  (GradleRunner)        │     TestKit with sample projects
                    ├───────────────────────┤
                    │  Integration Tests     │  ← anax-kogito-spring-boot-starter
                    │  (Spring Boot Test +   │     @SpringBootTest with Kogito
                    │   Kogito Test Utils)   │     test runtime
                    ├───────────────────────┤
                    │  Unit Tests            │  ← all modules
                    │  (JUnit 5 + Mockito)   │     fast, no containers
                    └───────────────────────┘
```

### 6.3 Module 1: `anax-kogito-codegen-extensions` — Tests

**Test scope:** Verify that each `FunctionTypeHandler` correctly parses URIs and emits the expected `WorkItemNode` parameters.

**Framework:**
```gradle
testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
testImplementation 'org.mockito:mockito-core:5.11.0'
testImplementation "org.kie.kogito:kogito-serverless-workflow-builder:${kogitoVersion}"
testImplementation "io.serverlessworkflow:serverlessworkflow-api:4.0.5.Final"
```

**Test cases:**

| Test Class | Test | Verifies |
|------------|------|----------|
| `DmnFunctionTypeHandlerTest` | `type() returns "dmn"` | Scheme registration |
| | `isCustom() returns true` | Custom function flag |
| | `parses dmn://namespace/ModelName correctly` | URI parsing: namespace + modelName extraction |
| | `parses dmn://deep.namespace/Model Name With Spaces` | Edge case: dots in namespace, spaces in name |
| | `sets workName("dmn") and workParameters` | Codegen output: workName + DmnNamespace + ModelName params |
| `AnaxFunctionTypeHandlerTest` | `parses anax://beanName/methodName` | URI parsing |
| | `defaults methodName to "execute" when omitted` | Default method behavior |
| | `sets workName("anax") and workParameters` | Codegen output |
| `MapFunctionTypeHandlerTest` | `parses map://mappingName` | URI parsing |
| | `handles mappingName with hyphens and dots` | Edge case: `map://x9.field-mapping.v2` |
| | `sets workName("map") and workParameter("MappingName")` | Codegen output |

**Test approach:** Create a mock `FunctionDefinition` with the custom operation string, invoke `fillWorkItemHandler()`, and verify the factory's `workName()` and `workParameter()` calls.

```java
@Test
void parseDmnUri() {
    DmnFunctionTypeHandler handler = new DmnFunctionTypeHandler();
    FunctionDefinition fd = new FunctionDefinition("testFn")
        .withType(FunctionDefinition.Type.CUSTOM)
        .withOperation("dmn://com.anax.decisions/Order Type Routing");

    WorkItemNodeFactory<?> factory = mock(WorkItemNodeFactory.class);
    when(factory.workName(any())).thenReturn(factory);
    when(factory.workParameter(any(), any())).thenReturn(factory);

    handler.fillWorkItemHandler(workflow, parserContext, factory, fd);

    verify(factory).workName("dmn");
    verify(factory).workParameter("DmnNamespace", "com.anax.decisions");
    verify(factory).workParameter("ModelName", "Order Type Routing");
}
```

### 6.4 Module 2: `anax-kogito-spring-boot-starter` — Tests

**Test scope:** Verify handlers execute correctly given work-item parameters, and auto-configuration wires beans properly.

**Framework:**
```gradle
testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
testImplementation 'org.mockito:mockito-core:5.11.0'
testImplementation 'org.springframework.boot:spring-boot-starter-test'

// Kogito test utilities — provides test process engine and work-item test harness
testImplementation "org.kie.kogito:kogito-spring-boot-starter-test:${kogitoVersion}"
testImplementation "org.kie.kogito:kogito-api:${kogitoVersion}"
```

**`kogito-spring-boot-starter-test` provides:**
- `KogitoSpringbootApplication` — test-scoped Spring Boot app with Kogito auto-configuration
- Work-item handler test infrastructure
- In-memory process engine for integration tests

**Test cases:**

| Test Class | Test | Framework |
|------------|------|-----------|
| **DmnWorkItemHandlerTest** | `evaluates DMN model with correct namespace and name` | Unit (mock `DecisionModels`) |
| | `passes all non-parameter inputs to DMN context` | Unit |
| | `merges decision results into output map` | Unit |
| | `throws when model not found` | Unit |
| **AnaxWorkItemHandlerTest** | `invokes named bean method via ApplicationContext` | Unit (mock `ApplicationContext`) |
| | `defaults to "execute" when MethodName not set` | Unit |
| | `throws IllegalArgumentException for missing bean` | Unit |
| | `throws IllegalArgumentException for missing method` | Unit |
| **MapWorkItemHandlerTest** | `loads spec from classpath and passes data through (stub)` | Unit (mock `ResourceLoader`) |
| | `throws when mapping spec not found on classpath` | Unit |
| **AnaxKogitoAutoConfigurationTest** | `registers all handlers when all conditions met` | `@SpringBootTest` |
| | `skips DmnWorkItemHandler when DecisionModels not on classpath` | `@SpringBootTest` with filtered classpath |
| | `allows consumer @Bean override via @ConditionalOnMissingBean` | `@SpringBootTest` with custom config |
| **AnaxCatalogControllerTest** | `GET /anax/catalog returns full catalog` | `@WebMvcTest` |
| | `GET /anax/catalog/schemes returns scheme list` | `@WebMvcTest` |
| | `catalog disabled via anax.catalog.enabled=false` | `@SpringBootTest` with properties |

**Integration test with Kogito runtime:**

```java
@SpringBootTest
@Import(TestWorkflowConfiguration.class)
class WorkflowIntegrationTest {

    @Autowired
    ProcessService processService;  // Kogito process engine

    @Test
    void anaxHandlerInvokedDuringWorkflowExecution() {
        // Given: a workflow with anax://greetingService/greet
        Map<String, Object> input = Map.of("name", "World");

        // When: start the process
        ProcessInstance<?> pi = processService.createProcessInstance(
            "hello-world", input);
        processService.startProcessInstance(pi.id());

        // Then: the greeting was added to workflow data
        Map<String, Object> vars = pi.variables();
        assertEquals("Hello, World!", vars.get("greeting"));
    }
}
```

### 6.5 Module 3: `anax-kogito-codegen-plugin` — Tests

**Test scope:** Verify the Gradle plugin configures tasks correctly, resolves assets from the metadata server, and runs Kogito codegen.

**Framework:**
```gradle
testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
testImplementation 'org.mockito:mockito-core:5.11.0'
testImplementation gradleTestKit()  // Gradle TestKit for functional tests

// For metadata server stub tests
testImplementation 'com.github.tomakehurst:wiremock-jre8:2.35.1'
```

**Test types:**

#### Unit Tests (fast, no Gradle)

| Test Class | Test | Approach |
|------------|------|----------|
| **UriParserTest** | `parses dmn:// URI into namespace + modelName` | Pure function |
| | `parses map:// URI into mappingName` | Pure function |
| | `rejects malformed URIs` | Exception cases |
| | `handles URL-encoded segments` | Edge case |
| **SwJsonParserTest** | `extracts custom function URIs from .sw.json` | Parse JSON fixture |
| | `ignores non-custom function types` | Filter logic |
| | `handles multiple workflows in directory` | Multi-file scan |
| **StubMetadataServerClientTest** | `returns registered decisions` | Stub behavior |
| | `returns empty for unknown IDs` | 404 simulation |
| **HttpMetadataServerClientTest** | `fetches decision from server` | WireMock |
| | `returns empty on 404` | WireMock |
| | `throws MetadataServerException on 500` | WireMock |
| | `throws on connection timeout` | WireMock with delay |

#### Functional Tests (Gradle TestKit)

These are the critical tests — they run a real Gradle build against a sample project.

```java
class PluginFunctionalTest {

    @TempDir Path testProjectDir;

    // WireMock stub for metadata server
    WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeEach
    void setup() {
        wireMock.start();
        // Stub metadata server responses
        wireMock.stubFor(get(urlPathEqualTo("/api/decisions"))
            .withQueryParam("namespace", equalTo("com.example"))
            .withQueryParam("name", equalTo("Test Decision"))
            .willReturn(okJson(testDecisionJson())));

        wireMock.stubFor(get(urlPathEqualTo("/api/mappings/test-mapping"))
            .willReturn(okJson(testMappingJson())));

        // Write build.gradle, settings.gradle, and .sw.json to testProjectDir
        writeTestProject(testProjectDir, wireMock.baseUrl());
    }

    @Test
    void resolveGovernanceAssetsDownloadsArtifacts() {
        BuildResult result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withArguments("resolveGovernanceAssets")
            .build();

        assertEquals(SUCCESS, result.task(":resolveGovernanceAssets").getOutcome());
        assertTrue(Files.exists(testProjectDir
            .resolve("build/generated/resources/kogito/test-decision.dmn")));
        assertTrue(Files.exists(testProjectDir
            .resolve("build/generated/resources/kogito/META-INF/anax/mappings/test-mapping.json")));
    }

    @Test
    void buildFailsWhenAssetMissing() {
        // Stub 404 for a referenced asset
        wireMock.stubFor(get(urlPathEqualTo("/api/mappings/nonexistent"))
            .willReturn(notFound()));

        BuildResult result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withArguments("resolveGovernanceAssets")
            .buildAndFail();

        assertTrue(result.getOutput().contains("Asset not found"));
        assertTrue(result.getOutput().contains("nonexistent"));
    }

    @Test
    void generateKogitoSourcesProducesJavaFiles() {
        BuildResult result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withArguments("generateKogitoSources")
            .build();

        assertEquals(SUCCESS, result.task(":generateKogitoSources").getOutcome());
        // Verify generated Java sources exist
        assertTrue(Files.exists(testProjectDir
            .resolve("build/generated/sources/kogito")));
    }
}
```

**Test project fixture structure:**

```
testProjectDir/
├── settings.gradle     (rootProject.name = 'test-project')
├── build.gradle        (applies plugin, configures anaxKogito extension)
└── src/main/resources/
    └── test-workflow.sw.json  (references dmn:// and map:// URIs)
```

### 6.6 Module 4: `anax-kogito-sample` — E2E Tests

**Test scope:** Full build-and-run smoke test. Verifies the entire pipeline from `.sw.json` → codegen → runtime.

**Framework:**
```gradle
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testImplementation "org.kie.kogito:kogito-spring-boot-starter-test:${kogitoVersion}"
testImplementation 'io.rest-assured:rest-assured:5.4.0'
```

**Test cases:**

| Test | Verifies |
|------|----------|
| `POST /hello-world with name triggers workflow and returns greeting` | Full anax:// handler execution |
| `GET /anax/catalog returns schemes, workflows, beans` | Catalog generation + runtime endpoint |
| `GET /anax/catalog/dmn returns resolved DMN models` | DMN resolution → catalog pipeline |
| Application starts without errors | Auto-configuration wiring |

### 6.7 Test Dependencies Summary

| Module | JUnit 5 | Mockito | Spring Boot Test | Kogito Test | Gradle TestKit | WireMock | REST Assured |
|--------|---------|---------|------------------|-------------|----------------|----------|--------------|
| codegen-extensions | ✓ | ✓ | | ✓ (codegen API) | | | |
| spring-boot-starter | ✓ | ✓ | ✓ | ✓ | | | |
| codegen-plugin | ✓ | ✓ | | | ✓ | ✓ | |
| sample | ✓ | | ✓ | ✓ | | | ✓ |

### 6.8 Kogito Test Utilities Reference

Key Kogito test artifacts:

| Artifact | Purpose |
|----------|---------|
| `org.kie.kogito:kogito-spring-boot-starter-test` | Spring Boot test autoconfiguration for Kogito process engine |
| `org.kie.kogito:kogito-test-utils` | Low-level test utilities: in-memory process engine, mock work items |
| `org.kie.kogito:kogito-api` (test scope) | `ProcessInstance`, `ProcessService`, `WorkItem` interfaces |
| `org.jbpm:jbpm-flow` (test scope) | `DefaultWorkItemHandlerConfig` for test handler registration |

**Note:** The exact test artifact availability for Kogito 10.1.0 should be verified against the Maven repository. The `10.1.x` branch of `incubator-kie-kogito-runtimes` is the authoritative source for test infrastructure.

### 6.9 CI Pipeline Integration

```yaml
# Suggested GitHub Actions test matrix
test:
  strategy:
    matrix:
      module:
        - anax-kogito-codegen-extensions
        - anax-kogito-spring-boot-starter
        - anax-kogito-codegen-plugin
        - anax-kogito-sample
  steps:
    - run: ./gradlew :${{ matrix.module }}:test
    - run: ./gradlew :${{ matrix.module }}:jacocoTestReport  # coverage
```

---

## 7. Task Dependency Graph

```
resolveGovernanceAssets
    │ inputs: src/main/resources/**/*.sw.json
    │ outputs: build/generated/resources/kogito/**/*.dmn
    │          build/generated/resources/kogito/META-INF/anax/mappings/*.json
    │ requires: MetadataServerClient (HTTP or Stub)
    ▼
generateKogitoSources
    │ inputs: src/main/resources/**/*.sw.json
    │         build/generated/resources/kogito/**/*.dmn
    │ outputs: build/generated/sources/kogito/**/*.java
    │          build/generated/resources/kogito/**/*.json (process metadata)
    │ requires: kogitoCodegen + runtimeClasspath on URLClassLoader
    ▼
generateAnaxCatalog
    │ inputs: build/generated/resources/kogito/**/*.dmn
    │         build/generated/resources/kogito/META-INF/anax/mappings/*.json
    │         src/main/resources/**/*.sw.json
    │         src/main/java/**/*.java
    │ outputs: build/generated/resources/kogito/META-INF/anax/catalog.json
    ▼
compileJava ← depends on generateKogitoSources, generateAnaxCatalog
processResources ← depends on generateKogitoSources, generateAnaxCatalog
```

---

## 8. Implementation Checklist

Phase 3 implementation should proceed in this order:

- [ ] **3.1** Plugin build file (`build.gradle` with `java-gradle-plugin`, `maven-publish`)
- [ ] **3.2** `AnaxKogitoExtension` (plugin configuration: `metadataServerUrl`, `kogitoVersion`)
- [ ] **3.3** `MetadataServerClient` interface + DTOs + `MetadataServerException`
- [ ] **3.4** `StubMetadataServerClient` + unit tests
- [ ] **3.5** `HttpMetadataServerClient` + WireMock tests
- [ ] **3.6** `MetadataServerClientFactory`
- [ ] **3.7** `UriParser` (extracts scheme, namespace, modelName, mappingName from custom URIs)
- [ ] **3.8** `SwJsonParser` (reads `.sw.json`, extracts custom function operations)
- [ ] **3.9** `ResolveGovernanceAssetsTask` + functional tests (GradleRunner + WireMock)
- [ ] **3.10** `AnaxKogitoCodegenPlugin` (classpath assembly, reflective invocation) + functional tests
- [ ] **3.11** `CatalogManifestTask` + tests
- [ ] **3.12** Integration: wire all tasks, verify full `build` lifecycle

---

## Appendix A: Metadata Server API Quick Reference

Endpoints consumed by the Gradle plugin at build time:

| Endpoint | Method | Purpose | Response |
|----------|--------|---------|----------|
| `/api/decisions?namespace={ns}&name={name}&status=active` | GET | Find decision by namespace + name | Array of decision objects |
| `/api/decisions/{decisionId}` | GET | Fetch decision by ID | Decision object with `definition` |
| `/api/decisions/{decisionId}/export?format=dmn` | GET | **NEW (proposed)** — Export DMN XML | Raw DMN XML |
| `/api/mappings/{mappingId}` | GET | Fetch mapping by ID | Mapping object with `fields[]` |
| `/health` | HEAD | Health check | 200 if healthy |

**Authentication:** None (PoC scope). Future: Azure AD OAuth2 bearer tokens.

## Appendix B: Error Messages

The plugin must produce clear, actionable error messages:

| Scenario | Error Message |
|----------|---------------|
| Metadata server unreachable | `Cannot connect to metadata server at {url}. Ensure the server is running or set anaxKogito.metadataServerUrl='stub' for offline builds.` |
| Decision not found (404) | `DMN decision not found on metadata server: dmn://{namespace}/{modelName} (referenced by {swJsonFile}). Ensure the decision exists and has status 'active'.` |
| Mapping not found (404) | `Mapping not found on metadata server: map://{mappingName} (referenced by {swJsonFile}). Ensure the mapping exists and has status 'active'.` |
| Server error (500) | `Metadata server returned error {statusCode} for {url}. Check server logs.` |
| No metadata server URL configured | `Metadata server URL is not configured. Set 'anaxKogito.metadataServerUrl' in build.gradle or the METADATA_SERVER_URL environment variable. Use 'stub' for offline builds.` |
| Malformed custom URI | `Invalid custom function URI: '{uri}' in {swJsonFile}. Expected format: {scheme}://{segments}` |
