# ADR 006: Custom Workflow Spring Boot Starter

**Status:** Proposed

**Date:** March 2026

## 1. Context and Problem Statement

The prototype in `order-routing-service` proved that Kogito's `FunctionTypeHandler` SPI can be extended with custom URI schemes (`dmn://`, `anax://`, `map://`) to enable in-process DMN evaluation, Spring bean invocation, and data-mapping transformations directly from Serverless Workflow JSON definitions. The prototype **works** — codegen emits `WorkItemNode` instead of empty lambdas, and runtime handlers execute correctly.

However, the extension code is tangled into the application project:

- **Codegen-time classes** live in a `codegenExtensions` sourceSet alongside application code.
- **Runtime handlers** are mixed in with domain services.
- **Build wiring** (the `generateKogitoSources` Gradle task, `URLClassLoader` assembly, `codegenExtensions` sourceSet) is hand-rolled and duplicated per project.
- **SPI registration** is manual.
- **No discoverability** — a developer (or AI coding assistant) authoring a new `.sw.json` has no programmatic way to discover which custom URI schemes are available, what parameters they accept, which DMN models exist in the project, which Spring beans are callable, or which form schemas are defined. The developer must read handler source code or ask a teammate.
- **No runtime observability** — there is no mechanism for a running service to report what governance assets (DMN models, Jolt mappings) it is using, making it impossible to detect drift between deployed services and the governed asset inventory.

Any new project that wants `dmn://`, `anax://`, and `map://` must copy all of this infrastructure. This ADR proposes extracting it into a **Spring Boot Starter** that:

1. Gives consuming applications the full capability by adding a single Gradle dependency — **zero Kogito dependencies exposed to consumers**.
2. Provides a **self-contained build** — governance assets (DMN models, Jolt mapping specs) are committed to the project repository in `src/main/resources/`. Builds have zero external dependencies beyond standard Maven repositories. Assets are authored via Copilot + MCP tools and committed to git.
3. Exposes a **metadata catalog** — a structured directory of available schemes, operations, rules, workflows, and form schemas — that both the runtime and AI coding tools (GitHub Copilot) can query to assist with authoring CNCF Serverless Workflow definitions.
4. **Registers with the metadata server at runtime** — on application startup, the starter publishes the catalog to the metadata management platform, enabling real-time observability of which governance assets are in use across running services and drift detection against the governed inventory.

## 2. Decision

Extract the custom URI scheme infrastructure into three publishable Gradle modules:

| Module                            | Artifact ID                                | Purpose                                                                                                                                                                 |
| --------------------------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `anax-kogito-codegen-extensions`  | `com.anax:anax-kogito-codegen-extensions`  | Codegen-time SPI — `FunctionTypeHandler` implementations for `dmn://`, `anax://`, `map://` + `META-INF/services` file                                                   |
| `anax-kogito-spring-boot-starter` | `com.anax:anax-kogito-spring-boot-starter` | Runtime auto-configuration — `WorkItemHandler` beans + `WorkItemHandlerConfig` + **metadata catalog REST endpoint**                                                     |
| `anax-kogito-codegen-plugin`      | `com.anax:anax-kogito-codegen-plugin`      | Gradle plugin — encapsulates the `generateKogitoSources` task, `codegenExtensions` classpath wiring, Kogito BOM management, and **static metadata manifest generation** |

## 3. Architecture

### 3.1 Governance Asset Lifecycle

The starter participates in a **three-phase lifecycle** for governance assets. This lifecycle decouples the build from external services while enabling runtime observability.

```
┌──────────────────────────────────────────────────────────────────┐
│                  AUTHOR (IDE → Metadata Server)                  │
│                                                                  │
│  Primary: Copilot + MCP tools (19 validated MCP tools)           │
│    create_decision, update_decision, validate_decision, etc.     │
│    create_mapping, create_workflow, create_model, etc.           │
│                                                                  │
│  Copilot writes DMN/Jolt files directly into src/main/resources/ │
│  Developer commits governance assets to git                      │
│                                                                  │
│  Fallback: Metadata server REST API / UI for non-Copilot flows   │
│                                                                  │
│  Safety: All MCP write tools force status: "draft"               │
│  Human promotes draft → active via metadata platform UI          │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                      BUILD TIME (Gradle)                         │
│                                                                  │
│  Self-contained — no external service dependencies               │
│                                                                  │
│  sw.json + .dmn (committed) ──► kogito-codegen ──► Generated Java│
│                                      ▲                           │
│                                      │ ServiceLoader             │
│                                 ┌────┴──────────────────────┐    │
│                                 │ anax-kogito-codegen-       │    │
│                                 │   extensions.jar           │    │
│                                 │ ┌────────────────────────┐ │    │
│                                 │ │ DmnFunctionTypeHandler  │ │    │
│                                 │ │ AnaxFunctionTypeHandler │ │    │
│                                 │ │ MapFunctionTypeHandler  │ │    │
│                                 │ │ META-INF/services/...   │ │    │
│                                 │ └────────────────────────┘ │    │
│                                 └───────────────────────────┘    │
│                                                                  │
│  Generated code contains:                                        │
│    workItemNode.workName("dmn")                                  │
│    workItemNode.workName("anax")                                 │
│    workItemNode.workName("map")                                  │
│  instead of:                                                     │
│    actionNode.action(kcontext -> {})   ← empty lambda (before)   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                    RUNTIME (Spring Boot)                          │
│                                                                  │
│  Process Engine dispatches WorkItem by name:                     │
│     "dmn"  ──► DmnWorkItemHandler  (evaluates DMN in-process)    │
│     "anax" ──► AnaxWorkItemHandler (invokes Spring bean)         │
│     "map"  ──► MapWorkItemHandler  (applies Jolt transform)      │
│                          ▲                                       │
│                          │ auto-configured by                    │
│                     ┌────┴──────────────────────┐                │
│                     │ anax-kogito-spring-boot-   │                │
│                     │   starter                  │                │
│                     │ ┌────────────────────────┐ │                │
│                     │ │ AnaxKogitoAutoConfig    │ │                │
│                     │ │  → DmnWorkItemHandler   │ │                │
│                     │ │  → AnaxWorkItemHandler  │ │                │
│                     │ │  → MapWorkItemHandler   │ │                │
│                     │ │  → WorkItemHandlerConfig│ │                │
│                     │ └────────────────────────┘ │                │
│                     └───────────────────────────┘                │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│              REGISTER (Running App → Metadata Server)            │
│                                                                  │
│  On ApplicationReadyEvent, the starter publishes the catalog     │
│  to the metadata management platform:                            │
│                                                                  │
│    POST {metadataServerUrl}/api/registrations                    │
│      ├── catalog.json (schemes, DMN models, workflows, beans)    │
│      ├── instance metadata (app name, version, host, timestamp)  │
│      └── heartbeat schedule (periodic re-registration)           │
│                                                                  │
│  Enables:                                                        │
│    • Real-time observability of which decisions are running       │
│    • Drift detection: server compares registered assets against   │
│      governed inventory — flags assets in use that are not        │
│      governed, or governed assets that are not deployed           │
│    • Service catalog: which services use which governance assets  │
│                                                                  │
│  Failure mode: registration failure is logged but does NOT        │
│  prevent the application from starting (fire-and-forget)         │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 Module Dependency Graph

```
consuming-app
  ├── implementation 'com.anax:anax-kogito-spring-boot-starter'     (runtime)
  └── plugins { id 'com.anax.kogito-codegen' }                     (build-time)
          │
          ├── kogitoCodegen 'com.anax:anax-kogito-codegen-extensions' (SPI jar)
          └── kogitoCodegen 'org.kie.kogito:kogito-codegen-manager'   (engine)
```

### 3.3 Gradle Plugin Responsibilities

The `anax-kogito-codegen-plugin` encapsulates all build-time wiring that was previously hand-maintained in `build.gradle`:

1. **Creates `kogitoCodegen` configuration** — resolved at codegen task execution
2. **Registers `generateKogitoSources` task** — builds `URLClassLoader` from `kogitoCodegen` + `runtimeClasspath` + extension jars, runs Kogito codegen reflectively. Reads `.sw.json` and `.dmn` files from `src/main/resources/` (committed to the project repository).
3. **Wires source sets** — adds `build/generated/sources/kogito` and `build/generated/resources/kogito` to `main` sourceSet
4. **Task dependencies** — `compileJava` and `processResources` depend on `generateKogitoSources`
5. **BOM management** — applies Kogito BOM to `kogitoCodegen` and `implementation` configurations
6. **Extension classpath** — automatically puts `anax-kogito-codegen-extensions` on the codegen classpath via `kogitoCodegen` dependency
7. **Automatic DMN dependency** — if any `.dmn` files are present in `src/main/resources/`, adds `org.kie.kogito:kogito-dmn` to the `implementation` configuration so consumers never reference Kogito artifacts directly
8. **All Kogito runtime deps managed transitively** — consumers declare zero `org.kie.kogito` artifacts; the starter and plugin manage the full dependency tree
9. **Plugin GAV** — uses the consuming project's `group`, `name`, and `version` for the Kogito GAV passed to the codegen engine

> **Design note:** The Gradle plugin has **no dependency on the metadata server**. Governance assets (DMN models, Jolt mapping specs) are committed to `src/main/resources/` and read locally. The metadata server is the system of record, but assets flow into the project via Copilot + MCP tools during development, not via the build pipeline. This ensures CI/CD builds are fully self-contained.

#### 3.3.1 Runtime Registration with the Metadata Server

The Spring Boot starter (not the Gradle plugin) handles the metadata server integration at **runtime**. On application startup, the starter publishes the catalog to the metadata management platform for observability and drift detection.

**Registration bean: `MetadataServerRegistrationService`**

Auto-configured in `AnaxKogitoAutoConfiguration` with the following behavior:

1. Listens for `ApplicationReadyEvent`
2. Loads `catalog.json` from classpath (built by Gradle plugin)
3. Constructs a registration payload with:
   - The full catalog (schemes, DMN models, workflows, beans)
   - Instance metadata: application name, version, host/port, startup timestamp
4. POSTs to `{metadataServerUrl}/api/registrations`
5. Optionally schedules periodic heartbeat re-registrations

**Configuration:**

```yaml
anax:
  metadata-server:
    url: http://metadata-platform:3001    # or METADATA_SERVER_URL env var
    registration:
      enabled: true                       # default: true (disabled if url not set)
      heartbeat-interval: 60s             # periodic re-registration interval
```

**Failure mode:** Registration failures are logged at WARN level but do **not** prevent the application from starting. The service is fully functional even without metadata server connectivity.

**Auto-configuration conditions:**

| Bean | Condition |
|------|-----------|
| `MetadataServerRegistrationService` | `@ConditionalOnProperty(prefix = "anax.metadata-server", name = "url")` — only if a metadata server URL is configured |

**Registration payload:**

```json
{
  "applicationName": "my-contribution-service",
  "applicationVersion": "1.2.0",
  "host": "10.0.1.42",
  "port": 8085,
  "startedAt": "2026-03-24T14:30:00Z",
  "catalog": {
    "schemaVersion": "1.0",
    "schemes": [ ... ],
    "dmnModels": [ ... ],
    "workflows": [ ... ],
    "springBeans": [ ... ]
  }
}
```

**Metadata server drift detection (server-side):**

The metadata server compares registration data against its governed asset inventory:

| Scenario | Detection |
|----------|-----------|
| A service uses a DMN model not in the governed inventory | **Ungoverned asset** — flagged for governance review |
| A governed DMN model is not used by any registered service | **Unused asset** — candidate for deprecation |
| A service uses a different version of a governed asset | **Version drift** — flagged for update |
| A registered service stops sending heartbeats | **Stale registration** — service may be down |

> **Note:** Drift detection is implemented by the metadata server, not the starter. The starter's responsibility is limited to publishing the registration. See the [feature request for the metadata team](#registration-feature-request) for the server-side contract.

### 3.4 Copilot-Discoverable Metadata Catalog

The starter's most distinctive feature is a **metadata catalog** that makes the project's full inventory of custom schemes, operations, rules, workflows, and form references machine-readable. This serves two consumers:

1. **AI coding assistants (GitHub Copilot)** — query the catalog at authoring time to generate valid `sw.json` function references, correct URI syntax, and accurate `arguments` blocks.
2. **Runtime introspection** — the catalog REST endpoint lets dashboards, health checks, and operational tools enumerate what a service can do.

#### 3.4.1 Static Metadata Manifest (Build-Time)

The Gradle plugin generates a `META-INF/anax/catalog.json` manifest during the `generateKogitoSources` task by scanning project resources:

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
        {
          "name": "DmnNamespace",
          "description": "DMN model namespace",
          "source": "uri-segment-1"
        },
        {
          "name": "ModelName",
          "description": "DMN model name",
          "source": "uri-segment-2"
        }
      ],
      "handler": "com.anax.kogito.autoconfigure.DmnWorkItemHandler"
    },
    {
      "scheme": "anax",
      "description": "Invoke a method on a Spring bean in the application context",
      "uriPattern": "anax://{beanName}/{methodName}",
      "parameters": [
        {
          "name": "BeanName",
          "description": "Spring bean name",
          "source": "uri-segment-1"
        },
        {
          "name": "MethodName",
          "description": "Method to invoke (default: execute)",
          "source": "uri-segment-2"
        }
      ],
      "handler": "com.anax.kogito.autoconfigure.AnaxWorkItemHandler"
    },
    {
      "scheme": "map",
      "description": "Apply a Jolt data-mapping transformation",
      "uriPattern": "map://{mappingName}",
      "parameters": [
        {
          "name": "MappingName",
          "description": "Registered mapping identifier",
          "source": "uri-segment-1"
        }
      ],
      "handler": "com.anax.kogito.autoconfigure.MapWorkItemHandler"
    }
  ],
  "dmnModels": [
    {
      "namespace": "com.anax.decisions",
      "name": "Order Type Routing",
      "uri": "dmn://com.anax.decisions/Order Type Routing",
      "resource": "order-type-routing.dmn",
      "inputs": ["orderType", "accountClassification"],
      "outputs": ["OrderTypeRoutable"]
    }
  ],
  "workflows": [
    {
      "id": "utility-order-mapping",
      "name": "Automated X9 Mapping Pipeline with Party Lookup",
      "resource": "utility-order-mapping.sw.json",
      "events": [
        {
          "name": "ControlRecordCreatedEvent",
          "type": "anax.controlrecord.created",
          "kind": "consumed"
        },
        {
          "name": "HumanCorrectionSubmittedEvent",
          "type": "anax.controlrecord.corrected",
          "kind": "consumed"
        }
      ],
      "functions": [
        {
          "name": "orderTypeDecisionFunction",
          "operation": "dmn://com.anax.decisions/Order Type Routing"
        },
        {
          "name": "partyLookupFunction",
          "operation": "anax://partyLookupService/lookup"
        },
        {
          "name": "startChildWorkflowFunction",
          "operation": "anax://workflowRouterService/startChild"
        }
      ]
    }
  ],
  "springBeans": [
    {
      "beanName": "partyLookupService",
      "className": "com.anax.routing.service.PartyLookupService",
      "methods": [
        {
          "name": "lookup",
          "parameterType": "java.util.Map",
          "returnType": "java.util.Map"
        }
      ],
      "uri": "anax://partyLookupService/lookup"
    }
  ],
  "formSchemas": [
    {
      "formId": "address-correction",
      "title": "Address Correction",
      "source": "forms-service",
      "fields": ["street", "city", "state", "zipCode", "notes"]
    }
  ]
}
```

The manifest is generated by combining build-time data from multiple sources:

- **DMN models** — committed in `src/main/resources/`; parsed for namespace, model name, input/output variable names
- **Jolt mapping specs** — committed in `src/main/resources/META-INF/anax/mappings/`; cataloged with mapping name and spec summary
- **Workflows** — `src/main/resources/**/*.sw.json` parsed for workflow id, events, function references
- **URI schemes** — `META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler` enumerates registered schemes
- **Spring beans** — classes annotated with `@Component` / `@Service` that have a `public Map<String, Object> xxx(Map<String, Object>)` method signature — discoverable as `anax://` targets
- (Optional) External form schema directory configured via `anax.catalog.form-schemas-url`

#### 3.4.2 Runtime Catalog Endpoint

The starter auto-configures a REST endpoint that serves the catalog at runtime:

```
GET /anax/catalog          → full catalog JSON
GET /anax/catalog/schemes  → list of available URI schemes with patterns
GET /anax/catalog/dmn      → list of DMN models with inputs/outputs
GET /anax/catalog/workflows → list of deployed workflows with events and functions
GET /anax/catalog/beans    → list of anax://-callable Spring beans
```

Auto-configuration:

| Bean | Condition |
|------|-----------||
| `AnaxCatalogController` | `@ConditionalOnProperty(prefix = "anax.catalog", name = "enabled", matchIfMissing = true)` |
| `AnaxCatalogService` | Always — reads `META-INF/anax/catalog.json` from classpath + augments with live Spring bean scan |

The endpoint is enabled by default and can be disabled:

```yaml
anax:
  catalog:
    enabled: false
```

#### 3.4.3 Copilot Instructions File

The starter includes a `.github/copilot-instructions.md` template that consuming projects can adopt. This file teaches Copilot how to use the catalog:

```markdown
## Anax Serverless Workflow Conventions

This project uses the Anax Kogito Spring Boot Starter to build
event-driven CNCF Serverless Workflows.

### Custom Function URI Schemes

When authoring `.sw.json` workflow definitions, use these custom
function types:

| Scheme    | Purpose                             | URI Pattern                      | Example                                       |
| --------- | ----------------------------------- | -------------------------------- | --------------------------------------------- |
| `dmn://`  | Evaluate a DMN decision model       | `dmn://{namespace}/{modelName}`  | `dmn://com.anax.decisions/Order Type Routing` |
| `anax://` | Invoke a Spring bean method         | `anax://{beanName}/{methodName}` | `anax://partyLookupService/lookup`            |
| `map://`  | Apply a data-mapping transformation | `map://{mappingName}`            | `map://x9-field-mapping`                      |

All custom functions use `"type": "custom"` in the sw.json function
definition.

### Discovering Available Operations

Query the metadata catalog for available operations:

- **DMN models**: `GET /anax/catalog/dmn` — returns namespace, model
  name, inputs, outputs
- **Spring beans**: `GET /anax/catalog/beans` — returns bean names,
  methods, and constructed `anax://` URIs
- **Workflows**: `GET /anax/catalog/workflows` — returns deployed
  workflow IDs, events consumed/produced, and function references
- **Full catalog**: `GET /anax/catalog` — complete inventory

The static catalog manifest is at `META-INF/anax/catalog.json` in
the build output.

### Event-Driven Patterns

Workflows consume CloudEvents from Kafka. Define consumed events in
the `events` array with `"kind": "consumed"`. The event `type` field
must match the CloudEvent type published by upstream services.

Callback states (human-in-the-loop) wait for a correction event and
resume the workflow. The `eventRef` must reference a consumed event.
```

#### 3.4.4 Copilot Custom Instructions Integration

The metadata catalog is designed to integrate with Copilot's context mechanisms:

1. **`copilot-instructions.md`** — static guidance on URI patterns and conventions (checked into the project)
2. **`catalog.json` in build output** — Copilot can read this file for project-specific operations when using workspace context
3. **Runtime endpoint** — for MCP-based tool integrations or agent workflows that can query live service metadata

This three-layer approach ensures Copilot has the right context whether the developer is:

- Authoring a new `.sw.json` file (reads `copilot-instructions.md` + `catalog.json`)
- Debugging a running service (queries `/anax/catalog`)
- Building a new contribution service from scratch (reads the starter's bundled instructions)

#### 3.4.5 MCP Server for Copilot Agent Mode

The metadata management platform adds a **fourth layer** — two-way tool access — on top of the three read-only layers above, via an MCP ([Model Context Protocol](https://modelcontextprotocol.io/)) server. MCP support shipped in `margic/anax-metadata-platform:v0.3.0`.

> **Note:** The metadata platform also ships a VS Code extension (`@anax.metadata` chat participant, §14.1 of the platform spec) that provides read-only Copilot Chat tools. That extension was built as an internal dev tool for building the platform's React frontend. It is not the general-purpose integration layer for consuming developers. In a future iteration, the extension will be refactored to use the MCP server as its backend — the extension will continue to own UX concerns (chat participant, system prompt, formatting) while the MCP server centralizes tool definitions.

**MCP Capabilities:**

| Category | Tools | Purpose |
|----------|-------|---------|
| Discovery | `list_models`, `get_model`, `list_decisions`, `get_decision`, `list_workflows`, `get_workflow`, `list_mappings`, `get_mapping`, `resolve_model`, `search_assets`, `get_related_assets` | Browse and read the full governance asset inventory |
| Authoring | `create_model`, `create_decision`, `create_workflow`, `create_mapping` | Create new assets in `draft` status — human promotes to `active` |
| Validation | `validate_model`, `validate_decision`, `validate_workflow`, `validate_mapping` | Validate asset structure before or after creation |

Each MCP tool maps directly to an existing REST API endpoint on the metadata server — the MCP layer is a thin adapter, not new business logic.

**Safety constraint:** All write tools force `status: "draft"` regardless of the caller's input. Copilot cannot create assets that are immediately active in the build pipeline. A human must review and promote every AI-generated asset via the metadata platform UI.

**Transport:** SSE (Server-Sent Events) on the existing Express server at `/mcp/sse`, using the `@modelcontextprotocol/sdk` npm package.

**VS Code configuration** (`.vscode/mcp.json`):

```json
{
  "servers": {
    "anax-metadata": {
      "type": "sse",
      "url": "http://localhost:3001/mcp/sse"
    }
  }
}
```

**"Generate metadata from metadata" workflow:**

```
Developer (VS Code, Copilot Agent Mode):
  "Create a Child Support model similar to Tax Levy"
         │
         ▼
Copilot calls list_models → discovers existing models
Copilot calls get_model("tax-levy-order") → reads full JSON Schema
         │
         ▼
Copilot generates a new model, adapted for Child Support
         │
         ▼
Copilot calls create_model → draft status
Copilot calls validate_model → tests with sample data
         │
         ▼
Developer reviews in platform UI → promotes draft → active
```

This pattern applies to all four asset types. The full MCP tool specification is in [memo-mcp-server-spec.md](memo-mcp-server-spec.md).

**Relationship to other layers:**

| Layer | Mechanism | Direction | When |
|-------|-----------|-----------|------|
| 1 — Instructions | `copilot-instructions.md` | Read | Always (auto-loaded) |
| 2 — Static catalog | `catalog.json` | Read | After build |
| 3 — Runtime endpoint | `GET /anax/catalog` | Read | Running service |
| 4 — MCP server | MCP tools on metadata server | Read + Write | Running metadata server |

Layers 1–3 are provided by the starter. Layer 4 is provided by the metadata management platform.

### 3.5 Auto-Configuration Design

The starter follows Spring Boot auto-configuration conventions:

```
anax-kogito-spring-boot-starter/
  src/main/java/
    com/anax/kogito/
      autoconfigure/
        AnaxKogitoAutoConfiguration.java      ← @AutoConfiguration
        DmnWorkItemHandler.java               ← @Bean (conditional)
        AnaxWorkItemHandler.java              ← @Bean (conditional)
        MapWorkItemHandler.java               ← @Bean (conditional)
        AnaxKogitoWorkItemHandlerConfig.java  ← @Bean wiring handler registration
        AnaxKogitoProperties.java             ← @ConfigurationProperties
      catalog/
        AnaxCatalogService.java               ← reads catalog.json + live bean scan
        AnaxCatalogController.java            ← REST endpoint: /anax/catalog
        CatalogModel.java                     ← Java record model for catalog JSON
      registration/
        MetadataServerRegistrationService.java ← publishes catalog on startup
        RegistrationPayload.java              ← registration data model
  src/main/resources/
    META-INF/
      spring/
        org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Key auto-configuration conditions:

| Bean                                   | Condition                                                                                  |
| -------------------------------------- | ------------------------------------------------------------------------------------------ |
| `DmnWorkItemHandler`                   | `@ConditionalOnClass(DecisionModels.class)` — only if DMN runtime is on classpath          |
| `AnaxWorkItemHandler`                  | `@ConditionalOnMissingBean` — allow consumer override                                      |
| `MapWorkItemHandler`                   | `@ConditionalOnMissingBean` — allow consumer override                                      |
| `AnaxKogitoWorkItemHandlerConfig`      | Always — registers whatever handlers are present                                           |
| `AnaxCatalogController`                | `@ConditionalOnProperty(prefix = "anax.catalog", name = "enabled", matchIfMissing = true)` |
| `AnaxCatalogService`                   | Always — reads `META-INF/anax/catalog.json` + live `ApplicationContext` scan               |
| `MetadataServerRegistrationService`    | `@ConditionalOnProperty(prefix = "anax.metadata-server", name = "url")` — only if server URL is configured |

## 4. Repository Structure

```
custom-workflow-starter/                       ← new repository
├── settings.gradle
├── gradle.properties                         ← kogitoVersion=10.1.0
├── build.gradle                              ← shared config, BOM import
│
├── anax-kogito-codegen-extensions/           ← MODULE 1
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/anax/kogito/codegen/
│       │   ├── DmnFunctionTypeHandler.java
│       │   ├── AnaxFunctionTypeHandler.java
│       │   └── MapFunctionTypeHandler.java
│       └── resources/META-INF/services/
│           └── org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler
│
├── anax-kogito-spring-boot-starter/          ← MODULE 2
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/anax/kogito/
│       │   ├── autoconfigure/
│       │   │   ├── AnaxKogitoAutoConfiguration.java
│       │   │   ├── DmnWorkItemHandler.java
│       │   │   ├── AnaxWorkItemHandler.java
│       │   │   ├── MapWorkItemHandler.java
│       │   │   ├── AnaxKogitoWorkItemHandlerConfig.java
│       │   │   └── AnaxKogitoProperties.java
│       │   ├── catalog/
│       │   │   ├── AnaxCatalogService.java
│       │   │   ├── AnaxCatalogController.java
│       │   │   └── CatalogModel.java
│       │   └── registration/
│       │       ├── MetadataServerRegistrationService.java
│       │       └── RegistrationPayload.java
│       └── resources/
│           └── META-INF/
│               └── spring/
│                   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│
├── anax-kogito-codegen-plugin/               ← MODULE 3
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/anax/kogito/gradle/
│       │   ├── AnaxKogitoCodegenPlugin.java
│       │   ├── AnaxKogitoExtension.java          ← plugin config (kogitoVersion, etc.)
│       │   └── CatalogManifestTask.java           ← generates META-INF/anax/catalog.json
│       └── resources/META-INF/gradle-plugins/
│           └── com.anax.kogito-codegen.properties
│
└── anax-kogito-sample/                       ← MODULE 4 (example / integration test)
    ├── .github/
    │   └── copilot-instructions.md           ← Copilot context for sw.json authoring
    ├── build.gradle
    └── src/main/
        ├── java/com/example/demo/
        │   ├── DemoApplication.java
        │   └── GreetingService.java
        └── resources/
            ├── application.yml
            └── hello-world.sw.json
```

## 5. Consuming Application Experience

After the starter is published, a new project needs only:

**`build.gradle`:**

```gradle
plugins {
    id 'org.springframework.boot' version '3.3.7'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'com.anax.kogito-codegen' version '0.1.0'
}

dependencies {
    implementation 'com.anax:anax-kogito-spring-boot-starter:0.1.0'
    // All Kogito runtime deps are pulled transitively — consumers declare ZERO org.kie.kogito artifacts
}
```

**`application.yml`:**

```yaml
anax:
  metadata-server:
    url: http://metadata-platform:3001    # enables runtime registration (optional)
```

**`src/main/resources/my-workflow.sw.json`:**

```json
{
  "functions": [
    {
      "name": "evaluateRisk",
      "type": "custom",
      "operation": "dmn://com.example/Risk Assessment"
    },
    {
      "name": "sendNotification",
      "type": "custom",
      "operation": "anax://notificationService/send"
    },
    {
      "name": "transformPayload",
      "type": "custom",
      "operation": "map://x9-field-mapping"
    }
  ]
}
```

**That's it.** No `codegenExtensions` sourceSet, no `ServiceLoader` files, no `generateKogitoSources` task, no `CustomWorkItemHandlerConfig`, no `org.kie.kogito` dependencies. The plugin runs Kogito codegen against committed DMN models and workflow definitions, the starter auto-configures all work-item handlers at runtime, and the service registers itself with the metadata server for observability.

### 5.1 Copilot-Assisted Workflow Authoring

When a developer creates a new `.sw.json` file, Copilot has three sources of context:

1. **`.github/copilot-instructions.md`** — project-level instructions explaining the custom URI schemes, patterns, and conventions. The starter ships a template; the consuming project customises it.

2. **`META-INF/anax/catalog.json`** — the build-generated manifest listing every DMN model (with inputs/outputs), every callable Spring bean (with method signatures), every workflow (with events and function refs), and every registered mapping. Copilot reads this from the build output to generate valid function references.

3. **`GET /anax/catalog`** — the live runtime endpoint. For MCP-integrated agents or Copilot agent mode with tool access, the running service can be queried to discover operations dynamically.

**Authoring flow (Copilot-assisted):**

```
Developer creates new .sw.json file
     │
     ▼
Copilot reads copilot-instructions.md
  → learns URI scheme patterns (dmn://, anax://, map://)
     │
     ▼
Copilot reads catalog.json from build output
  → discovers available DMN models, bean methods, mappings
     │
     ▼
Copilot generates valid function definitions with correct
URIs, argument shapes, and event references
```

This turns the starter from a build-time convenience into a **Copilot-native development platform** — the AI assistant knows what the service can do and generates correct workflow definitions on the first attempt.

## 6. What Moves Where

| POC Location                                             | Starter Location                  | Notes                                             |
| -------------------------------------------------------- | --------------------------------- | ------------------------------------------------- |
| `src/codegenExtensions/.../DmnFunctionTypeHandler.java`  | `anax-kogito-codegen-extensions`  | Package changes to `com.anax.kogito.codegen`      |
| `src/codegenExtensions/.../AnaxFunctionTypeHandler.java` | `anax-kogito-codegen-extensions`  | Same                                              |
| *(new)* `MapFunctionTypeHandler.java`                    | `anax-kogito-codegen-extensions`  | New — handles `map://` URIs at codegen time       |
| `META-INF/services/...FunctionTypeHandler`               | `anax-kogito-codegen-extensions`  | Same file, updated class names, adds Map handler  |
| `src/main/.../DmnWorkItemHandler.java`                   | `anax-kogito-spring-boot-starter` | Becomes `@Bean` in auto-config (not `@Component`) |
| `src/main/.../AnaxWorkItemHandler.java`                  | `anax-kogito-spring-boot-starter` | Same                                              |
| *(new)* `MapWorkItemHandler.java`                        | `anax-kogito-spring-boot-starter` | New — Jolt stub handler, loads specs from classpath |
| `src/main/.../CustomWorkItemHandlerConfig.java`          | `anax-kogito-spring-boot-starter` | Becomes auto-config bean                          |
| `build.gradle` codegen task + sourceSets                 | `anax-kogito-codegen-plugin`      | Encapsulated in Gradle plugin                     |
| *(new)* `ResolveGovernanceAssetsTask.java`               | `anax-kogito-codegen-plugin`      | New — fetches assets from metadata server         |
| `hello-world.sw.json`                                    | `anax-kogito-sample`              | Example project                                   |
| `HelloService.java`                                      | `anax-kogito-sample`              | Example—never in the starter                      |
| `PartyLookupService.java`                                | stays in `order-routing-service`  | Domain code—never in the starter                  |
| `WorkflowRouterService.java`                             | stays in `order-routing-service`  | Domain code—never in the starter                  |

## 7. Key Design Decisions

### 7.1 Separate Codegen Extensions JAR

The SPI classes (`DmnFunctionTypeHandler`, `AnaxFunctionTypeHandler`) must be a **separate JAR** from the starter because:

- They run at **build time** inside the Kogito codegen `URLClassLoader`
- They depend on `kogito-serverless-workflow-builder` (codegen API), not Spring Boot
- They must **not** pull in Spring Boot transitive dependencies into the codegen classpath

### 7.2 Gradle Plugin vs Convention Plugin

A full Gradle plugin (with `Plugin<Project>` class) is preferred over a convention plugin because:

- It can be published to a Maven repository and consumed via `plugins { id }` block
- It encapsulates the `URLClassLoader` + reflective codegen invocation (complex, error-prone if copied)
- It can version-lock the `kogito-codegen-manager` and `anax-kogito-codegen-extensions` dependencies

### 7.3 Spring Boot 3 Auto-Configuration

Uses the Spring Boot 3 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` mechanism (not the deprecated `spring.factories`). This ensures forward compatibility with Spring Boot 3.x and 4.x.

### 7.4 `@ConditionalOnClass` for DMN Handler

`DmnWorkItemHandler` is conditional on `DecisionModels.class` being on the classpath. This allows projects that only use `anax://` (no DMN) to skip the DMN runtime dependency entirely.

### 7.5 Self-Contained Builds, Runtime Registration

The build pipeline has **zero dependency on the metadata server**. Governance assets (DMN models, Jolt mapping specs) are committed to `src/main/resources/` and read locally by the Gradle plugin. This is a deliberate choice:

- **CI independence** — builds succeed without network access to the metadata server; no fragile external dependency in the build chain
- **Developer ownership** — engineers have a local copy of every governance asset their service uses, committed to git with full version history
- **Reproducible builds** — the same commit always produces the same output
- **Copilot as the bridge** — governance assets flow from the metadata server into the project via Copilot + MCP tools during development, not via automated build-time fetch

The metadata server remains the **system of record** for governance assets. The connection between the metadata server and running services is established at **runtime** via the starter's registration service:

- On application startup, the starter publishes the catalog to the metadata server
- The metadata server uses registration data for observability, drift detection, and service catalog features
- Registration failure does not prevent the application from starting

This "author locally, register at runtime" model gives teams the best of both worlds: independent builds and centralized governance visibility.

### 7.6 Copilot + MCP as the Primary Authoring Path

Governance assets are authored and updated primarily through GitHub Copilot with MCP tools connected to the metadata server. This means:

- The developer stays in the IDE for the full workflow — create/update assets on the server, export to local files, commit to git
- The metadata server's 19 MCP tools (create, update, validate, list, get for each asset type) provide full CRUD capability
- All MCP write tools force `status: "draft"` — a human must promote to `active` via the metadata platform UI
- REST API / UI serve as the fallback for non-Copilot workflows

### 7.6 Jolt Transformations for `map://`

The `map://` scheme uses [Jolt](https://github.com/bazaarvoice/jolt) transformation specs fetched from the metadata server, rather than requiring consumers to implement `Function<Map, Map>` beans. This approach:

- Keeps transformation logic declarative and versionable as metadata assets
- Allows non-developer users to author and update mappings via the metadata server UI
- Decouples mapping definitions from application code deployments

The Jolt execution engine is wired in a later iteration; the initial `MapWorkItemHandler` is a stub that loads specs from the classpath. This establishes the URI pattern and metadata server fetch pipeline first.

### 7.7 Zero Kogito Exposure to Consumers

Consumers never declare `org.kie.kogito` artifacts in their `build.gradle`. The starter manages all Kogito runtime dependencies transitively, and the plugin manages all codegen dependencies. This:

- Prevents version conflicts between consumer-declared and starter-declared Kogito deps
- Simplifies upgrades — only the starter version needs to change
- Abstracts the Kogito implementation detail from the consuming application

## 8. Constraints

- **Java 17** — minimum target; no Java 21+ features
- **Spring Boot 3.x** — specifically 3.3.7 (current), auto-config must work with 3.2+
- **Gradle 8.x+** — plugin uses current Gradle API, no deprecated APIs
- **Kogito 10.1.0** — pinned; the starter manages this version centrally
- **No Maven support** — Gradle-only for now (Maven plugin is a future enhancement)
- **Governance assets committed to git** — DMN models and Jolt mapping specs must be committed to `src/main/resources/`; the build does not fetch from external services

## 9. Consequences

### Positive

- **One-line adoption** — `id 'com.anax.kogito-codegen'` + one `implementation` dependency
- **No boilerplate in consuming apps** — no codegen task, no sourceSet magic, no SPI files, no `org.kie.kogito` dependencies
- **Self-contained builds** — governance assets are committed to the repo; builds have zero external service dependencies
- **Runtime observability** — the starter registers with the metadata server on startup, enabling real-time visibility of which governance assets are deployed and drift detection
- **Copilot-native development** — the metadata catalog enables AI coding assistants to discover available operations, DMN models, bean methods, and event types, generating valid `.sw.json` definitions without manual lookup
- **Copilot + MCP authoring** — governance assets are authored and updated via Copilot with MCP tools, keeping the developer in the IDE
- **Three URI schemes** — `dmn://` (in-process decisions), `anax://` (Spring bean invocation), `map://` (Jolt data transformations) cover the core patterns for contribution service workflows
- **Machine-readable catalog** — `catalog.json` manifest + `/anax/catalog` REST endpoint make the service self-describing for tooling, dashboards, and AI agents
- **Testable in isolation** — each module has its own unit/integration tests
- **Versioned and releasable** — proper SemVer, consumers control upgrade timing
- **Extensible** — new URI schemes (e.g., `rest://`, `grpc://`) follow the same SPI + handler + catalog pattern

### Negative

- **Three modules to maintain** — more release coordination than a single project
- **Gradle plugin testing** — requires `GradleRunner` functional tests, which are slower
- **Kogito version coupling** — starter pins a Kogito version; consumers can't easily override
- **Catalog freshness** — the static manifest is generated at build time; runtime bean scan augments it, but newly added beans require a rebuild for the manifest to be complete
- **Governance assets must be committed** — developers must commit DMN models and Jolt specs to `src/main/resources/`; there is no automatic sync from the metadata server (Copilot + MCP is the bridge)

### Risks

- Kogito 10.2.0 ships a native Gradle plugin that conflicts — mitigation: monitor Kogito releases, sunset our plugin when upstream catches up
- The reflective codegen invocation breaks on Kogito API changes — mitigation: integration test in `anax-kogito-sample` runs codegen from published JARs
- Catalog endpoint leaks internal service structure — mitigation: disable via `anax.catalog.enabled=false` in production profiles, or restrict to management port
- Registration data leaks service topology — mitigation: disable via `anax.metadata-server.registration.enabled=false`; registration endpoint on metadata server should require authentication
