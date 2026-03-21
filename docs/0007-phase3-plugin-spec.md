# Phase 3 Specification: Gradle Plugin, URI Resolution & Metadata Server Client

**Status:** AGREED — API contract confirmed by Metadata Team (March 21, 2026)  
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

## 1.1 Governance Asset Lifecycle

The metadata server is a **governance asset store**. The lifecycle is:

```
1. Author creates a governance resource (DMN model, Jolt mapping spec, etc.)
2. Author publishes the resource to the metadata server (out of band / out of scope)
   └── The metadata server stores the ORIGINAL artifact (e.g., raw .dmn XML file)
   └── The metadata server may also parse it for display/search (JSON representation)
3. Workflow developer references the resource via custom URI in .sw.json:
   └── "operation": "dmn://com.anax.decisions/Order Type Routing"
4. At build time, the Gradle plugin fetches the ORIGINAL artifact from the metadata server
   └── Kogito codegen needs actual DMN XML — the JSON representation is not sufficient
5. Kogito codegen processes the .dmn file alongside the .sw.json
6. At runtime, the generated process evaluates the DMN model in-process
```

This is an iterative cycle: new governance resources are developed, published to the metadata server, then consumed by future workflows — including workflows that call other workflows. Publishing to the metadata server is **out of scope** for this project; consumption at build time is in scope.

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

**Resolution algorithm (confirmed — two-step):**

```
Input:  "dmn://com.anax.decisions/Order Type Routing"
         ├── strip "dmn://" prefix
         └── remainder: "com.anax.decisions/Order Type Routing"
              ├── namespace = "com.anax.decisions"  (everything before last '/')
              └── modelName = "Order Type Routing"   (everything after last '/')

Step 1 — Resolve decisionId:
  GET {metadataServerUrl}/api/decisions?namespace=com.anax.decisions&name=Legal%20Order%20Routing&status=active
  Response: { "data": [{ "decisionId": "legal-order-routing", ... }], "pagination": { "total": 1 } }
  Extract: decisionId = data[0].decisionId  →  "legal-order-routing"

  Error cases:
    data.length == 0  →  FAIL BUILD ("no active decision found for dmn://...")
    data.length >  1  →  FAIL BUILD ("ambiguous: multiple active decisions match dmn://...")

Step 2 — Download original DMN XML:
  GET {metadataServerUrl}/api/decisions/legal-order-routing/export?format=dmn
  Response: raw DMN XML (Content-Type: application/xml)

Output:  build/generated/resources/kogito/legal-order-routing.dmn
```

**CONFIRMED by metadata team (March 21, 2026):**
- The metadata server stores the **original DMN XML** in a `sourceContent` field.
- The `decisionId` is a **user-defined slug** (e.g., `legal-order-routing`) set at creation time.
- The search endpoint supports `namespace` and `name` query parameters (case-insensitive).
- The export endpoint returns the original DMN XML with `Content-Type: application/xml`.
- **Edge case:** Decisions authored purely through the UI (no uploaded `.dmn` file) have no `sourceContent` — the export endpoint returns 404 for these. This is acceptable; our workflows must reference uploaded DMN artifacts.

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

**CONFIRMED by metadata team:** Same principle as DMN — the metadata server stores the **original Jolt spec JSON** as uploaded by the author and serves it via the export endpoint. The `mappingId` in the URI maps directly to the metadata server's `mappingId` (no search step needed).

For now, the `MapWorkItemHandler` is a stub (passes data through). The Jolt engine is wired in a later iteration. The plugin still fetches and writes the spec to the classpath at build time — this validates that the asset exists and is accessible.

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
       │                     │  GET /api/decisions?  │                    │
       │                     │  namespace=&name=     │                    │
       │                     │  &status=active       │                    │
       │                     │                       │                    │
       │                     │  3a. Search results   │                    │
       │                     │  { data: [{ decision- │                    │
       │                     │    Id: "..."}] }      │                    │
       │                     │◄──────────────────────│                    │
       │                     │                       │                    │
       │                     │  4a. GET /decisions/  │                    │
       │                     │  {decisionId}/export  │                    │
       │                     │  ?format=dmn          │                    │
       │                     │──────────────────────►│                    │
       │                     │                       │                    │
       │                     │  5a. DMN XML response │                    │
       │                     │◄──────────────────────│                    │
       │                     │                       │                    │
       │                     │  6a. Write .dmn file  │                    │
       │                     │───────────────────────────────────────────►│
       │                     │                       │                    │
       │                     │  2b. map:// URI found │                    │
       │                     │──────────────────────►│                    │
       │                     │  GET /api/mappings/   │                    │
       │                     │  {mappingId}/export   │                    │
       │                     │  ?format=jolt         │                    │
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
├── legal-order-routing.dmn                         ← from dmn://com.anax.decisions/Legal Order Routing (decisionId: legal-order-routing)
├── risk-assessment.dmn                             ← from dmn://com.anax.decisions/Risk Assessment (decisionId: risk-assessment)
└── META-INF/
    └── anax/
        └── mappings/
            ├── x9-field-mapping.json               ← from map://x9-field-mapping
            └── pdf-extract-to-legal-order.json     ← from map://pdf-extract-to-legal-order
```

**File naming rules:**
- DMN files: `{decisionId}.dmn` — uses the slug from the search result (e.g., `legal-order-routing.dmn`)
- Mapping files: `{mappingId}.json` — used as-is from the `map://` URI (e.g., `x9-field-mapping.json`)

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

import java.util.List;
import java.util.Optional;

/**
 * Client interface for the Metadata Management Platform.
 * Used at build time by the Gradle plugin to resolve governance assets
 * referenced by custom URI schemes in .sw.json workflow definitions.
 */
public interface MetadataServerClient {

    /**
     * Search for decisions by namespace and model name.
     * Used to resolve a dmn:// URI to a decisionId.
     *
     * @param namespace the DMN namespace (from URI authority segment)
     * @param modelName the DMN model name (from URI path segment)
     * @return list of matching decisions (may be empty, one, or many)
     * @throws MetadataServerException on communication errors
     */
    List<DecisionSearchResult> findDecisions(
        String namespace, String modelName);

    /**
     * Download the original DMN XML for a decision.
     *
     * @param decisionId the decision slug (from search results)
     * @return the raw DMN XML bytes, or empty if no sourceContent stored (404)
     * @throws MetadataServerException on communication errors (non-404)
     */
    Optional<byte[]> exportDecisionDmn(String decisionId);

    /**
     * Download the original Jolt spec for a mapping.
     *
     * @param mappingId the mapping identifier (from map:// URI)
     * @return the raw Jolt spec JSON bytes, or empty if not found (404)
     * @throws MetadataServerException on communication errors (non-404)
     */
    Optional<byte[]> exportMappingJolt(String mappingId);

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
 * A decision returned from the search endpoint.
 * Contains metadata needed to identify the decision and call the export endpoint.
 */
public record DecisionSearchResult(
    String decisionId,      // User-defined slug, e.g. "legal-order-routing"
    String name,            // Display name, e.g. "Legal Order Routing"
    String namespace,       // DMN namespace, e.g. "com.anax.decisions"
    String version,         // Semantic version, e.g. "1.0.0"
    String status           // "active", "draft", or "deprecated"
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

Note: The `ResolvedDecision` / `ResolvedMapping` DTOs from the earlier draft are removed. The client now returns raw bytes from export endpoints — the `ResolveGovernanceAssetsTask` writes them directly to the build output. This simplifies the client and avoids double-parsing.
```

### 4.4 HTTP Implementation

```java
package com.anax.kogito.gradle.metadata;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
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
    public List<DecisionSearchResult> findDecisions(
            String namespace, String modelName) {
        String url = baseUrl + "/api/decisions?namespace="
            + URLEncoder.encode(namespace, StandardCharsets.UTF_8)
            + "&name="
            + URLEncoder.encode(modelName, StandardCharsets.UTF_8)
            + "&status=active";
        HttpResponse<String> response = doGet(url);

        if (response.statusCode() == 404) return Collections.emptyList();
        validateResponse(response, url);

        // Parse { "data": [...], "pagination": {...} } response
        return parseDecisionSearchResults(response.body());
    }

    @Override
    public Optional<byte[]> exportDecisionDmn(String decisionId) {
        String url = baseUrl + "/api/decisions/"
            + URLEncoder.encode(decisionId, StandardCharsets.UTF_8)
            + "/export?format=dmn";
        HttpResponse<byte[]> response = doGetBytes(url);

        if (response.statusCode() == 404) return Optional.empty();
        validateResponse(response, url);

        return Optional.of(response.body());
    }

    @Override
    public Optional<byte[]> exportMappingJolt(String mappingId) {
        String url = baseUrl + "/api/mappings/"
            + URLEncoder.encode(mappingId, StandardCharsets.UTF_8)
            + "/export?format=jolt";
        HttpResponse<byte[]> response = doGetBytes(url);

        if (response.statusCode() == 404) return Optional.empty();
        validateResponse(response, url);

        return Optional.of(response.body());
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

    private HttpResponse<byte[]> doGetBytes(String url) {
        try {
            return httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
        } catch (Exception e) {
            throw new MetadataServerException(
                "Failed to connect to metadata server: " + e.getMessage(),
                0, url
            );
        }
    }

    private void validateResponse(HttpResponse<?> response, String url) {
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory stub for testing and offline development.
 * Pre-populated with test fixtures or loaded from local files.
 */
public class StubMetadataServerClient implements MetadataServerClient {

    private final Map<String, DecisionSearchResult> decisions = new HashMap<>();
    private final Map<String, byte[]> decisionDmnXml = new HashMap<>();
    private final Map<String, byte[]> mappingJoltSpecs = new HashMap<>();

    /** Register a decision for testing */
    public StubMetadataServerClient withDecision(
            DecisionSearchResult decision, byte[] dmnXml) {
        decisions.put(decision.decisionId(), decision);
        decisionDmnXml.put(decision.decisionId(), dmnXml);
        return this;
    }

    /** Register a mapping for testing */
    public StubMetadataServerClient withMapping(
            String mappingId, byte[] joltSpec) {
        mappingJoltSpecs.put(mappingId, joltSpec);
        return this;
    }

    @Override
    public List<DecisionSearchResult> findDecisions(
            String namespace, String modelName) {
        List<DecisionSearchResult> results = new ArrayList<>();
        for (DecisionSearchResult d : decisions.values()) {
            if (namespace.equalsIgnoreCase(d.namespace())
                    && modelName.equalsIgnoreCase(d.name())) {
                results.add(d);
            }
        }
        return results;
    }

    @Override
    public Optional<byte[]> exportDecisionDmn(String decisionId) {
        return Optional.ofNullable(decisionDmnXml.get(decisionId));
    }

    @Override
    public Optional<byte[]> exportMappingJolt(String mappingId) {
        return Optional.ofNullable(mappingJoltSpecs.get(mappingId));
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

### ~~Q1: DMN Artifact Format~~ — RESOLVED

**Decision:** The metadata server stores the **original DMN XML file** as uploaded by the author and serves it via an export/download endpoint. The JSON decision table representation is for the server's UI only. The plugin fetches raw DMN XML. See §1.1 and §2.2.

### ~~Q2: Mapping Artifact Format~~ — RESOLVED

**Decision:** Same pattern as DMN. The metadata server stores the **original Jolt spec JSON** as uploaded by the author and serves it via an export/download endpoint. The handler is a stub for now; the Jolt engine comes later. See §2.2.

### ~~Q3: Decision ID Resolution Strategy~~ — RESOLVED

**Decision (confirmed by metadata team March 21, 2026): Option A — Search endpoint.**

The plugin uses
`GET /api/decisions?namespace={ns}&name={name}&status=active`
to resolve a `dmn://` URI to a `decisionId` slug, then calls the export endpoint. The metadata team confirmed that:

- `namespace` and `name` are supported as query parameters (case-insensitive)
- `decisionId` is a **user-defined slug** set at creation time (e.g., `legal-order-routing`)
- `status` filter supports `active`, `draft`, `deprecated`

---

## 5.1 Edge Cases Identified From Metadata Team Response

These must be handled by `ResolveGovernanceAssetsTask`:

| Edge Case | Detection | Plugin Behavior |
|-----------|-----------|-----------------|
| **Multiple active decisions match** | `data.length > 1` from search | **FAIL BUILD** — `"Ambiguous: {n} active decisions match dmn://{namespace}/{modelName}. Expected exactly one. DecisionIds: [{id1}, {id2}]"` |
| **No active decision matches** | `data.length == 0` from search | **FAIL BUILD** — `"No active decision found for dmn://{namespace}/{modelName}. Verify the decision exists with status 'active' on the metadata server."` |
| **Decision exists but no DMN XML stored** | Export returns 404 with `"does not have original DMN XML stored"` | **FAIL BUILD** — `"Decision '{decisionId}' exists but has no DMN XML. It may have been authored through the UI without uploading a .dmn file. Re-publish with the original DMN file."` |
| **Mapping exists but no Jolt spec stored** | Export returns 404 with `"does not have a Jolt spec stored"` | **FAIL BUILD** — `"Mapping '{mappingId}' exists but has no Jolt spec. Re-publish with the original Jolt spec JSON."` |
| **Metadata server returns paginated results** | `pagination.total > pagination.pageSize` | Log warning; use first result. Future: iterate pages if needed. |
| **Status field not `active`** | Should not occur when using `?status=active` filter | N/A — filtered server-side |

---

## 6. Test Strategy

### 6.1 Guiding Principles

- **Every module is independently testable** — no module requires a running metadata server or Kogito engine to run its unit tests
- **The metadata server client interface enables test doubles** — stub for unit tests, WireMock for integration tests
- **No proprietary Kogito test artifact is required** — Kogito does **not** ship a generic work-item unit test harness. We follow the same pattern Kogito uses internally: mock `KogitoWorkItem`, `KogitoWorkItemManager`, and `KogitoWorkItemHandler` with Mockito, then call `activateWorkItemHandler()` directly
- **Gradle TestKit (`GradleRunner`)** for plugin functional tests — these are inherently slower but critical
- **No test requires network access** — metadata server interactions are stubbed or mocked at every level

### 6.2 Assessment of Kogito Test Libraries

Research of the `incubator-kie-kogito-runtimes` repository reveals the following:

| Artifact | Actual Content | Useful For Us? |
|----------|----------------|----------------|
| `kogito-test-utils` | **Testcontainers wrappers** for Infinispan, Kafka, Keycloak, MongoDB, PostgreSQL, Redis. Provides `KogitoInfinispanContainer`, `KogitoKafkaContainer`, etc. | **No** — we don't need database or messaging containers |
| `kogito-spring-boot-test-utils` (in `springboot/test/`) | Spring Boot wrappers around the Testcontainers (e.g., `InfinispanSpringBootTestResource`, `KafkaSpringBootTestResource`) | **No** — same infrastructure focus |
| `kogito-api` (test scope) | `KogitoWorkItem`, `KogitoWorkItemHandler`, `KogitoWorkItemManager` interfaces | **Yes** — needed as compile dependency for handler tests |
| `jbpm-flow` (test scope) | `KogitoWorkItemImpl` — concrete work-item implementation we can instantiate directly in tests | **Yes** — provides the real `KogitoWorkItem` implementation |
| `process-workitems` | `DefaultKogitoWorkItemHandler`, `KogitoDefaultWorkItemManager`, `KogitoWorkItemImpl` | **Yes** — base classes our handlers extend |

**Key finding:** There is no `kogito-spring-boot-starter-test` artifact providing a high-level test harness. Kogito's own tests (e.g., `RestWorkItemHandlerTest`, `WorkItemTest`) use plain **JUnit 5 + Mockito** to mock work-item interfaces and directly invoke handler methods. We follow the same approach.

### 6.3 How Kogito Tests Work Items Internally

Kogito uses two patterns for testing `DefaultKogitoWorkItemHandler` subclasses. Our tests follow these proven patterns:

**Pattern 1: Direct handler invocation with Mockito (unit tests)**

Used by `RestWorkItemHandlerTest` in `kogito-rest-workitem`:

```java
// Kogito's actual pattern — mock the interfaces, call handler directly
@ExtendWith(MockitoExtension.class)
class RestWorkItemHandlerTest {
    @Mock KogitoWorkItemManager manager;
    KogitoWorkItemImpl workItem;            // real impl, not mock

    @BeforeEach
    void init() {
        workItem = new KogitoWorkItemImpl();
        workItem.setId("1");
        parameters = workItem.getParameters();
        parameters.put("someParam", "value");
        // ... set up processInstance, node mocks as needed
    }

    @Test
    void testHandler() {
        WorkItemTransition transition = handler.startingTransition(parameters);
        workItem.setPhaseStatus("Activated");
        Optional<WorkItemTransition> result =
            handler.activateWorkItemHandler(manager, handler, workItem, transition);
        // assert result
    }
}
```

**Key classes used:**
- `KogitoWorkItemImpl` — concrete class from `process-workitems`, instantiated directly (not mocked)
- `KogitoWorkItemManager` — mocked via Mockito
- `KogitoWorkItemHandler` — the handler under test (passed as both `handler` arg and caller)
- `WorkItemTransition` — obtained from `handler.startingTransition()`

**Pattern 2: Capture handler for process-level tests**

Used by `TestWorkItemHandler` in `jbpm-tests`:

```java
// A simple test handler that captures activated work items
public class TestWorkItemHandler extends DefaultKogitoWorkItemHandler {
    private List<KogitoWorkItem> workItems = new ArrayList<>();

    @Override
    public Optional<WorkItemTransition> activateWorkItemHandler(
            KogitoWorkItemManager manager, KogitoWorkItemHandler handler,
            KogitoWorkItem workItem, WorkItemTransition transition) {
        workItems.add(workItem);
        return Optional.empty();  // don't auto-complete — let test drive
    }

    public KogitoWorkItem getWorkItem() { return workItems.get(0); }
}
```

This handler is then registered with `ProcessTestHelper.registerHandler(app, "Human Task", handler)` and used to assert that the process engine dispatched the correct parameters.

### 6.4 Test Pyramid by Module

```
                    ┌───────────────────────┐
                    │   E2E / Smoke Tests    │  ← anax-kogito-sample
                    │   (full build + run)   │     bootRun + REST Assured
                    ├───────────────────────┤
                    │  Plugin Functional     │  ← anax-kogito-codegen-plugin
                    │  (GradleRunner)        │     TestKit + WireMock
                    ├───────────────────────┤
                    │  Integration Tests     │  ← anax-kogito-spring-boot-starter
                    │  (@SpringBootTest)     │     auto-config wiring validation
                    ├───────────────────────┤
                    │  Unit Tests            │  ← all modules
                    │  (JUnit 5 + Mockito)   │     fast, no containers
                    └───────────────────────┘
```

### 6.5 Module 1: `anax-kogito-codegen-extensions` — Tests

**Test scope:** Verify that each `WorkItemTypeHandler` (`FunctionTypeHandler`) correctly parses URIs and emits the expected `WorkItemNode` parameters.

**Framework:**
```gradle
testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
testImplementation 'org.mockito:mockito-core:5.11.0'
// Kogito codegen APIs needed to mock WorkItemNodeFactory, FunctionDefinition, ParserContext
testImplementation "org.kie.kogito:kogito-serverless-workflow-builder:${kogitoVersion}"
testImplementation "io.serverlessworkflow:serverlessworkflow-api:4.0.5.Final"
```

**Test approach:** Our handlers extend `WorkItemTypeHandler`, which itself extends `WorkItemBuilder implements FunctionTypeHandler`. The key method is `fillWorkItemHandler(Workflow, ParserContext, WorkItemNodeFactory, FunctionDefinition)`. Tests mock the `WorkItemNodeFactory` and verify that `workName()` and `workParameter()` are called with expected values.

This mirrors how Kogito registers built-in custom types. For reference, `JwtParserTypeHandler`, `CamelWorkItemTypeHandler`, and `DummyRPCCustomType` in the Kogito repo all follow this exact pattern:
1. Override `fillWorkItemHandler()` → call `node.workName(NAME)` + `node.workParameter(key, value)`
2. Override `type()` → return the scheme name
3. Register via `META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler`

The `FunctionTypeHandlerFactory` constructor loads handlers via `ServiceLoader` and routes based on `type()` + `isCustom()`.

**Test cases:**

| Test Class | Test | Verifies |
|------------|------|----------|
| `DmnFunctionTypeHandlerTest` | `type() returns "dmn"` | Scheme registration |
| | `isCustom() returns true` | Custom function flag |
| | `parses dmn://namespace/ModelName correctly` | URI parsing via `trimCustomOperation()` |
| | `parses dmn://deep.namespace/Model Name With Spaces` | Edge case: dots in namespace, spaces in name |
| | `sets workName("dmn") and workParameters` | Codegen output: workName + DmnNamespace + ModelName params |
| `AnaxFunctionTypeHandlerTest` | `parses anax://beanName/methodName` | URI parsing |
| | `defaults methodName to "execute" when omitted` | Default method behavior |
| | `sets workName("anax") and workParameters` | Codegen output |
| `MapFunctionTypeHandlerTest` | `parses map://mappingName` | URI parsing |
| | `handles mappingName with hyphens and dots` | Edge case: `map://x9.field-mapping.v2` |
| | `sets workName("map") and workParameter("MappingName")` | Codegen output |

**Reference test:**

```java
@Test
void parseDmnUri() {
    DmnFunctionTypeHandler handler = new DmnFunctionTypeHandler();

    // Verify type registration (used by FunctionTypeHandlerFactory)
    assertThat(handler.type()).isEqualTo("dmn");
    assertThat(handler.isCustom()).isTrue();

    // Create a FunctionDefinition with custom operation
    // Format: "dmn://com.anax.decisions/Order Type Routing"
    // trimCustomOperation() strips the "dmn:" prefix → "//com.anax.decisions/Order Type Routing"
    FunctionDefinition fd = new FunctionDefinition("testFn")
        .withType(FunctionDefinition.Type.CUSTOM)
        .withOperation("dmn://com.anax.decisions/Order Type Routing");

    // Mock the WorkItemNodeFactory (fluent builder returned by each call)
    @SuppressWarnings("unchecked")
    WorkItemNodeFactory<RuleFlowNodeContainerFactory<?, ?>> factory = mock(WorkItemNodeFactory.class);
    when(factory.workName(any())).thenReturn(factory);
    when(factory.workParameter(any(), any())).thenReturn(factory);

    Workflow workflow = mock(Workflow.class);
    ParserContext parserContext = mock(ParserContext.class);

    handler.fillWorkItemHandler(workflow, parserContext, factory, fd);

    verify(factory).workName("dmn");
    verify(factory).workParameter("DmnNamespace", "com.anax.decisions");
    verify(factory).workParameter("ModelName", "Order Type Routing");
}
```

### 6.6 Module 2: `anax-kogito-spring-boot-starter` — Tests

**Test scope:** Verify handlers execute correctly given work-item parameters, and auto-configuration wires beans properly.

**Framework:**
```gradle
testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
testImplementation 'org.mockito:mockito-core:5.11.0'
testImplementation 'org.springframework.boot:spring-boot-starter-test'

// Kogito classes needed for handler unit tests — NOT a test harness
testImplementation "org.kie.kogito:kogito-api:${kogitoVersion}"
testImplementation "org.kie.kogito:jbpm-process-workitems:${kogitoVersion}"  // KogitoWorkItemImpl, DefaultKogitoWorkItemHandler
```

**Why no `kogito-test-utils`?** That artifact provides only Testcontainers wrappers (Infinispan, Kafka, Keycloak, MongoDB, PostgreSQL, Redis). Our handlers don't need infrastructure containers. We test handlers directly using the same pattern as Kogito's `RestWorkItemHandlerTest`.

**Work-item handler test pattern (all three handlers):**

```java
@ExtendWith(MockitoExtension.class)
class AnaxWorkItemHandlerTest {

    @Mock KogitoWorkItemManager manager;
    @Mock ApplicationContext applicationContext;

    private AnaxWorkItemHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AnaxWorkItemHandler(applicationContext);
    }

    @Test
    void invokesNamedBeanMethod() {
        // Arrange: create a real KogitoWorkItemImpl with parameters
        KogitoWorkItemImpl workItem = new KogitoWorkItemImpl();
        workItem.setId("1");
        workItem.setParameter("BeanName", "greetingService");
        workItem.setParameter("MethodName", "greet");

        GreetingService mockBean = mock(GreetingService.class);
        when(mockBean.greet(any())).thenReturn(Map.of("greeting", "Hello!"));
        when(applicationContext.getBean("greetingService")).thenReturn(mockBean);

        // Act: invoke the handler directly (Kogito's proven test pattern)
        WorkItemTransition transition = handler.startingTransition(
            workItem.getParameters());
        workItem.setPhaseStatus("Activated");
        Optional<WorkItemTransition> result =
            handler.activateWorkItemHandler(manager, handler, workItem, transition);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().data()).containsEntry("Result",
            Map.of("greeting", "Hello!"));
    }

    @Test
    void defaultsToExecuteMethod() {
        KogitoWorkItemImpl workItem = new KogitoWorkItemImpl();
        workItem.setId("2");
        workItem.setParameter("BeanName", "myService");
        // No MethodName → defaults to "execute"
        // ... assert "execute" is invoked
    }

    @Test
    void throwsForMissingBean() {
        KogitoWorkItemImpl workItem = new KogitoWorkItemImpl();
        workItem.setId("3");
        workItem.setParameter("BeanName", "nonexistent");
        when(applicationContext.getBean("nonexistent"))
            .thenThrow(new NoSuchBeanDefinitionException("nonexistent"));

        assertThatThrownBy(() -> handler.activateWorkItemHandler(
            manager, handler, workItem, handler.startingTransition(workItem.getParameters())
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
```

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

### 6.7 Module 3: `anax-kogito-codegen-plugin` — Tests

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

### 6.8 Module 4: `anax-kogito-sample` — E2E Tests

**Test scope:** Full build-and-run smoke test. Verifies the entire pipeline from `.sw.json` → codegen → runtime.

**Framework:**
```gradle
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testImplementation 'io.rest-assured:rest-assured:5.4.0'
// No special Kogito test dependency — the starter auto-configures the runtime
```

**Test cases:**

| Test | Verifies |
|------|----------|
| `POST /hello-world with name triggers workflow and returns greeting` | Full anax:// handler execution |
| `GET /anax/catalog returns schemes, workflows, beans` | Catalog generation + runtime endpoint |
| `GET /anax/catalog/dmn returns resolved DMN models` | DMN resolution → catalog pipeline |
| Application starts without errors | Auto-configuration wiring |

### 6.9 Test Dependencies Summary

| Module | JUnit 5 | Mockito | Spring Boot Test | Kogito (compile/test) | Gradle TestKit | WireMock | REST Assured |
|--------|---------|---------|------------------|----------------------|----------------|----------|--------------|
| codegen-extensions | ✓ | ✓ | | `kogito-serverless-workflow-builder`, `serverlessworkflow-api` | | | |
| spring-boot-starter | ✓ | ✓ | ✓ | `kogito-api`, `jbpm-process-workitems` | | | |
| codegen-plugin | ✓ | ✓ | | | ✓ | ✓ | |
| sample | ✓ | | ✓ | (via starter transitively) | | | ✓ |

### 6.10 Kogito Test Utilities Reference — Verified Findings

Based on research of the `incubator-kie-kogito-runtimes` repository (`main` branch, reflecting Kogito 10.x+):

#### What `kogito-test-utils` actually contains

`kogito-test-utils` (`org.kie.kogito:kogito-test-utils`) is **exclusively** Testcontainers infrastructure:

| Class | Purpose |
|-------|---------|
| `KogitoGenericContainer` | Base Testcontainers wrapper |
| `KogitoInfinispanContainer` | Testcontainers Infinispan |
| `KogitoKafkaContainer` | Testcontainers Kafka (Redpanda) |
| `KogitoKeycloakContainer` | Testcontainers Keycloak |
| `KogitoMongoDBContainer` | Testcontainers MongoDB |
| `KogitoPostgreSqlContainer` | Testcontainers PostgreSQL |
| `KogitoRedisSearchContainer` | Testcontainers Redis |
| `KogitoImageNameSubstitutor` | Docker image name resolution |
| `Constants` | Container start timeout, image prefix |

The Spring Boot wrappers in `springboot/test/` (e.g., `InfinispanSpringBootTestResource`, `KafkaSpringBootTestResource`) wrap these Testcontainers for Spring's `ApplicationContextInitializer` lifecycle.

**None of these are relevant for testing work-item handlers or codegen extensions.**

#### What we actually use from Kogito (for tests)

| Artifact | Class | How We Use It |
|----------|-------|---------------|
| `kogito-api` | `KogitoWorkItem` (interface) | Type reference in handler method signatures |
| `kogito-api` | `KogitoWorkItemHandler` (interface) | Handler interface with `activateWorkItemHandler()` |
| `kogito-api` | `KogitoWorkItemManager` (interface) | Mocked in tests |
| `kogito-api` | `WorkItemTransition` (interface) | Return type from handler activation |
| `jbpm-process-workitems` | `KogitoWorkItemImpl` | **Instantiated directly** in tests (not mocked) — provides `setParameter()`, `setId()`, `getParameters()` |
| `jbpm-process-workitems` | `DefaultKogitoWorkItemHandler` | Base class our handlers extend — provides `startingTransition()`, lifecycle wiring |
| `kogito-serverless-workflow-builder` | `WorkItemTypeHandler` | Base class our codegen handlers extend |
| `kogito-serverless-workflow-builder` | `FunctionTypeHandler` | SPI interface (loaded by `ServiceLoader`) |
| `kogito-serverless-workflow-builder` | `FunctionTypeHandlerFactory` | Provides `trimCustomOperation(FunctionDefinition)` |

#### Test doubles we build ourselves

Kogito does **not** provide reusable test doubles. Each Kogito test module defines its own. We follow suit:

| Test Double | Purpose | Where Used |
|-------------|---------|------------|
| `StubMetadataServerClient` | In-memory metadata server (§4.5) | Plugin unit tests, plugin functional tests |
| Mock `KogitoWorkItemManager` | Mockito mock of the manager interface | Handler unit tests |
| Mock `ApplicationContext` | Mockito mock of Spring context | `AnaxWorkItemHandler` tests |
| Mock `DecisionModels` | Mockito mock of Kogito DMN API | `DmnWorkItemHandler` tests |
| Mock `WorkItemNodeFactory` | Mockito mock of the codegen factory | `FunctionTypeHandler` tests |

### 6.11 CI Pipeline Integration

```yaml
# GitHub Actions test workflow
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

## Appendix A: Metadata Server API Contract (Confirmed March 21, 2026)

All endpoints confirmed live on the metadata server's `business-friendly-viewer` branch (98 passing tests).

### A.1 Decision Search

```
GET /api/decisions?namespace={ns}&name={name}&status=active
```

**Response (200):**
```json
{
  "data": [
    {
      "decisionId": "legal-order-routing",
      "name": "Legal Order Routing",
      "namespace": "com.anax.decisions",
      "version": "1.0.0",
      "status": "active",
      "description": "Routes incoming legal orders...",
      "hitPolicy": "FIRST",
      "inputs": ["..."],
      "outputs": ["..."],
      "rules": ["..."]
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "totalPages": 1
  }
}
```

Additional query parameters: `tags` (comma-separated), `q` (full-text), `page`, `pageSize`.

### A.2 Decision Export

```
GET /api/decisions/{decisionId}/export?format=dmn
```

**Response (200):**
- **Content-Type:** `application/xml`
- **Content-Disposition:** `attachment; filename="{decisionId}.dmn"`
- **Body:** Raw DMN XML

**Response (404) — no sourceContent:**
```json
{"error": {"code": "NOT_FOUND", "message": "Decision {decisionId} does not have original DMN XML stored..."}}
```

### A.3 Mapping Export

```
GET /api/mappings/{mappingId}/export?format=jolt
```

**Response (200):**
- **Content-Type:** `application/json`
- **Content-Disposition:** `attachment; filename="{mappingId}.json"`
- **Body:** Raw Jolt spec JSON

**Response (404):**
```json
{"error": {"code": "NOT_FOUND", "message": "Mapping not found: {mappingId}"}}
```

### A.4 Health Check

```
GET  /health    → {"status": "ok"}
HEAD /health    → 200 (empty body)
GET  /api/health → {"status": "ok", "storageBackend": "memory", "assetCount": 42}
```

### A.5 Error Response Format

All errors use a consistent structure:

```json
{"error": {"code": "NOT_FOUND|BAD_REQUEST|INTERNAL_ERROR", "message": "Human-readable description"}}
```

| Scenario | HTTP Status | Error Code |
|----------|-------------|------------|
| Asset found | 200 | — |
| Asset not found | 404 | `NOT_FOUND` |
| Missing required parameter | 400 | `BAD_REQUEST` |
| Server error | 500 | `INTERNAL_ERROR` |

### A.6 Key Data Model Fields

| Field | Type | Description |
|-------|------|-------------|
| `decisionId` | string | User-defined slug identifier |
| `name` | string | Display name (maps to DMN `<definitions name="...">`) |
| `namespace` | string | DMN namespace (maps to DMN `<definitions namespace="...">`) |
| `version` | string | Semantic version |
| `status` | string | `active` / `draft` / `deprecated` |
| `sourceContent` | string | Original DMN XML — served by the export endpoint |

**Authentication:** None (PoC scope). Future: Azure AD OAuth2 bearer tokens.

### A.7 Open Item From Metadata Team

> If decisions authored purely through our UI (no uploaded DMN file) need to be consumable by your plugin, we would need to implement DMN XML *generation* from our internal model. Currently, the export endpoint returns 404 for UI-authored decisions that lack `sourceContent`.

**Our position:** Acceptable for now. Workflows must reference DMN artifacts that were uploaded with original XML. The plugin will produce a clear error message for this case (see §5.1).

## Appendix B: Error Messages

The plugin must produce clear, actionable error messages:

| Scenario | Error Message |
|----------|---------------|
| Metadata server unreachable | `Cannot connect to metadata server at {url}. Ensure the server is running or set anaxKogito.metadataServerUrl='stub' for offline builds.` |
| No active decision found | `No active decision found for dmn://{namespace}/{modelName} (referenced by {swJsonFile}). Verify the decision exists with status 'active' on the metadata server.` |
| Multiple active decisions match | `Ambiguous: {n} active decisions match dmn://{namespace}/{modelName} (referenced by {swJsonFile}). Expected exactly one. DecisionIds: [{id1}, {id2}].` |
| Decision has no DMN XML stored | `Decision '{decisionId}' exists but has no DMN XML (referenced by {swJsonFile}). It may have been authored via UI without uploading a .dmn file. Re-publish with the original DMN file.` |
| Mapping not found (404) | `Mapping not found on metadata server: map://{mappingName} (referenced by {swJsonFile}). Ensure the mapping exists and has a Jolt spec stored.` |
| Server error (500) | `Metadata server returned error {statusCode} for {url}. Check server logs.` |
| No metadata server URL configured | `Metadata server URL is not configured. Set 'anaxKogito.metadataServerUrl' in build.gradle or the METADATA_SERVER_URL environment variable. Use 'stub' for offline builds.` |
| Malformed custom URI | `Invalid custom function URI: '{uri}' in {swJsonFile}. Expected format: {scheme}://{segments}` |
