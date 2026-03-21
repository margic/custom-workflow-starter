# Custom Workflow Spring Boot Starter

> Build event-driven CNCF Serverless Workflows with custom URI schemes, Kogito codegen, build-time asset resolution from a metadata server, and a Copilot-discoverable metadata catalog.

**Java 17** · **Spring Boot 3.3.7** · **Kogito 10.1.0** · **Gradle 8.10+** · **CNCF Serverless Workflow spec v0.8**

---

## What Is This?

The Custom Workflow Spring Boot Starter is a development platform for building **contribution services** — Spring Boot microservices that execute event-driven workflows authored as CNCF Serverless Workflow JSON definitions.

It extends the Kogito runtime with three custom URI schemes (`dmn://`, `anax://`, `map://`) that let workflow authors invoke DMN decisions, Spring beans, and Jolt data transformations directly from `.sw.json` files — without writing Java glue code.

At build time, the Gradle plugin parses `.sw.json` function definitions, fetches referenced DMN models and Jolt mapping specs from the [Metadata Management Platform](docs/canonical-metadata-server.md), and runs Kogito code generation. If any referenced asset is missing from the metadata server, the build fails immediately — providing **build-time validation** of governance assets.

The starter also generates a **metadata catalog** — a machine-readable inventory of available operations, rules, workflows, and beans — that enables GitHub Copilot (and other AI coding assistants) to generate valid workflow definitions on the first attempt.

---

## Quick Start

### 1. Apply the plugin and add the starter dependency

**`build.gradle`:**

```gradle
plugins {
    id 'org.springframework.boot' version '3.3.7'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'com.anax.kogito-codegen' version '0.1.0'
}

anaxKogito {
    metadataServerUrl = 'http://localhost:3000'  // or set METADATA_SERVER_URL env var
}

dependencies {
    implementation 'com.anax:anax-kogito-spring-boot-starter:0.1.0'
}
```

That's it. All Kogito runtime dependencies are managed transitively by the starter and plugin — consuming applications never reference `org.kie.kogito` artifacts directly. The Gradle plugin fetches referenced DMN models and Jolt mapping specs from the metadata server at build time, auto-adds the DMN runtime if `dmn://` URIs are present, and runs Kogito code generation.

### 2. Create a workflow definition

**`src/main/resources/my-workflow.sw.json`:**

```json
{
  "id": "my-workflow",
  "name": "My Contribution Workflow",
  "version": "1.0",
  "specVersion": "0.8",
  "start": "EvaluateOrder",
  "functions": [
    {
      "name": "evaluateOrderType",
      "type": "custom",
      "operation": "dmn://com.example.decisions/Order Type Routing"
    },
    {
      "name": "enrichPayload",
      "type": "custom",
      "operation": "anax://enrichmentService/enrich"
    },
    {
      "name": "transformToX9",
      "type": "custom",
      "operation": "map://x9-field-mapping"
    }
  ],
  "states": [
    {
      "name": "EvaluateOrder",
      "type": "operation",
      "actions": [
        { "functionRef": { "refName": "evaluateOrderType", "arguments": {} } }
      ],
      "transition": "EnrichAndTransform"
    },
    {
      "name": "EnrichAndTransform",
      "type": "operation",
      "actions": [
        { "functionRef": { "refName": "enrichPayload", "arguments": { "orderId": "${ .orderId }" } } },
        { "functionRef": { "refName": "transformToX9", "arguments": {} } }
      ],
      "end": true
    }
  ]
}
```

### 3. Implement your Spring beans

```java
@Component("enrichmentService")
public class EnrichmentService {
    public Map<String, Object> enrich(Map<String, Object> params) {
        String orderId = (String) params.get("orderId");
        // ... your domain logic ...
        return Map.of("enriched", true, "orderId", orderId);
    }
}
```

### 4. Build and run

```bash
./gradlew bootRun
```

The Gradle plugin handles Kogito code generation automatically. Spring Boot auto-configuration registers all work-item handlers. No additional wiring needed.

---

## How It Works: Two-Phase Execution Model

The starter operates in two distinct phases — one at build time and one at runtime. Understanding this model is key to understanding the project.

```
                       BUILD TIME                              RUNTIME
                    ┌──────────────┐                    ┌──────────────────┐
  .sw.json ───────►│ Gradle Plugin │──► Generated Java ─►│ Spring Boot App  │
                   │  (Module 3)   │                    │                  │
                   │               │                    │  Starter (Mod 2) │
                   │  1. Resolve   │                    │    dmn handler   │
                   │     assets    │                    │    anax handler  │
  Metadata  ─────►│  from server  │                    │    map handler   │
  Server           │  2. Codegen   │                    │    catalog API   │
  (DMN, Jolt)      │  3. Catalog   │                    │                  │
                   └───────────────┘                    └──────────────────┘
```

### Phase 1 — Build Time (Gradle)

1. **Resolve governance assets** — The `resolveGovernanceAssets` task parses `.sw.json` function definitions, extracts `dmn://` and `map://` URIs, and fetches the referenced DMN models and Jolt mapping specs from the [Metadata Management Platform](docs/canonical-metadata-server.md). **If any referenced asset is missing, the build fails immediately** (build validation). `anax://` URIs reference local Spring beans and are not fetched.

2. **Kogito code generation** — The code generator reads `.sw.json` workflow definitions and resolved `.dmn` decision models. Our `FunctionTypeHandler` SPI implementations (provided by `anax-kogito-codegen-extensions`) are discovered via `ServiceLoader`. They parse custom URIs and instruct the code generator to emit `WorkItemNode` entries in the generated Java process code — instead of the empty lambdas Kogito would normally produce for unknown custom types.

3. **Catalog generation** — The `generateAnaxCatalog` task produces `META-INF/anax/catalog.json` from resolved assets, workflows, and local Spring beans.

### Phase 2 — Runtime (Spring Boot)

When the process engine executes a workflow and reaches a `WorkItemNode`, it dispatches to the handler matching the node's `workName`. The starter auto-configures `DefaultKogitoWorkItemHandler` subclasses for each URI scheme and registers them with `WorkItemHandlerConfig`. The handler extracts parameters embedded by codegen, executes its logic (evaluate a DMN model, invoke a Spring bean, apply a mapping function), and completes the work item.

**Critical invariant:** The `workName` set during codegen (e.g., `"dmn"`) must exactly match the name used in `register("dmn", handler)` at runtime. The starter guarantees this by managing both sides.

---

## Modules

| Module | Artifact | Phase | Purpose |
|--------|----------|-------|---------|
| `anax-kogito-codegen-extensions` | `com.anax:anax-kogito-codegen-extensions` | Build | SPI `FunctionTypeHandler` implementations that teach Kogito codegen to recognize `dmn://`, `anax://`, `map://` URIs |
| `anax-kogito-spring-boot-starter` | `com.anax:anax-kogito-spring-boot-starter` | Runtime | Auto-configuration: work-item handler beans, `WorkItemHandlerConfig`, and metadata catalog REST endpoint |
| `anax-kogito-codegen-plugin` | `com.anax:anax-kogito-codegen-plugin` | Build | Gradle plugin encapsulating metadata server asset resolution, `generateKogitoSources` task, classpath wiring, BOM management, automatic DMN dependency detection, and `catalog.json` generation |
| `anax-kogito-sample` | *(not published)* | Both | Example Spring Boot app demonstrating all features |

### Module Dependency Graph

```
consuming-app
  ├── implementation 'com.anax:anax-kogito-spring-boot-starter'     (runtime)
  └── plugins { id 'com.anax.kogito-codegen' }                     (build-time)
          │
          ├── kogitoCodegen 'com.anax:anax-kogito-codegen-extensions' (SPI jar)
          └── kogitoCodegen 'org.kie.kogito:kogito-codegen-manager'   (engine)
```

---

## Custom URI Schemes

All custom functions use `"type": "custom"` in `.sw.json` function definitions.

### `dmn://` — In-Process DMN Decision Evaluation

Evaluates a DMN decision model bundled in the same application.

**URI format:** `dmn://{namespace}/{modelName}`

```json
{
  "name": "evaluateRisk",
  "type": "custom",
  "operation": "dmn://com.anax.decisions/Order Type Routing"
}
```

| Segment | Description | Example |
|---------|-------------|---------|
| `namespace` | DMN model namespace (from `<definitions namespace="...">`) | `com.anax.decisions` |
| `modelName` | DMN model name (from `<definitions name="...">`) | `Order Type Routing` |

At build time, `DmnFunctionTypeHandler` parses the URI and emits a `WorkItemNode` with parameters `DmnNamespace` and `ModelName`. At runtime, `DmnWorkItemHandler` uses Kogito's `DecisionModels` API to evaluate the model in-process — all input variables from the workflow data are passed to the DMN context, and decision results are merged back.

**Requirement:** The DMN model must exist on the metadata server. The Gradle plugin fetches it at build time via `GET /api/decisions/{decisionId}` and places the `.dmn` file in the generated resources. The build fails if the model is not found.

---

### `anax://` — Spring Bean Method Invocation

Invokes a method on any Spring-managed bean by name.

**URI format:** `anax://{beanName}/{methodName}`

```json
{
  "name": "lookupParty",
  "type": "custom",
  "operation": "anax://partyLookupService/lookup"
}
```

| Segment | Description | Default |
|---------|-------------|---------|
| `beanName` | Spring bean name (`@Component("name")` or lowercase class name) | *(required)* |
| `methodName` | Method to invoke on the bean | `execute` |

**Bean method contract:**

```java
public Map<String, Object> methodName(Map<String, Object> params)
```

The method receives all workflow data variables as a `Map` and returns a `Map` whose entries are merged back into the workflow data. At runtime, `AnaxWorkItemHandler` looks up the bean in the Spring `ApplicationContext` and invokes the named method reflectively.

---

### `map://` — Jolt Data Transformation

Applies a [Jolt](https://github.com/bazaarvoice/jolt) transformation spec fetched from the metadata server at build time.

**URI format:** `map://{mappingName}`

```json
{
  "name": "transformToX9",
  "type": "custom",
  "operation": "map://x9-field-mapping"
}
```

| Segment | Description |
|---------|-------------|
| `mappingName` | Mapping identifier on the metadata server |

At build time, the `resolveGovernanceAssets` task fetches the Jolt spec from `GET /api/mappings/{mappingName}` on the metadata server and places it at `META-INF/anax/mappings/{mappingName}.json` on the classpath.

At runtime, `MapWorkItemHandler` loads the Jolt spec from the classpath and applies the transformation. The initial implementation is a **stub** — the Jolt execution engine (`com.bazaarvoice.jolt:jolt-core`) will be wired in a later iteration. The stub loads the spec and passes input data through unchanged, establishing the URI pattern and metadata server fetch pipeline.

**Note:** Unlike `anax://`, `map://` transformations are declarative — defined as Jolt specs in the metadata server, not as Java code. This allows non-developers to author and update mappings via the metadata server UI.

---

## Metadata Catalog

The starter generates a machine-readable catalog of everything the service can do, serving two consumers:

1. **AI coding assistants (GitHub Copilot)** — query the catalog at authoring time to generate valid `.sw.json` definitions with correct URIs, argument shapes, and event references.
2. **Runtime introspection** — dashboards, health checks, and operational tools can enumerate what a service can do.

### Build-Time Manifest

The Gradle plugin generates `META-INF/anax/catalog.json` during the build by combining data from multiple sources:

| Source | What is extracted |
|--------|-------------------|
| Resolved DMN files (from metadata server) | Namespace, model name, input/output variables → DMN model entries with `dmn://` URIs |
| Resolved Jolt specs (from metadata server) | Mapping name → mapping entries with `map://` URIs |
| `*.sw.json` files (local) | Workflow ID, name, events, function references → workflow entries |
| `*.java` files (local) | `@Component`/`@Service` beans with `Map → Map` methods → bean entries with `anax://` URIs |
| SPI registration | Registered `FunctionTypeHandler` implementations → scheme definitions |

### Runtime REST Endpoint

The starter auto-configures a REST API under `/anax/catalog`:

| Endpoint | Returns |
|----------|---------|
| `GET /anax/catalog` | Full catalog JSON |
| `GET /anax/catalog/schemes` | Available URI schemes with patterns and parameters |
| `GET /anax/catalog/dmn` | DMN models with namespace, name, inputs, outputs |
| `GET /anax/catalog/workflows` | Deployed workflows with events and function references |
| `GET /anax/catalog/beans` | Spring beans callable via `anax://` with method signatures |

The runtime endpoint augments the static manifest with a live `ApplicationContext` scan, so beans added after the last build are still discoverable.

---

## Copilot Integration

The starter is designed to be a **Copilot-native development platform**. Three layers provide AI coding assistants with the context they need:

| Layer | Source | When |
|-------|--------|------|
| **Project instructions** | `.github/copilot-instructions.md` | Always — teaches URI patterns, conventions |
| **Static catalog** | `META-INF/anax/catalog.json` in build output | At authoring time — project-specific DMN models, beans, workflows |
| **Runtime endpoint** | `GET /anax/catalog` | For MCP-integrated agents or tool-enabled agent mode |

```
Developer creates new .sw.json
         │
         ▼
Copilot reads copilot-instructions.md
  → learns URI scheme patterns
         │
         ▼
Copilot reads catalog.json
  → discovers available DMN models, bean methods, mappings
         │
         ▼
Copilot generates valid function definitions with correct URIs
```

---

## Configuration

### Gradle Plugin Configuration

Configure the plugin in `build.gradle`:

```gradle
anaxKogito {
    metadataServerUrl = 'http://localhost:3000'  // or set METADATA_SERVER_URL env var
}
```

### Runtime Configuration

All runtime configuration is under the `anax` prefix in `application.yml`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `anax.catalog.enabled` | `boolean` | `true` | Enable/disable the `/anax/catalog` REST endpoint |
| `anax.catalog.form-schemas-url` | `String` | — | Optional URL to an external forms-service for form schema discovery |

```yaml
server:
  port: 8084

anax:
  catalog:
    enabled: true
    form-schemas-url: http://forms-service:8083/api/forms
```

To disable the catalog in production:

```yaml
# application-prod.yml
anax:
  catalog:
    enabled: false
```

---

## Event-Driven Workflow Patterns

### Consuming CloudEvents from Kafka

Workflows start by consuming CloudEvents. Define events in the `events` array:

```json
{
  "events": [
    {
      "name": "OrderReceivedEvent",
      "source": "control-record-service",
      "type": "anax.controlrecord.created",
      "kind": "consumed"
    }
  ]
}
```

### Human-in-the-Loop Callback

Use a `callback` state to pause the workflow until a human submits a correction:

```json
{
  "name": "WaitForCorrection",
  "type": "callback",
  "action": {
    "name": "logException",
    "functionRef": {
      "refName": "sysoutFunction",
      "arguments": { "message": "Waiting for human correction: ${ .controlRecordId }" }
    }
  },
  "eventRef": "HumanCorrectionSubmittedEvent",
  "transition": "ContinueProcessing"
}
```

### Decision Gate (DMN Switch)

Combine `dmn://` with a `switch` state to route workflow execution based on business rules:

```json
{
  "name": "EvaluateOrderType",
  "type": "operation",
  "actions": [
    { "functionRef": { "refName": "orderTypeDecision", "arguments": {} } }
  ],
  "transition": "CheckResult"
},
{
  "name": "CheckResult",
  "type": "switch",
  "dataConditions": [
    { "condition": "${ .OrderTypeRoutable == false }", "transition": "RejectOrder" }
  ],
  "defaultCondition": { "transition": "ProcessOrder" }
}
```

---

## Extending with a New URI Scheme

The starter is extensible. To add a new scheme (e.g., `grpc://`):

1. **Codegen handler** — Create a class extending `WorkItemTypeHandler` in `anax-kogito-codegen-extensions`:

    ```java
    public class GrpcFunctionTypeHandler extends WorkItemTypeHandler {
        @Override public String type() { return "grpc"; }
        @Override public boolean isCustom() { return true; }

        @Override
        protected <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T>
        fillWorkItemHandler(Workflow workflow, ParserContext context,
                            WorkItemNodeFactory<T> factory, FunctionDefinition functionDef) {
            String operation = FunctionTypeHandlerFactory.trimCustomOperation(functionDef);
            // parse grpc://service/method ...
            return factory.workName("grpc")
                          .workParameter("Service", service)
                          .workParameter("Method", method);
        }
    }
    ```

2. **SPI registration** — Add to `META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler`

3. **Runtime handler** — Create a handler extending `DefaultKogitoWorkItemHandler` in the starter and register it as a `@Bean` in the auto-configuration.

4. **Update the catalog** — Add the new scheme definition to `CatalogManifestTask`.

---

## Building from Source

### Prerequisites

- Java 17+
- Gradle 8.10+ (or use the included wrapper)
- Access to the [Metadata Management Platform](docs/canonical-metadata-server.md) if your workflows use `dmn://` or `map://` URIs (set `METADATA_SERVER_URL` env var or configure `anaxKogito.metadataServerUrl` in `build.gradle`)

### Build all modules

```bash
./gradlew build
```

### Publish to local Maven repository

```bash
./gradlew publishToMavenLocal
```

### Run the sample

```bash
./gradlew :anax-kogito-sample:bootRun
```

Then test:

```bash
# Trigger a workflow
curl -s -X POST http://localhost:8085/hello-world \
  -H "Content-Type: application/json" \
  -d '{"name": "World"}' | jq .

# Query the catalog
curl -s http://localhost:8085/anax/catalog | jq .
curl -s http://localhost:8085/anax/catalog/schemes | jq .
```

---

## Architecture Decision Record

See [docs/0006-kogito-custom-uri-spring-boot-starter.md](docs/0006-kogito-custom-uri-spring-boot-starter.md) for the full design rationale, trade-offs, and architectural context.
