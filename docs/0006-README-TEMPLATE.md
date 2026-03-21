# Custom Workflow Spring Boot Starter

> Build event-driven CNCF Serverless Workflows with custom URI schemes, Kogito codegen, build-time asset resolution from a metadata server, and a Copilot-discoverable metadata catalog.

**Java 17** · **Spring Boot 3.3.7** · **Kogito 10.1.0** · **Gradle 8.10+** · **CNCF Serverless Workflow spec v0.8**

---

## What Is This?

The Custom Workflow Spring Boot Starter is a development platform for building **contribution services** — Spring Boot microservices that execute event-driven workflows authored as CNCF Serverless Workflow JSON definitions.

It extends the Kogito runtime with three custom URI schemes (`dmn://`, `anax://`, `map://`) that let workflow authors invoke DMN decisions, Spring beans, and Jolt data transformations directly from `.sw.json` files — without writing Java glue code.

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

dependencies {
    implementation 'com.anax:anax-kogito-spring-boot-starter:0.1.0'

    // Add DMN runtime if using dmn:// functions
    implementation 'org.kie.kogito:kogito-dmn'
}
```

### 2. Create a workflow definition

**`src/main/resources/my-workflow.sw.json`:**

```json
{
  "id": "my-workflow",
  "name": "My Contribution Workflow",
  "version": "1.0",
  "specVersion": "0.8",
  "start": "EvaluateOrder",
  "events": [
    {
      "name": "OrderReceivedEvent",
      "source": "upstream-service",
      "type": "com.example.order.received",
      "kind": "consumed"
    }
  ],
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
    },
    {
      "name": "logResult",
      "type": "custom",
      "operation": "sysout"
    }
  ],
  "states": [
    {
      "name": "EvaluateOrder",
      "type": "operation",
      "actions": [
        {
          "name": "runDecision",
          "functionRef": {
            "refName": "evaluateOrderType",
            "arguments": {}
          }
        }
      ],
      "transition": "EnrichAndTransform"
    },
    {
      "name": "EnrichAndTransform",
      "type": "operation",
      "actions": [
        {
          "name": "enrich",
          "functionRef": {
            "refName": "enrichPayload",
            "arguments": {
              "orderId": "${ .orderId }"
            }
          }
        },
        {
          "name": "transform",
          "functionRef": {
            "refName": "transformToX9",
            "arguments": {}
          }
        }
      ],
      "transition": "Done"
    },
    {
      "name": "Done",
      "type": "operation",
      "actions": [
        {
          "name": "log",
          "functionRef": {
            "refName": "logResult",
            "arguments": {
              "message": "Workflow complete for: ${ .orderId }"
            }
          }
        }
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
gradle bootRun
```

The Gradle plugin handles Kogito code generation automatically. Spring Boot auto-configuration registers all work-item handlers. No additional wiring needed.

---

## Modules

| Module                            | Artifact                                   | Purpose                                                                                                                                                  |
| --------------------------------- | ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `anax-kogito-codegen-extensions`  | `com.anax:anax-kogito-codegen-extensions`  | Build-time SPI — teaches Kogito codegen to recognize `dmn://`, `anax://`, `map://` URI schemes and emit `WorkItemNode` entries in generated process code |
| `anax-kogito-spring-boot-starter` | `com.anax:anax-kogito-spring-boot-starter` | Runtime auto-configuration — registers work-item handlers for each URI scheme, provides the metadata catalog REST endpoint                               |
| `anax-kogito-codegen-plugin`      | `com.anax:anax-kogito-codegen-plugin`      | Gradle plugin — encapsulates `generateKogitoSources` task, classpath wiring, BOM management, and `catalog.json` manifest generation                      |
| `anax-kogito-sample`              | — (not published)                          | Example application demonstrating all features                                                                                                           |

### How they fit together

```
                       BUILD TIME                              RUNTIME
                    ┌──────────────┐                    ┌──────────────────┐
  .sw.json ───────►│ Gradle Plugin │──► Generated Java ─►│ Spring Boot App  │
  .dmn             │  (Module 3)   │                    │                  │
                   │               │                    │  Starter (Mod 2) │
                   │  Codegen Ext  │                    │    dmn handler   │
                   │  (Module 1)   │                    │    anax handler  │
                   │               │                    │    map handler   │
                   │  catalog.json │                    │    catalog API   │
                   └──────────────┘                    └──────────────────┘
```

---

## Custom URI Schemes

### `dmn://` — In-Process DMN Decision Evaluation

Evaluates a DMN decision model that is bundled in the same application.

**URI format:** `dmn://{namespace}/{modelName}`

**Example:**

```json
{
  "name": "evaluateRisk",
  "type": "custom",
  "operation": "dmn://com.anax.decisions/Order Type Routing"
}
```

| Segment     | Description                                                | Example              |
| ----------- | ---------------------------------------------------------- | -------------------- |
| `namespace` | DMN model namespace (from `<definitions namespace="...">`) | `com.anax.decisions` |
| `modelName` | DMN model name (from `<definitions name="...">`)           | `Order Type Routing` |

**How it works:**

1. At build time, `DmnFunctionTypeHandler` parses the URI and emits a `WorkItemNode` with `workName("dmn")` and parameters `DmnNamespace` and `ModelName`.
2. At runtime, `DmnWorkItemHandler` uses Kogito's `DecisionModels` API to evaluate the DMN model in-process. All input variables from the workflow data are passed to the DMN context. Decision results are merged back into the workflow data.

**Requirements:** Add `org.kie.kogito:kogito-dmn` to your dependencies. Place `.dmn` files in `src/main/resources/`.

---

### `anax://` — Spring Bean Method Invocation

Invokes a method on any Spring-managed bean by name.

**URI format:** `anax://{beanName}/{methodName}`

**Example:**

```json
{
  "name": "lookupParty",
  "type": "custom",
  "operation": "anax://partyLookupService/lookup"
}
```

| Segment      | Description                                                          | Default      |
| ------------ | -------------------------------------------------------------------- | ------------ |
| `beanName`   | Spring bean name (from `@Component("name")` or lowercase class name) | — (required) |
| `methodName` | Method to invoke on the bean                                         | `execute`    |

**Bean method contract:**

```java
public Map<String, Object> methodName(Map<String, Object> params)
```

The method receives all workflow data variables as a `Map` and must return a `Map` whose entries are merged back into the workflow data.

**How it works:**

1. At build time, `AnaxFunctionTypeHandler` parses the URI and emits a `WorkItemNode` with `workName("anax")` and parameters `BeanName` and `MethodName`.
2. At runtime, `AnaxWorkItemHandler` looks up the bean in the Spring `ApplicationContext` and invokes the named method reflectively.

---

### `map://` — Jolt Data Transformation

Applies a [Jolt](https://github.com/bazaarvoice/jolt) transformation spec fetched from the metadata server at build time.

**URI format:** `map://{mappingName}`

**Example:**

```json
{
  "name": "transformToX9",
  "type": "custom",
  "operation": "map://x9-field-mapping"
}
```

| Segment       | Description                                    |
| ------------- | ---------------------------------------------- |
| `mappingName` | Mapping identifier on the metadata server      |

**How it works:**

1. At build time, `resolveGovernanceAssets` fetches the Jolt spec from `GET /api/mappings/{mappingName}` on the metadata server and places it at `META-INF/anax/mappings/{mappingName}.json` on the classpath.
2. At build time, `MapFunctionTypeHandler` parses the URI and emits a `WorkItemNode` with `workName("map")` and parameter `MappingName`.
3. At runtime, `MapWorkItemHandler` loads the Jolt spec from the classpath and applies the transformation.

**Note:** The initial `MapWorkItemHandler` implementation is a stub — the Jolt execution engine will be wired in a later iteration. The stub establishes the URI pattern and metadata server fetch pipeline.

---

## Metadata Catalog

The starter generates a machine-readable catalog of everything the service can do. This serves two purposes:

1. **Copilot/AI assistance** — AI coding tools read the catalog to generate valid `.sw.json` definitions with correct URIs, argument shapes, and event references.
2. **Runtime introspection** — dashboards and operational tools can enumerate deployed capabilities.

### Build-Time Manifest

The Gradle plugin generates `META-INF/anax/catalog.json` during the build by scanning:

| Source            | What is extracted                                                                                                                   |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `*.dmn` files     | Namespace, model name, input/output variables → DMN model entries with constructed `dmn://` URIs                                    |
| `*.sw.json` files | Workflow ID, name, events, function references → workflow entries                                                                   |
| `*.java` files    | `@Component`/`@Service` beans with `Map<String,Object> → Map<String,Object>` methods → bean entries with constructed `anax://` URIs |
| SPI registration  | Registered `FunctionTypeHandler` implementations → scheme definitions                                                               |

Example `catalog.json`:

```json
{
  "schemaVersion": "1.0",
  "generatedAt": "2026-03-21T12:00:00Z",
  "schemes": [
    {
      "scheme": "dmn",
      "description": "Evaluate a DMN decision model in-process",
      "uriPattern": "dmn://{namespace}/{modelName}",
      "parameters": [
        { "name": "DmnNamespace", "description": "DMN model namespace", "source": "uri-segment-1" },
        { "name": "ModelName", "description": "DMN model name", "source": "uri-segment-2" }
      ],
      "handler": "com.anax.kogito.autoconfigure.DmnWorkItemHandler"
    },
    {
      "scheme": "anax",
      "description": "Invoke a method on a Spring bean in the application context",
      "uriPattern": "anax://{beanName}/{methodName}",
      "parameters": [
        { "name": "BeanName", "description": "Spring bean name", "source": "uri-segment-1" },
        { "name": "MethodName", "description": "Method to invoke (default: execute)", "source": "uri-segment-2" }
      ],
      "handler": "com.anax.kogito.autoconfigure.AnaxWorkItemHandler"
    },
    {
      "scheme": "map",
      "description": "Apply a Jolt data transformation",
      "uriPattern": "map://{mappingName}",
      "parameters": [
        { "name": "MappingName", "description": "Registered mapping identifier", "source": "uri-segment-1" }
      ],
      "handler": "com.anax.kogito.autoconfigure.MapWorkItemHandler"
    }
  ],
  "dmnModels": [ ... ],
  "workflows": [ ... ],
  "springBeans": [ ... ],
  "formSchemas": [ ... ]
}
```

### Runtime REST Endpoint

The starter auto-configures a REST API under `/anax/catalog`:

| Endpoint                      | Returns                                                    |
| ----------------------------- | ---------------------------------------------------------- |
| `GET /anax/catalog`           | Full catalog JSON                                          |
| `GET /anax/catalog/schemes`   | Available URI schemes with patterns and parameters         |
| `GET /anax/catalog/dmn`       | DMN models with namespace, name, inputs, outputs           |
| `GET /anax/catalog/workflows` | Deployed workflows with events and function references     |
| `GET /anax/catalog/beans`     | Spring beans callable via `anax://` with method signatures |

The runtime endpoint augments the static manifest with a live `ApplicationContext` scan, so beans added after the last build are still discoverable.

---

## Copilot Integration

The starter is designed to be a **Copilot-native development platform**. Three layers provide context to AI coding assistants:

### Layer 1: Project Instructions

Add a `.github/copilot-instructions.md` to your project (a template ships with the sample module). This teaches Copilot the URI scheme patterns, conventions, and where to find the catalog:

```markdown
## Anax Serverless Workflow Conventions

This project uses the Anax Kogito Spring Boot Starter.

### Custom Function URI Schemes

| Scheme    | URI Pattern                      | Example                                       |
| --------- | -------------------------------- | --------------------------------------------- |
| `dmn://`  | `dmn://{namespace}/{modelName}`  | `dmn://com.anax.decisions/Order Type Routing` |
| `anax://` | `anax://{beanName}/{methodName}` | `anax://partyLookupService/lookup`            |
| `map://`  | `map://{mappingName}`            | `map://x9-field-mapping`                      |

### Discovering Operations

See `build/generated/resources/kogito/META-INF/anax/catalog.json`
or query `GET /anax/catalog` at runtime.
```

### Layer 2: Static Catalog

After a build, `catalog.json` is in the build output. Copilot reads this file from workspace context to discover project-specific DMN models, beans, and workflows — generating valid function references without manual lookup.

### Layer 3: Runtime Endpoint

For MCP-integrated agents or Copilot agent mode with tool access, the running service can be queried at `/anax/catalog` to discover all operations dynamically.

### Authoring Flow

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

All configuration is under the `anax` prefix in `application.yml`:

| Property                        | Type      | Default | Description                                                         |
| ------------------------------- | --------- | ------- | ------------------------------------------------------------------- |
| `anax.catalog.enabled`          | `boolean` | `true`  | Enable/disable the `/anax/catalog` REST endpoint                    |
| `anax.catalog.form-schemas-url` | `String`  | —       | Optional URL to an external forms-service for form schema discovery |

**Example `application.yml`:**

```yaml
server:
  port: 8084

spring:
  application:
    name: my-contribution-service

anax:
  catalog:
    enabled: true
    form-schemas-url: http://forms-service:8083/api/forms

kogito:
  workflow:
    version-strategy: workflow
```

### Disabling the catalog in production

```yaml
# application-prod.yml
anax:
  catalog:
    enabled: false
```

---

## Building from Source

### Prerequisites

- Java 17+
- Gradle 8.10+ (or use the included wrapper)

### Build all modules

```bash
git clone https://github.com/margic/custom-workflow-starter.git
cd custom-workflow-starter
./gradlew build
```

### Publish to local Maven repository

```bash
./gradlew publishToMavenLocal
```

This publishes all three modules to `~/.m2/repository` so consuming projects can resolve them.

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

# Query specific catalog sections
curl -s http://localhost:8085/anax/catalog/schemes | jq .
curl -s http://localhost:8085/anax/catalog/beans | jq .
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
  ],
  "states": [
    {
      "name": "ReceiveOrder",
      "type": "event",
      "onEvents": [
        {
          "eventRefs": ["OrderReceivedEvent"],
          "actions": []
        }
      ],
      "transition": "ProcessOrder"
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
      "arguments": {
        "message": "Waiting for human correction: ${ .controlRecordId }"
      }
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
    {
      "functionRef": {
        "refName": "orderTypeDecision",
        "arguments": {}
      }
    }
  ],
  "transition": "CheckResult"
},
{
  "name": "CheckResult",
  "type": "switch",
  "dataConditions": [
    {
      "condition": "${ .OrderTypeRoutable == false }",
      "transition": "RejectOrder"
    }
  ],
  "defaultCondition": {
    "transition": "ProcessOrder"
  }
}
```

---

## Writing a Custom URI Scheme

The starter is extensible. To add a new URI scheme (e.g., `grpc://`):

### 1. Create a codegen-time handler

In `anax-kogito-codegen-extensions`, create a new class extending `WorkItemTypeHandler`:

```java
public class GrpcFunctionTypeHandler extends WorkItemTypeHandler {
    @Override
    public String type() { return "grpc"; }

    @Override
    public boolean isCustom() { return true; }

    @Override
    protected <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T>
    fillWorkItemHandler(Workflow workflow, ParserContext context,
                        WorkItemNodeFactory<T> factory, FunctionDefinition functionDef) {
        String operation = FunctionTypeHandlerFactory.trimCustomOperation(functionDef);
        // parse grpc://service/method
        // ...
        return factory.workName("grpc")
                      .workParameter("Service", service)
                      .workParameter("Method", method);
    }
}
```

### 2. Register in SPI file

Add to `META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler`:

```
com.anax.kogito.codegen.GrpcFunctionTypeHandler
```

### 3. Create a runtime handler

In `anax-kogito-spring-boot-starter`, create a handler extending `DefaultKogitoWorkItemHandler` and register it as a `@Bean` in the auto-configuration.

### 4. Update the catalog

Add the new scheme definition to the `CatalogManifestTask` so it appears in `catalog.json`.

---

## Architecture Decision Record

See [ADR 006: Kogito Custom URI Spring Boot Starter](docs/0006-kogito-custom-uri-spring-boot-starter.md) for the full design rationale, trade-offs, and architectural context.

## Implementation Plan

See [0006-IMPLEMENTATION-PLAN.md](docs/0006-IMPLEMENTATION-PLAN.md) for the step-by-step prompt sequence used to scaffold this project.

---

## Related ADRs

| ADR                                                        | Title                                  | Relevance                                                        |
| ---------------------------------------------------------- | -------------------------------------- | ---------------------------------------------------------------- |
| [001](docs/0001-adopt-event-driven-serverless-workflow.md) | Adopt Event-Driven Serverless Workflow | Establishes the sw.json-based orchestration paradigm             |
| [002](docs/0002-order-type-decision-table-gate.md)         | Order Type Decision Table Gate         | First DMN integration — the `dmn://` scheme generalises this     |
| [003](docs/0003-dynamic-workflow-ux-from-metadata.md)      | Dynamic Workflow UX from Metadata      | UX derives from sw.json — catalog extends this to tooling        |
| [004](docs/0004-persistence-state-in-control-record.md)    | Persistence State in Control Record    | Control record integration pattern used by contribution services |
