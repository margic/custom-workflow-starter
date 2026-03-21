# Project Anax: Metadata Management Platform — Proof of Concept Specification

**Target Audience:** Engineering Team & GitHub Copilot Agent  
**Objective:** Build a metadata management platform that serves as the authoring, cataloging, and operational inspection layer for **Governance Assets** — the versionable metadata artifacts (models, workflows, decisions, mappings) that define how legal orders and subpoenas are processed through CNCF Serverless Workflow automation pipelines — with rich, context-aware editors and a unified canonical model that drives both validation and UI rendering.  
**Environment:** Single Devcontainer / GitHub Codespaces  
**Revision:** v2 (2026-03-05)

---

## 1. Context & Lineage

### 1.1 What We Proved in the Dynamic Forms PoC

The Dynamic Forms PoC (`main` branch) validated three core ideas:

- **Metadata-driven UIs work.** JSON form definitions drove dynamic rendering without per-form code changes.
- **A single definition can drive both display and validation.** Fields carry structural rules (required, type) and presentation semantics (label, conditional visibility) in one object.
- **Async service contracts** enable seamless backend swap — mock today, real API tomorrow.

### 1.2 What This PoC Advances

The Dynamic Forms PoC separated *schema* (structural validity) from *form definition* (UI metadata). **This was the wrong split.** In practice, the people editing these artifacts — developers, business analysts, operations staff — need a single object that answers both "what does this data look like?" and "how should a human see it?" simultaneously.

This PoC **unifies canonical model + form definition + validation** into a single renderable object using JSON Forms (for UI specification) and Zod (for runtime validation), then extends the platform to manage the full suite of CNCF automation assets.

### 1.3 What Changed

| Dynamic Forms PoC | Metadata Management Platform PoC |
|---|---|
| Separate schema vs. form definition | **Unified model** — one object for structure, display, and validation |
| Form definitions only | **Four asset types** — models, workflows (sw.json), decisions (DMN), mappings |
| Client-only React SPA | **Node.js backend + React frontend** — full-stack platform |
| Mock service layer | **Express API + embedded management UI** |
| View/render only | **Author, edit, catalog, validate, inspect** — editing is a first-class concern |
| Single form preview | **Context-aware editors** per asset type (sw.json, JSON Forms, DMN) |

---

## 2. Problem Statement

Order processing automation requires coordinating multiple metadata artifacts:

1. **Canonical Models** — What does a "Tax Levy Order" look like? What fields, types, constraints, and how should a human render it?
2. **Workflow Definitions** — What steps process that order? (validate → enrich → route → notify) — expressed as CNCF `sw.json`
3. **Decision Tables** — What business rules apply? (if levy > $50k, require manual review) — expressed as DMN
4. **Transformation Mappings** — How does an inbound document (PDF, EDI, API) map onto the canonical model?
5. **Version Management** — All artifacts evolve. A workflow authored in 2025 must remain reproducible when audited in 2027.

Today these are scattered across code, config files, spreadsheets, and tribal knowledge. At scale (1000s of order types across jurisdictions), this is unmanageable.

**The Metadata Management Platform centralizes all automation assets as versionable, queryable, editable metadata — and provides the UX for humans to author, inspect, and operate them.**

---

## 3. Platform Role & Personas

This is not just an API server. It is an **extension layer on top of CNCF Serverless Workflow** that provides UX capabilities the workflow runtime does not. 

### 3.1 What the Platform Is

```
┌──────────────────────────────────────────────────────────────────────┐
│                    Metadata Management Platform                       │
│                                                                      │
│   An authoring and operational tool that sits alongside the CNCF     │
│   Serverless Workflow runtime. It owns the metadata lifecycle:       │
│                                                                      │
│   CREATE  → Author new models, workflows, decisions, mappings        │
│   CATALOG → Search, browse, tag, and organize 1000s of assets        │
│   EDIT    → Context-aware editors understand each asset's spec       │
│   VALIDATE→ Verify assets against their respective schemas           │
│   INSPECT → Render live data through forms, visualize workflow runs  │
│   SERVE   → Deliver assets to the workflow runtime via REST API      │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 Personas & Use Cases

#### Developer (Design-Time)

A developer building order processing automations uses the platform to:

| Use Case | Description |
|---|---|
| **Author workflows** | Create/edit `sw.json` files using a spec-aware editor that understands CNCF Serverless Workflow state types, function refs, event definitions, and jq expressions |
| **Design canonical models** | Define the unified model (structure + form + validation) using a JSON Forms editor with live preview and Zod rule generation |
| **Write decision rules** | Author DMN decision tables that the workflow references for business logic |
| **Define mappings** | Specify how inbound formats transform to canonical models |
| **Visualize workflows** | See a graphical representation of workflow states/transitions to verify logic before deployment |
| **Catalog assets** | Browse all assets, understand relationships (which workflows use which models, which decisions feed which states) |
| **Test with data** | Push sample data through a model's form to verify rendering and validation behaves correctly |

#### Operator (Runtime / Debug)

An operator monitoring production or debugging failures uses the same platform to:

| Use Case | Description |
|---|---|
| **Inspect order data** | Render an order's payload through its canonical model's form to see data the way a human expects, not as raw JSON |
| **Trace workflow execution** | Visualize which states a workflow instance traversed, what data was present at each step |
| **Debug exceptions** | When an order fails validation, see exactly which model fields failed and why |
| **View decision outcomes** | See which DMN rules fired for a given input, what the decision output was |
| **Audit asset versions** | Confirm which version of a model/workflow/decision was active when a specific order was processed |

#### Platform Engineer (Operations)

| Use Case | Description |
|---|---|
| **Same UI as dev** | The operational view IS the development view — no separate "admin console" |
| **Monitor asset health** | See which assets are active, deprecated, or draft |
| **Manage lifecycle** | Promote assets through draft → active → deprecated stages |

### 3.3 Key Design Principle

> **The platform engineers who build automations and the operators who monitor them should use the same tool.** The development-time editing experience IS the runtime inspection experience. A form that developers preview while authoring is the same form operators use to inspect live data.

---

## 3.4 Governance Assets — What We Store

The platform manages four types of **Governance Assets** — versionable, queryable metadata artifacts that together define the complete executable specification for order processing automation.

| Asset Type | What It Defines | Format / Standard | Example |
|---|---|---|---|
| **Canonical Model** | Data structure, validation rules, and form rendering for an order type | JSON Schema (draft-07) + JSON Forms UI Schema | X9.129 Legal Order Header (RT 20) |
| **Workflow Definition** | The process steps that transform an order from receipt to completion | CNCF Serverless Workflow (`sw.json`, spec v0.8) | `process-legal-order` |
| **Decision Table** | Business rules that determine routing, priority, and exception handling | DMN (Decision Model and Notation) in JSON representation | `legal-order-routing` |
| **Transformation Mapping** | How inbound document formats map to canonical model fields | Source→target field mapping with transform functions | `PDF_EXTRACT_TO_X9_129_LEGAL_ORDER_V1` |

**Why "Governance Assets":**
- They **govern** how every legal order and subpoena flows through the system
- They are the **authoritative source** for structure, process, rules, and data normalization
- They are **auditable** — version-tracked, with status lifecycle (draft → active → deprecated)
- They are **machine-executable** — the workflow runtime, validation engine, and UI all consume them directly

Each governance asset is stored as a **document** in the platform's document store, addressed by a unique ID and partitioned by document type. The platform provides a REST API to CRUD these assets and context-aware editors to author them.

---

## 4. The Four Asset Types

### 4.1 Unified Canonical Model

**The key design decision:** Combine structural schema, form rendering, and validation into **one object**. No more separate "form definition" and "canonical model." One artifact serves all three purposes:

| Concern | Technology | Role in the Unified Model |
|---|---|---|
| **Structure & Validation Schema** | JSON Schema (draft-07) | Defines field types, constraints, required fields. Used by ajv for server-side validation. |
| **Form Rendering** | JSON Forms (jsonforms.io) | UI schema + data schema = renderable form. Drives React-based form rendering with layout, labels, conditional visibility. |
| **Runtime Validation** | Zod (generated from JSON Schema) | TypeScript-native validation for client-side and workflow-step validation. Generated from the JSON Schema — not maintained separately. |

#### Unified Model Structure

The seed data for canonical models is grounded in the ANSI X9 standards — X9.129 (Legal Orders Exchange) and X9.144 (Production Subpoena Orders Exchange). See `docs/x9-record-types-plan.md` for the full record type inventory and rollout plan.

```json
{
  "modelId": "X9_129_LEGAL_ORDER_HEADER_V1",
  "name": "X9.129 Legal Order Header",
  "version": "1.0.0",
  "status": "active",
  "standard": "ANSI X9.129",
  "recordType": "20",
  "jurisdiction": "US",
  "documentType": "LEGAL_ORDER",
  "effectiveDate": "2025-01-01",
  "description": "Record Type 20 — Legal Order Header from ANSI X9.129. Captures core identifying and classifying information for levies, garnishments, seizures, and other asset-based legal orders.",

  "jsonSchema": {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "type": "object",
    "required": ["recordType", "orderTypeCode", "orderNumber", "issuingAgencyName", "issuingAgencyId", "issueDate", "effectiveDate", "jurisdictionCode"],
    "properties": {
      "recordType": {
        "type": "string",
        "const": "20",
        "description": "X9.129 Record Type identifier"
      },
      "orderTypeCode": {
        "type": "string",
        "enum": ["TAX_LEVY", "CHILD_SUPPORT", "CREDITOR_GARNISHMENT", "BANKRUPTCY", "ASSET_SEIZURE", "RESTRAINING_NOTICE", "COURT_ORDER"],
        "description": "Classification of the legal order per X9.129 order type codes"
      },
      "orderNumber": {
        "type": "string",
        "minLength": 1,
        "maxLength": 30,
        "description": "Unique order/case number assigned by the issuing agency"
      },
      "issuingAgencyName": {
        "type": "string",
        "minLength": 1,
        "maxLength": 100,
        "description": "Name of the court or government agency issuing the legal order"
      },
      "issuingAgencyId": {
        "type": "string",
        "pattern": "^[A-Z0-9]{4,20}$",
        "description": "Unique identifier for the issuing agency"
      },
      "issueDate": {
        "type": "string",
        "format": "date",
        "description": "Date the legal order was issued"
      },
      "effectiveDate": {
        "type": "string",
        "format": "date",
        "description": "Date the legal order takes effect"
      },
      "expirationDate": {
        "type": "string",
        "format": "date",
        "description": "Date the legal order expires, if applicable"
      },
      "jurisdictionCode": {
        "type": "string",
        "pattern": "^(US|[A-Z]{2})$",
        "description": "Jurisdiction — 'US' for federal or two-letter state code"
      },
      "priorityCode": {
        "type": "string",
        "enum": ["STANDARD", "PRIORITY", "EXPEDITED"],
        "default": "STANDARD",
        "description": "Processing priority level"
      },
      "orderAmount": {
        "type": "number",
        "minimum": 0,
        "description": "Total monetary amount in USD"
      },
      "currencyCode": {
        "type": "string",
        "pattern": "^[A-Z]{3}$",
        "default": "USD",
        "description": "ISO 4217 currency code"
      },
      "orderStatus": {
        "type": "string",
        "enum": ["RECEIVED", "ACKNOWLEDGED", "IN_PROCESS", "COMPLETED", "REJECTED", "RELEASED"],
        "default": "RECEIVED",
        "description": "Current processing status"
      },
      "narrativeText": {
        "type": "string",
        "maxLength": 500,
        "description": "Free-text narrative or special instructions"
      }
    }
  },

  "uiSchema": {
    "type": "VerticalLayout",
    "elements": [
      {
        "type": "Group",
        "label": "Order Identification",
        "elements": [
          { "type": "Control", "scope": "#/properties/recordType", "label": "Record Type", "options": { "readonly": true } },
          { "type": "Control", "scope": "#/properties/orderTypeCode", "label": "Order Type" },
          { "type": "Control", "scope": "#/properties/orderNumber", "label": "Order / Case Number" },
          { "type": "Control", "scope": "#/properties/orderStatus", "label": "Processing Status" },
          { "type": "Control", "scope": "#/properties/priorityCode", "label": "Priority" }
        ]
      },
      {
        "type": "Group",
        "label": "Issuing Authority",
        "elements": [
          { "type": "Control", "scope": "#/properties/issuingAgencyName", "label": "Agency / Court Name" },
          { "type": "Control", "scope": "#/properties/issuingAgencyId", "label": "Agency Identifier" },
          { "type": "Control", "scope": "#/properties/jurisdictionCode", "label": "Jurisdiction" }
        ]
      },
      {
        "type": "Group",
        "label": "Dates & Amounts",
        "elements": [
          { "type": "Control", "scope": "#/properties/issueDate", "label": "Issue Date" },
          { "type": "Control", "scope": "#/properties/effectiveDate", "label": "Effective Date" },
          { "type": "Control", "scope": "#/properties/expirationDate", "label": "Expiration Date" },
          { "type": "Control", "scope": "#/properties/orderAmount", "label": "Order Amount ($)" },
          { "type": "Control", "scope": "#/properties/currencyCode", "label": "Currency" }
        ]
      },
      {
        "type": "Group",
        "label": "Additional Information",
        "elements": [
          { "type": "Control", "scope": "#/properties/narrativeText", "label": "Narrative / Instructions", "options": { "multi": true } }
        ]
      }
    ]
  },

  "metadata": {
    "standard": "ANSI X9.129",
    "standardVersion": "2024",
    "recordType": "20",
    "owner": "legal-orders-team",
    "tags": ["x9.129", "legal-order", "header", "record-type-20"],
    "relatedWorkflows": ["process-legal-order"],
    "relatedDecisions": ["order-routing", "order-priority"],
    "createdAt": "2025-01-15T00:00:00Z",
    "updatedAt": "2025-06-20T00:00:00Z"
  }
}
```

**How the three concerns compose:**

```
                    Unified Canonical Model
                    ┌─────────────────────┐
                    │                     │
          ┌────────┤    jsonSchema        ├────────┐
          │        │  (JSON Schema)       │        │
          │        └──────────┬──────────┘        │
          │                   │                    │
          ▼                   ▼                    ▼
   ┌──────────────┐  ┌───────────────┐   ┌──────────────┐
   │ ajv           │  │ json-schema-  │   │ JSON Forms   │
   │ (server-side  │  │ to-zod        │   │ React        │
   │  validation)  │  │ (generates    │   │ (renders     │
   │               │  │  Zod schema)  │   │  forms from  │
   └──────────────┘  └───────────────┘   │  jsonSchema + │
                                          │  uiSchema)   │
                                          └──────────────┘
```

**Why this works:** JSON Forms already consumes a JSON Schema + a UI Schema to produce a renderable form. By standardizing on JSON Schema as the single source of structural truth, we get:
- Server-side validation via ajv (directly from `jsonSchema`)
- Client-side validation via Zod (generated from `jsonSchema`)
- Form rendering via JSON Forms (from `jsonSchema` + `uiSchema`)
- No duplication, no drift between validation and display

**Zod consumer pattern (code generation, not runtime eval):** The `/api/models/:id/zod` endpoint returns a Zod schema as a **string**. The intended consumer pattern is developer-time code generation: a developer fetches the string, reviews it, and pastes it into their TypeScript application code — the Zod schema becomes a static type at compile time. Do **not** evaluate this string at runtime via `eval()` or `new Function()` — this is an injection risk and defeats TypeScript typing. The Future Path section describes the production pattern: Zod schemas published as versioned npm packages.

### 4.2 Workflow Definition (CNCF `sw.json`)

Stored as CNCF Serverless Workflow spec-compliant JSON with server-managed metadata wrapper:

```json
{
  "workflowId": "process-legal-order",
  "name": "Process Legal Order",
  "version": "1.0.0",
  "status": "active",
  "category": "processing",
  "tags": ["x9.129", "legal-order", "automated"],
  "createdAt": "2025-06-15T00:00:00Z",
  "updatedAt": "2025-06-15T00:00:00Z",

  "definition": {
    "id": "process-legal-order",
    "version": "1.0.0",
    "specVersion": "0.8",
    "name": "Process Legal Order",
    "description": "Retrieves, routes, and processes a legal order. Triggered by an ingest event carrying a controlRecordId. Uses the claim check pattern — no order payload travels through the workflow.",
    "start": "HydrateOrder",
    "states": [
      {
        "name": "HydrateOrder",
        "type": "operation",
        "comment": "Fetch canonical order data from the control record store using the claim check ID. This is NOT the metadata server — it is a separate data store.",
        "actions": [
          {
            "functionRef": {
              "refName": "fetchControlRecord",
              "arguments": {
                "controlRecordId": "${ .controlRecordId }"
              }
            },
            "actionDataFilter": {
              "results": "${ .orderData }"
            }
          }
        ],
        "transition": "EnrichWithPartySearch",
        "onErrors": [
          { "errorRef": "HydrationError", "transition": "RouteToException" }
        ]
      },
      {
        "name": "EnrichWithPartySearch",
        "type": "operation",
        "comment": "Invokes the party-search sub-workflow to enrich the control record with matched party data from the bank's customer database.",
        "actions": [
          {
            "subFlowRef": {
              "workflowId": "party-search",
              "version": "1.0.0"
            }
          }
        ],
        "transition": "EvaluateRouting"
      },
      {
        "name": "EvaluateRouting",
        "type": "operation",
        "actions": [
          {
            "functionRef": {
              "refName": "evaluateRoutingDecision",
              "arguments": {
                "decisionId": "legal-order-routing",
                "data": "${ .orderData }"
              }
            }
          }
        ],
        "transition": "RouteOrder"
      },
      {
        "name": "RouteOrder",
        "type": "switch",
        "dataConditions": [
          {
            "name": "RequiresManualReview",
            "condition": "${ .routingResult.requiresReview == true }",
            "transition": "ManualReview"
          }
        ],
        "defaultCondition": { "transition": "AutoApprove" }
      },
      {
        "name": "ManualReview",
        "type": "operation",
        "actions": [{ "functionRef": { "refName": "createExceptionCase" } }],
        "end": true
      },
      {
        "name": "AutoApprove",
        "type": "operation",
        "actions": [{ "functionRef": { "refName": "approveOrder" } }],
        "transition": "NotifyParties"
      },
      {
        "name": "NotifyParties",
        "type": "operation",
        "comment": "Invokes the notify-parties sub-workflow to send notifications to affected parties after auto-approval.",
        "actions": [
          {
            "subFlowRef": {
              "workflowId": "notify-parties",
              "version": "1.0.0"
            }
          }
        ],
        "end": true
      },
      {
        "name": "RouteToException",
        "type": "operation",
        "actions": [{ "functionRef": { "refName": "createExceptionCase" } }],
        "end": true
      }
    ],
    "functions": [
      {
        "name": "fetchControlRecord",
        "comment": "Fetches canonical order data from the control record store (NOT the metadata server). Out of scope for this PoC — stubbed.",
        "operation": "http://control-record-store/api/records/{controlRecordId}",
        "type": "rest"
      },
      {
        "name": "evaluateRoutingDecision",
        "operation": "http://metadata-server/api/decisions/{decisionId}/evaluate",
        "type": "rest"
      },
      {
        "name": "createExceptionCase",
        "operation": "http://case-service/api/cases",
        "type": "rest"
      },
      {
        "name": "approveOrder",
        "operation": "http://order-service/api/orders/{orderId}/approve",
        "type": "rest"
      }
    ],
    "errors": [
      { "name": "HydrationError", "code": "HYDRATION_FAILED" }
    ]
  },

  "metadata": {
    "relatedModels": ["X9_129_LEGAL_ORDER_HEADER_V1"],
    "relatedDecisions": ["legal-order-routing"],
    "subWorkflows": ["party-search", "notify-parties"],
    "parentWorkflows": []
  }
}
```

#### Workflow Categorization

Workflows are categorized by their **automation purpose** — what role they play in the processing pipeline. This is the primary browse axis for the workflow catalog (analogous to how models are grouped by standard).

| Category | Description | Browse slug | Example workflows |
|---|---|---|---|
| `ingestion` | Receives external input, transforms, validates, stores a control record | `ingestion` | `ingest-legal-order`, `ingest-subpoena` |
| `processing` | Orchestrates business logic for a control record after ingestion | `processing` | `process-legal-order`, `process-subpoena` |
| `enrichment` | Fetches external data to add to a control record (party search, credit checks) | `enrichment` | `party-search` |
| `notification` | Sends alerts and updates to downstream systems or affected parties | `notification` | `notify-parties` |

**Category is explicit, not derived.** Unlike models where `standard` is intrinsic to the data, a workflow's category reflects its architectural role — the same `sw.json` structure could serve ingestion or processing. The `category` field is set by the author on the wrapper object (not inside `definition`).

**Domain comes from `relatedModels`.** A workflow's domain (X9.129 vs X9.144) is derived from its `metadata.relatedModels` entries, which link to models that declare their standard. Cross-domain workflows (like `party-search`) have an empty `relatedModels` array and appear in all domain views.

#### Sub-Workflow Composition

CNCF Serverless Workflow v0.8 natively supports sub-workflow invocation via `subFlowRef` in operation state actions:

```json
{
  "name": "EnrichWithPartySearch",
  "type": "operation",
  "actions": [
    {
      "subFlowRef": {
        "workflowId": "party-search",
        "version": "1.0.0"
      }
    }
  ],
  "transition": "NextState"
}
```

**Design decisions:**

1. **`metadata.subWorkflows[]`** — Lists the `workflowId` values of all workflows invoked via `subFlowRef`. Extracted automatically from the definition or set explicitly. Used by the catalog to show the composition tree.
2. **`metadata.parentWorkflows[]`** — The inverse: which workflows invoke this one as a sub-workflow. Maintained on the child side for bidirectional navigation.
3. **Validation** — Workflow validation uses the official CNCF SDK (`@serverlessworkflow/sdk-typescript@0.8.4`) for schema-level validation, supplemented by custom referential integrity checks. The custom layer verifies that every `subFlowRef.workflowId` in the definition matches a workflow that exists in the catalog. Missing sub-workflows produce an error.
4. **Diagram rendering** — States with `subFlowRef` actions render as a distinct node style (nested workflow icon) in the state diagram, with a clickable link to the sub-workflow editor.

**`serverDefinitions` note:** Function `operation` URLs use the symbolic hostname `metadata-server`. In CNCF SW 0.8, the base URL is resolved at runtime from a `serverDefinitions` block in the `sw.json`, keyed to an environment variable — not hardcoded. This keeps the workflow definition environment-agnostic.

```json
"serverDefinitions": {
  "metadata-server": { "href": "${METADATA_SERVER_URL}" }
}
```

_PoC simplification: hardcode `http://localhost:3001` in devcontainer. Document the `serverDefinitions` pattern for production._

### 4.3 Decision Definition (DMN)

DMN (Decision Model and Notation) is the standard for expressing business rules as decision tables. The workflow references decisions by ID; the metadata server evaluates them.

**Why DMN rather than jq `dataConditions`:** A `switch` state in CNCF SW can express routing logic directly as jq expressions — no external service required. DMN is chosen here because routing rules are authored and owned by **compliance and operations teams**, not developers. A compliance analyst can read, test, and change a decision table in the Decision Editor without opening a workflow JSON file or triggering a workflow redeployment cycle. The rules are also auditable as standalone governance assets: an auditor inspecting a processed order can identify the exact decision table and rule version that governed the routing. For the full rationale see Section 15.4.

```json
{
  "decisionId": "legal-order-routing",
  "name": "Legal Order Routing Rules",
  "version": "1.0.0",
  "status": "active",
  "tags": ["x9.129", "legal-order", "routing"],
  "createdAt": "2025-06-15T00:00:00Z",
  "updatedAt": "2025-06-15T00:00:00Z",

  "definition": {
    "hitPolicy": "FIRST",
    "inputs": [
      { "id": "orderAmount", "label": "Order Amount", "type": "number" },
      { "id": "jurisdictionCode", "label": "Jurisdiction", "type": "string" },
      { "id": "orderTypeCode", "label": "Order Type", "type": "string" }
    ],
    "outputs": [
      { "id": "requiresReview", "label": "Requires Manual Review", "type": "boolean" },
      { "id": "priority", "label": "Priority", "type": "string" },
      { "id": "assignee", "label": "Assign To", "type": "string" }
    ],
    "rules": [
      {
        "description": "High-value orders require manual review",
        "conditions": { "orderAmount": "> 50000" },
        "outputs": { "requiresReview": true, "priority": "high", "assignee": "senior-reviewer" }
      },
      {
        "description": "Multi-state orders require compliance review",
        "conditions": { "jurisdictionCode": "MULTI" },
        "outputs": { "requiresReview": true, "priority": "medium", "assignee": "compliance-team" }
      },
      {
        "description": "Government entities fast-tracked",
        "conditions": { "orderTypeCode": "COURT_ORDER" },
        "outputs": { "requiresReview": false, "priority": "low", "assignee": "auto" }
      },
      {
        "description": "Default: auto-approve",
        "conditions": {},
        "outputs": { "requiresReview": false, "priority": "normal", "assignee": "auto" }
      }
    ]
  },

  "metadata": {
    "relatedModels": ["X9_129_LEGAL_ORDER_HEADER_V1"],
    "relatedWorkflows": ["process-legal-order"]
  }
}
```

**How DMN fits the workflow:** The `sw.json` workflow calls `evaluateRoutingDecision` which hits the metadata server's `/api/decisions/:id/evaluate` endpoint. The server loads the decision, evaluates the rules against the input data, and returns the output. The workflow uses the output to branch (`switch` state).

### 4.4 Transformation Mapping

Maps inbound document formats to canonical models:

```json
{
  "mappingId": "PDF_EXTRACT_TO_X9_129_LEGAL_ORDER_V1",
  "name": "PDF Extract → X9.129 Legal Order Header",
  "version": "1.0.0",
  "status": "active",
  "sourceFormat": "PDF_EXTRACT",
  "targetModelId": "X9_129_LEGAL_ORDER_HEADER_V1",
  "tags": ["x9.129", "legal-order", "pdf"],
  "createdAt": "2025-06-15T00:00:00Z",
  "updatedAt": "2025-06-15T00:00:00Z",

  "fields": [
    { "source": "extracted.agencyName", "target": "issuingAgencyName", "transform": null },
    { "source": "extracted.caseId", "target": "orderNumber", "transform": null },
    { "source": "extracted.amount", "target": "orderAmount", "transform": "parseNumber" },
    { "source": "extracted.date", "target": "effectiveDate", "transform": "parseDate" },
    { "source": "extracted.state", "target": "jurisdictionCode", "transform": null }
  ],

  "transforms": {
    "parseNumber": {
      "type": "expression",
      "expr": "Number(value.replace(/[^0-9.]/g, ''))"
    },
    "parseDate": {
      "type": "expression",
      "expr": "new Date(value).toISOString().split('T')[0]"
    }
  },

  "metadata": {
    "relatedModels": ["X9_129_LEGAL_ORDER_HEADER_V1"]
  }
}
```

---

## 5. Features

Each feature represents a vertical slice of the platform. Features are ordered by implementation priority.

### Feature 1: Asset Catalog

**The foundation.** Before any editing can happen, users need to find things.

**Problem:** With 1000s of models, workflows, decisions, and mappings, browsing a flat list is unusable.

**Capability:**

| Capability | Description |
|---|---|
| **Unified asset index** | Single view that shows all asset types, sortable by type, name, status, jurisdiction, tags, last modified |
| **Faceted search** | Filter by asset type, status (`active`/`draft`/`deprecated`), jurisdiction, document type, tags |
| **Full-text search** | Search across asset names, descriptions, field names, tags |
| **Relationship graph** | For a selected asset, show related assets (e.g., model → workflows that use it → decisions those workflows invoke) |
| **Pagination** | Cursor-based pagination. Default page size 50. Required for 1000s of assets |
| **Bulk operations** | Multi-select → bulk tag, bulk status change |

**API support:**

```
GET /api/assets?type=model&status=active&jurisdiction=NY&q=tax&page=1&pageSize=50
GET /api/assets/:assetType/:assetId/related
```

**Scalability decisions:**
- In-memory index with file-backed persistence (PoC). Designed for swap to database.
- Assets stored as individual JSON files: `/data/{assetType}/{assetId}.json`
- Index file (`/data/index.json`) maintains a lightweight catalog (id, type, name, tags, status, timestamps) to avoid loading full assets for listing/search.
- All list endpoints return paginated responses: `{ items: [], total: number, page: number, pageSize: number }`

### Feature 2: Canonical Model Editor (JSON Forms + Zod)

**A visual editor optimized for creating and editing unified canonical models.**

**Capability:**

| Capability | Description |
|---|---|
| **Schema editor** | Visual form for defining JSON Schema properties — add/remove/reorder fields, set types, constraints, descriptions |
| **UI Schema editor** | Drag fields into layout groups (VerticalLayout, HorizontalLayout, Group). Set labels, visibility rules |
| **Live form preview** | Right panel renders the form in real-time using JSON Forms React. Auto-generates sample data per field type |
| **Zod preview** | Shows the generated Zod schema (read-only) derived from the JSON Schema. Developer can copy it for use in code |
| **Validation testing** | Paste a JSON payload → validate against the model → see structured errors inline on the form |
| **JSON source view** | Toggle to raw JSON editing for power users. Changes sync bidirectionally with the visual editor |

**Layout:**

```
┌───────────────────────────────────────────────────────────────────┐
│  Model: X9_129_LEGAL_ORDER_HEADER_V1 (v1.0.0)  [active]  [Save] │
├──────────────────────────┬────────────────────────────────────────┤
│  Schema Editor           │  Live Preview                          │
│                          │                                        │
│  ┌────────────────────┐  │  ┌──────────────────────────────────┐  │
│  │ + Add Field         │  │  │ ┌─ Order Identification ───────┐ │  │
│  │                     │  │  │ │ Record Type: [20           ] │ │  │
│  │ ▼ orderTypeCode     │  │  │ │ Order Type:  [TAX_LEVY  ▼ ] │ │  │
│  │   type: string      │  │  │ │ Order Number:[2025-NY-001 ] │ │  │
│  │   enum: TAX_LEVY... │  │  │ └─────────────────────────────┘ │  │
│  │                     │  │  │ ┌─ Issuing Authority ─────────┐ │  │
│  │ ▼ orderNumber       │  │  │ │ Agency: [NY Dept of Tax    ] │ │  │
│  │   type: string      │  │  │ │ Agency ID: [NYDTF001      ] │ │  │
│  │   maxLength: 30     │  │  │ └─────────────────────────────┘ │  │
│  │   required: ✓       │  │  │ ┌─ Dates & Amounts ───────────┐ │  │
│  │                     │  │  │ │ Issue Date:  [2025-06-01   ] │ │  │
│  │ ▼ orderAmount       │  │  │ │ Amount ($):  [15000        ] │ │  │
│  │   type: number      │  │  │ └─────────────────────────────┘ │  │
│  │   min: 0            │  │  │ Status: [RECEIVED ▼]             │  │
│  │                     │  │  │                                  │  │
│  │ ...                 │  │  │        [Submit]                   │  │
│  └────────────────────┘  │  └──────────────────────────────────┘  │
│                          │                                        │
│  [UI Layout] [JSON]      │  Zod Output (generated):               │
│                          │  ┌──────────────────────────────────┐  │
│                          │  │ z.object({                       │  │
│                          │  │   orderTypeCode: z.enum([...])   │  │
│                          │  │   orderNumber: z.string().max(30)│  │
│                          │  │   orderAmount: z.number().min(0) │  │
│                          │  │   ...                            │  │
│                          │  │ })                                │  │
│                          │  └──────────────────────────────────┘  │
└──────────────────────────┴────────────────────────────────────────┘
```

### Feature 3: Workflow Editor (CNCF sw.json)

**A spec-aware editor for CNCF Serverless Workflow definitions.**

**Capability:**

| Capability | Description |
|---|---|
| **Structured JSON editor** | Syntax-highlighted JSON editor with awareness of sw.json schema — autocomplete for state types, function types, jq expressions |
| **State diagram visualization** | Graphical DAG view showing states as nodes, transitions as edges. Updates live as JSON changes |
| **Function reference validation** | When a state references a function, validate it exists in the `functions[]` array |
| **Model/decision linking** | When a function URL references the metadata server (e.g., `/api/models/{modelId}/validate`), resolve and show the linked model/decision |
| **Error path highlighting** | Visualize error transitions distinctly from happy-path transitions |
| **Spec validation** | Validate against CNCF Serverless Workflow spec schema on save |

**Editor options considered:**

| Option | Pros | Cons | Decision |
|---|---|---|---|
| **Monaco Editor + custom sw.json language server** | Full IDE experience, IntelliSense | Heavy to build | PoC: use Monaco with JSON Schema validation only. Future: add language server |
| **CNCF Serverless Workflow VS Code extension** | Already built by CNCF community | VS Code only, not embeddable | Reference for spec compliance, not embeddable in our platform |
| **Custom React tree editor** | Lightweight, tailored to our UX | No ecosystem support | Fallback if Monaco is too heavy |

**PoC approach:** Monaco Editor with the CNCF sw.json JSON Schema loaded for validation and autocomplete, plus a read-only state diagram rendered alongside.

**Layout:**

```
┌───────────────────────────────────────────────────────────────────┐
│  Workflow: process-legal-order (v1.0.0)  [active]  [Save] [Cancel]  │
├──────────────────────────┬────────────────────────────────────────┤
│  sw.json Editor (Monaco) │  State Diagram                         │
│                          │                                        │
│  {                       │   ┌──────────┐                         │
│    "id": "process-...",  │   │ Validate │                         │
│    "specVersion": "0.8", │   │  Order   │                         │
│    "start": "Validate..  │   └────┬─────┘                         │
│    "states": [           │        │ ──── error ──▶ [Exception]    │
│      {                   │        ▼                               │
│        "name": "Valid.   │   ┌──────────┐                         │
│        "type": "opera.   │   │  Check   │                         │
│        ...               │   │ Routing  │                         │
│      },                  │   └────┬─────┘                         │
│      ...                 │        ▼                               │
│    ],                    │   ┌──────────┐                         │
│    "functions": [...]    │   │  Route   │──▶ [Manual Review]      │
│  }                       │   │  Order   │──▶ [Auto Approve]      │
│                          │   └──────────┘                         │
│  ⚠ 0 errors  ✓ valid    │                                        │
└──────────────────────────┴────────────────────────────────────────┘
```

### Feature 4: Decision Editor (DMN)

**A visual decision table editor for authoring business rules.**

**Capability:**

| Capability | Description |
|---|---|
| **Decision table grid** | Spreadsheet-like editor: rows = rules, columns = inputs + outputs. Edit cells inline |
| **Hit policy selector** | Choose FIRST, UNIQUE, COLLECT, etc. — with explanation tooltips |
| **Input/output schema awareness** | When linked to a canonical model, auto-suggest input fields from the model's JSON Schema |
| **Rule testing** | Paste input data → see which rule fires → see output. Inline in the editor |
| **Model-linking** | List of canonical models whose fields can be used as inputs |

**Layout:**

```
┌──────────────────────────────────────────────────────────────────────┐
│  Decision: legal-order-routing (v1.0.0)  [active]    [Save] [Cancel] │
│  Hit Policy: [FIRST ▼]     Linked Model: [X9_129_LEGAL_ORDER_HEADER_V1] │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────┬──────────────┬──────────────┬───────────┬──────────┬──────┐ │
│  │  #  │ Order Amount │ Jurisdiction │ Order     │ Requires │ Pri- │ │
│  │     │              │              │ Type      │ Review?  │ ority│ │
│  ├─────┼──────────────┼──────────────┼───────────┼──────────┼──────┤ │
│  │  1  │ > 50000      │ -            │ -         │ true     │ high │ │
│  │  2  │ -            │ MULTI        │ -         │ true     │ med  │ │
│  │  3  │ -            │ -            │ COURT_ORD │ false    │ low  │ │
│  │  4  │ (default)    │ (default)    │ (default) │ false    │ norm │ │
│  ├─────┼──────────────┼──────────────┼───────────┼──────────┼──────┤ │
│  │  +  │ [Add Rule]                                                │ │
│  └─────┴───────────────────────────────────────────────────────────┘ │
│                                                                      │
│  Test: {"levyAmount": 75000, "jurisdiction": "NY"}                   │
│  Result: Rule #1 → {"requiresReview": true, "priority": "high"}     │
└──────────────────────────────────────────────────────────────────────┘
```

### Feature 5: Data Inspector (Operational View)

**Render live data through the platform's forms for inspection and debugging.**

**Capability:**

| Capability | Description |
|---|---|
| **Order rendering** | Given an order payload + its `modelId`, load the unified model and render the data through JSON Forms in read-only mode |
| **Validation overlay** | Highlight which fields pass/fail validation directly on the form |
| **Workflow trace** | Given a workflow execution ID, show the state diagram with the executed path highlighted, data snapshot at each step |
| **Decision trace** | Show which DMN rule fired, what inputs were evaluated, what output was produced |
| **Side-by-side compare** | Compare two versions of an order (before/after operator edit) rendered through the same form |

**This is the same UI as the editors but in read-only/inspection mode.** The developer who authored the form and the operator who inspects live data see the same rendering.

### Feature 6: Mapping Editor

**Visual mapping definition between source formats and canonical models.**

| Capability | Description |
|---|---|
| **Source/target columns** | Left: source format fields. Right: canonical model fields (auto-loaded from model). Draw lines between them |
| **Transform functions** | Attach transform logic (regex, expression) to each mapping line |
| **Test with sample** | Paste source data → see transformed output → validate against canonical model |

---

## 6. Architecture

### 6.1 Technology Stack

| Layer | Technology | Rationale |
|---|---|---|
| **Runtime** | Node.js 20+ | JS/JSON native, team skill match |
| **API Framework** | Express.js | Well-understood, sufficient for PoC |
| **Frontend** | React 18 + Vite | Consistent with team skills, required for JSON Forms and Monaco |
| **Component Library** | MUI (`@mui/material`, `@mui/icons-material`, `@mui/lab`) | MUI-first: all layout, navigation, inputs, data display, and feedback. JSON Forms material renderers inherit MUI theme. Only Monaco is non-MUI. |
| **Form Rendering** | `@jsonforms/react` + `@jsonforms/material-renderers` | Standard for rendering forms from JSON Schema + UI Schema. Uses MUI internally. |
| **Schema Validation** | ajv (server) + Zod (generated, client) | ajv for JSON Schema validation; Zod for typed runtime validation |
| **Workflow SDK** | `@serverlessworkflow/sdk-typescript` v0.8.4 | Official CNCF SDK — schema validation, bundled v0.8 JSON Schema for Monaco, typed definitions |
| **Code Editor** | Monaco Editor (`@monaco-editor/react`) | Same engine as VS Code; supports JSON Schema validation |
| **Workflow Visualization** | `@xyflow/react` (React Flow) | Already proven in pipeline demo; ideal for state diagrams |
| **Data Store** | Document repository with swappable backends | MemoryRepository (dev) → CosmosRepository (prod) |
| **Testing** | Vitest + supertest | Consistent with existing tooling |

### 6.2 System Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                     Metadata Management Platform                        │
│                                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                        React Frontend (Vite)                      │  │
│  │                                                                   │  │
│  │  ┌────────────┐ ┌──────────────┐ ┌────────────┐ ┌─────────────┐ │  │
│  │  │ Asset       │ │ Model Editor │ │ Workflow   │ │ Decision    │ │  │
│  │  │ Catalog     │ │ (JSON Forms) │ │ Editor     │ │ Editor      │ │  │
│  │  │             │ │              │ │ (Monaco +  │ │ (DMN Table) │ │  │
│  │  │ Search,     │ │ Schema +     │ │ React Flow)│ │             │ │  │
│  │  │ Browse,     │ │ UI Schema +  │ │            │ │ + Mapping   │ │  │
│  │  │ Filter      │ │ Live Preview │ │ + State    │ │   Editor    │ │  │
│  │  │             │ │ + Zod Output │ │   Diagram  │ │             │ │  │
│  │  └──────┬──────┘ └──────┬───────┘ └─────┬──────┘ └──────┬──────┘ │  │
│  │         └───────────────┴───────────────┴───────────────┘        │  │
│  │                              │ REST API calls                     │  │
│  └──────────────────────────────┼────────────────────────────────────┘  │
│                                 │                                       │
│  ┌──────────────────────────────▼────────────────────────────────────┐  │
│  │                        Express API Server                          │  │
│  │                                                                    │  │
│  │  ┌──────────┐ ┌────────────┐ ┌────────────┐ ┌──────────────────┐ │  │
│  │  │ /api/    │ │ /api/      │ │ /api/      │ │ /api/            │ │  │
│  │  │ models   │ │ workflows  │ │ decisions  │ │ mappings         │ │  │
│  │  └────┬─────┘ └─────┬──────┘ └─────┬──────┘ └───────┬──────────┘ │  │
│  │       └──────────────┴──────────────┴────────────────┘            │  │
│  │                              │                                     │  │
│  │  ┌───────────────────────────▼───────────────────────────────┐    │  │
│  │  │                    Service Layer                            │    │  │
│  │  │  modelService │ workflowService │ decisionService │ ...    │    │  │
│  │  └───────────────────────────┬───────────────────────────────┘    │  │
│  │                              │                                     │  │
│  │  ┌───────────────────────────▼───────────────────────────────┐    │  │
│  │  │            Asset Repository (document store abstraction)    │    │  │
│  │  │                                                            │    │  │
│  │  │  ┌─────────────────────────────────────────────────────┐   │    │  │
│  │  │  │  MemoryRepository (development)                      │   │    │  │
│  │  │  │  In-memory Map + JSON file persistence               │   │    │  │
│  │  │  │  Loads /data/seed/*.json on startup                  │   │    │  │
│  │  │  │  Mimics Cosmos DB document semantics                 │   │    │  │
│  │  │  └─────────────────────────────────────────────────────┘   │    │  │
│  │  │  ┌─────────────────────────────────────────────────────┐   │    │  │
│  │  │  │  CosmosRepository (production)                       │   │    │  │
│  │  │  │  @azure/cosmos SDK                                   │   │    │  │
│  │  │  │  Database: anax-metadata                             │   │    │  │
│  │  │  │  Containers: models, workflows, decisions, mappings  │   │    │  │
│  │  │  └─────────────────────────────────────────────────────┘   │    │  │
│  │  └────────────────────────────────────────────────────────────┘    │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │               Validation Engine                                    │  │
│  │  ajv (JSON Schema) + DMN evaluator + sw.json structural checks    │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.3 How the Platform Fits the CNCF Ecosystem

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           CNCF Ecosystem                                  │
│                                                                          │
│  ┌──────────────────┐                                                    │
│  │ Inbound Document  │                                                    │
│  │ (PDF/EDI/API)     │                                                    │
│  └────────┬─────────┘                                                    │
│           ▼                                                              │
│  ┌──────────────────┐      ┌──────────────────────────────────────────┐  │
│  │ CNCF Serverless   │      │ Metadata Management Platform             │  │
│  │ Workflow Runtime   │      │ (THIS POC)                               │  │
│  │                    │      │                                          │  │
│  │ Executes sw.json   │─────▶│ GET  /api/workflows/:id/definition      │  │
│  │                    │      │ POST /api/models/:id/validate            │  │
│  │                    │─────▶│ POST /api/decisions/:id/evaluate         │  │
│  │                    │      │ GET  /api/mappings/resolve               │  │
│  │                    │      │                                          │  │
│  └──────────────────┘      │ + React UX for authoring & inspection    │  │
│           │                 └──────────────────────────────────────────┘  │
│           ▼                                                              │
│  ┌──────────────────┐                                                    │
│  │ Downstream         │                                                    │
│  │ (Case Mgmt, etc.)  │                                                    │
│  └──────────────────┘                                                    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 7. API Contract

All list endpoints support pagination: `?page=1&pageSize=50`. Responses follow the shape:

```json
{
  "items": [],
  "total": 1234,
  "page": 1,
  "pageSize": 50
}
```

### 7.1 Assets API (Cross-Cutting)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/assets` | Unified search/browse across all asset types. Supports `?type=&status=&jurisdiction=&tags=&q=&page=&pageSize=` |
| `GET` | `/api/assets/:assetType/:assetId/related` | Get related assets (graph traversal) |

### 7.2 Models API

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/models` | List all models (paginated, filterable) |
| `GET` | `/api/models/:modelId` | Get full unified model (jsonSchema + uiSchema + metadata) |
| `POST` | `/api/models` | Create a new model |
| `PUT` | `/api/models/:modelId` | Update a model |
| `DELETE` | `/api/models/:modelId` | Delete (blocked if referenced by active workflows/decisions) |
| `POST` | `/api/models/:modelId/validate` | Validate a data payload against the model's JSON Schema |
| `GET` | `/api/models/:modelId/zod` | Get the generated Zod schema as a string |
| `GET` | `/api/models/resolve` | Resolve model by `?jurisdiction=&documentType=&effectiveDate=` |

**`/api/models/resolve` — Resolution Algorithm:**

When multiple model versions exist, the server selects the correct one deterministically:

1. **Filter** `status == "active"`
2. **Filter** `model.effectiveDate <= query.effectiveDate`
3. **Exclude** models where `model.expirationDate` is set **and** `model.expirationDate < query.effectiveDate`
4. **Match** `model.jurisdiction == query.jurisdiction`; fall back to `jurisdiction == "US"` for a federal match if no exact match
5. **Match** `model.documentType == query.documentType` if `documentType` is provided in the query
6. **Select** the candidate with the **highest version** among those remaining (semver descending order — first wins)
7. **No match** → `404 { "error": "NO_MODEL_FOUND", "query": { ... } }`

This algorithm ensures that `/resolve?effectiveDate=2025-06-01` returns the model that **was active on that date**, not the current latest version. Audit trail integrity depends on it: "which model governed this order?" must be answerable after the fact.

### 7.3 Workflows API

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/workflows` | List all workflows (paginated, filterable) |
| `GET` | `/api/workflows/:workflowId` | Get workflow (wrapper + definition) |
| `GET` | `/api/workflows/:workflowId/definition` | Get raw `sw.json` only (for workflow runtime consumption) |
| `POST` | `/api/workflows` | Create a workflow |
| `PUT` | `/api/workflows/:workflowId` | Update a workflow |
| `DELETE` | `/api/workflows/:workflowId` | Delete a workflow |
| `POST` | `/api/workflows/:workflowId/validate` | Validate sw.json against CNCF spec schema |

### 7.4 Decisions API

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/decisions` | List all decision tables (paginated, filterable) |
| `GET` | `/api/decisions/:decisionId` | Get a decision definition |
| `POST` | `/api/decisions` | Create a decision |
| `PUT` | `/api/decisions/:decisionId` | Update a decision |
| `DELETE` | `/api/decisions/:decisionId` | Delete a decision (blocked if referenced by active workflows) |
| `POST` | `/api/decisions/:decisionId/evaluate` | Evaluate the decision table against an input payload. Returns matching rule + outputs |

### 7.5 Mappings API

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/mappings` | List all mappings (paginated, filterable) |
| `GET` | `/api/mappings/:mappingId` | Get a mapping definition |
| `POST` | `/api/mappings` | Create a mapping |
| `PUT` | `/api/mappings/:mappingId` | Update a mapping |
| `DELETE` | `/api/mappings/:mappingId` | Delete a mapping |
| `GET` | `/api/mappings/resolve` | Resolve by `?sourceFormat=&targetModelId=` |
| `POST` | `/api/mappings/:mappingId/transform` | Apply mapping to a source payload → return transformed data |

---

## 8. Seed Data

Seed canonical models are derived from the ANSI X9 standards (X9.129 and X9.144). See `docs/x9-record-types-plan.md` for the complete record type inventory and phased rollout covering all 32 record types.

| Asset Type | ID | Standard | RT | Notes |
|---|---|---|---|---|
| **Model** | `X9_129_LEGAL_ORDER_HEADER_V1` | X9.129 | 20 | 14 fields — order type codes, amounts, dates, jurisdiction, priority |
| **Model** | `X9_129_SUBJECT_INFO_V1` | X9.129 | 25 | 13 fields — conditional SSN/EIN based on subject type (individual vs business) |
| **Model** | `X9_144_SUBPOENA_HEADER_V1` | X9.144 | 20 | 15 fields — subpoena types, requesting party, compliance deadlines, production scope |
| **Model** | `X9_129_RESPONSE_V1` | X9.129 | 50 | 11 fields — conditional amount fields based on response type (hold vs payment) |
| **Workflow** | `ingest-legal-order` | — | — | Ingestion · 6 states (ResolveModel → ResolveMapping → TransformDocument → ValidateOrder → StoreControlRecord / RouteToException); calls metadata server for resolve/transform/validate; stores to control record store (out of scope); emits event to trigger `process-legal-order` |
| **Workflow** | `ingest-subpoena` | — | — | Ingestion · 6 states; parallels `ingest-legal-order` for X9.144 subpoenas; same pipeline pattern (resolve → map → transform → validate → store) |
| **Workflow** | `process-legal-order` | — | — | Processing · 8 states (HydrateOrder → EnrichWithPartySearch [sub] → EvaluateRouting → RouteOrder [switch] → ManualReview / AutoApprove → NotifyParties [sub] / RouteToException); invokes `party-search` and `notify-parties` as sub-workflows |
| **Workflow** | `process-subpoena` | — | — | Processing · 7 states; invokes `party-search` as a sub-workflow for party enrichment before compliance evaluation |
| **Workflow** | `party-search` | — | — | Enrichment · 6 states (ExtractSearchCriteria → SearchByName → ReconcileResults → EvaluateMatch [switch] → EnrichRecord / ReturnNoMatch); reusable sub-workflow invoked by processing workflows across order types |
| **Workflow** | `notify-parties` | — | — | Notification · 6 states (ResolveRecipients → RenderTemplates → ChooseChannel [switch] → Dispatch → RecordDelivery / LogFailure); reusable sub-workflow for party notifications |
| **Decision** | `legal-order-routing` | — | — | 4 rules, FIRST hit policy, amount/jurisdiction/type inputs |
| **Decision** | `subpoena-compliance` | — | — | Rules for compliance priority and routing |
| **Mapping** | `PDF_EXTRACT_TO_X9_129_LEGAL_ORDER_V1` | — | — | 5 field mappings with transforms |
| **Mapping** | `EDI_TO_X9_144_SUBPOENA_V1` | — | — | 6 field mappings with transforms |

---

## 9. Directory Structure

```text
/
├── docs/
│   └── canonical-model-metadata-server.md     # This specification
├── server/
│   ├── package.json                           # Express, ajv, cors
│   ├── src/
│   │   ├── server.js                          # Express app entry point
│   │   ├── routes/
│   │   │   ├── assets.js                      # /api/assets (unified search)
│   │   │   ├── models.js                      # /api/models
│   │   │   ├── workflows.js                   # /api/workflows
│   │   │   ├── decisions.js                   # /api/decisions
│   │   │   └── mappings.js                    # /api/mappings
│   │   ├── services/
│   │   │   ├── modelService.js                # Model-specific logic + validation
│   │   │   ├── workflowService.js             # Workflow-specific logic + sw.json validation
│   │   │   ├── decisionService.js             # Decision-specific logic + evaluation engine
│   │   │   └── mappingService.js              # Mapping-specific logic + transform execution
│   │   ├── repositories/
│   │   │   ├── AssetRepository.js             # Repository interface contract
│   │   │   ├── MemoryRepository.js            # Dev: in-memory + JSON file persistence
│   │   │   ├── CosmosRepository.js            # Prod: @azure/cosmos SDK (stub for Phase 1)
│   │   │   └── repositoryFactory.js           # Instantiates correct repo from config
│   │   ├── validation/
│   │   │   ├── schemaValidator.js             # ajv-based JSON Schema validation
│   │   │   ├── workflowValidator.js           # CNCF sw.json structural validation
│   │   │   └── zodGenerator.js                # JSON Schema → Zod schema string generator
│   │   └── middleware/
│   │       └── errorHandler.js                # Centralized error handling
│   └── data/
│       └── seed/
│           ├── models/
│           │   ├── X9_129_LEGAL_ORDER_HEADER_V1.json
│           │   ├── X9_129_SUBJECT_INFO_V1.json
│           │   ├── X9_144_SUBPOENA_HEADER_V1.json
│           │   └── X9_129_RESPONSE_V1.json
│           ├── workflows/
│           │   ├── ingest-legal-order.json
│           │   ├── ingest-subpoena.json
│           │   ├── process-legal-order.json
│           │   ├── process-subpoena.json
│           │   ├── party-search.json
│           │   └── notify-parties.json
│           ├── decisions/
│           │   ├── legal-order-routing.json
│           │   └── subpoena-compliance.json
│           └── mappings/
│               ├── PDF_EXTRACT_TO_X9_129_LEGAL_ORDER_V1.json
│               └── EDI_TO_X9_144_SUBPOENA_V1.json
├── client/
│   ├── package.json                           # React, Vite, JSON Forms, Monaco, React Flow
│   ├── vite.config.js
│   ├── index.html
│   └── src/
│       ├── main.jsx                           # App entry
│       ├── App.jsx                            # Shell: sidebar nav + content area
│       ├── api/
│       │   └── client.js                      # Fetch wrapper for /api/* endpoints
│       ├── features/
│       │   ├── catalog/
│       │   │   ├── AssetCatalog.jsx           # Search, browse, filter all assets
│       │   │   └── AssetRelationships.jsx     # Related asset graph
│       │   ├── models/
│       │   │   ├── ModelEditor.jsx            # JSON Schema + UI Schema editing
│       │   │   ├── ModelPreview.jsx           # Live JSON Forms rendering
│       │   │   └── ZodPreview.jsx             # Generated Zod output
│       │   ├── workflows/
│       │   │   ├── WorkflowEditor.jsx         # Monaco + sw.json schema
│       │   │   └── WorkflowDiagram.jsx        # React Flow state diagram
│       │   ├── decisions/
│       │   │   ├── DecisionEditor.jsx         # DMN table grid editor
│       │   │   └── DecisionTester.jsx         # Rule evaluation testing
│       │   ├── mappings/
│       │   │   ├── MappingEditor.jsx          # Source→target field mapping
│       │   │   └── MappingTester.jsx          # Transform testing
│       │   └── inspector/
│       │       ├── DataInspector.jsx          # Render data through model forms (read-only)
│       │       └── WorkflowTracer.jsx         # Visualize workflow execution paths
│       └── components/
│           ├── JsonEditor.jsx                 # Shared Monaco JSON editor wrapper
│           └── AssetHeader.jsx                # Shared asset header (name, version, status, save/cancel)
├── tests/
│   ├── server/
│   │   ├── models.test.js
│   │   ├── workflows.test.js
│   │   ├── decisions.test.js
│   │   └── mappings.test.js
│   └── client/
│       └── (component tests)
└── package.json                               # Root: workspaces or scripts to run both
```

---

## 10. Scalability Design Decisions

Even as a PoC, these decisions ensure the architecture doesn't collapse when moving from 8 seed assets to 1000s:

| Decision | Rationale |
|---|---|
| **Repository pattern** | `AssetRepository` interface with `MemoryRepository` (dev) and `CosmosRepository` (prod). Services never touch storage directly — they call the repository. Swapping backends is a config change, not a rewrite. |
| **Document-per-asset** | Each governance asset is a self-contained JSON document with a unique `id` and `documentType` partition key. Maps directly to Cosmos DB containers and document semantics. |
| **In-memory index for dev** | `MemoryRepository` builds a lightweight in-memory index on startup from seed files. List/search reads from the index — not from loading every full document. |
| **Seed data as JSON files** | Development seed data ships as `/data/seed/{type}/*.json`. `MemoryRepository` loads these on startup. Production loads from Cosmos DB. |
| **Cursor-based pagination** | All list endpoints paginate. No full-collection responses. Default page size 50. |
| **Server-side search** | Full-text search over the index/container, not client-side filtering. Swappable to Elasticsearch later. |
| **Lazy loading in UI** | Catalog shows summary cards. Full asset loaded only when opened for editing. |
| **Immutable versions (future)** | Status lifecycle: `draft` → `active` → `deprecated`. Active assets are immutable; edits create a new version. PoC allows in-place mutation for simplicity. |

---

## 11. Copilot Implementation Directives

*Hello Copilot. When asked to implement this specification, please adhere to the following constraints:*

1. **Monorepo structure:** Root `package.json` with `server/` and `client/` workspaces. Use npm workspaces or simple root scripts (`npm run dev:server`, `npm run dev:client`).
2. **Server:** Express.js, Node.js 20+. Repository pattern for data persistence (`MemoryRepository` for development, `CosmosRepository` stub for production). All service functions return Promises.
3. **Client:** React 18 + Vite. **MUI-first** — use `@mui/material` for all layout, navigation, data display, inputs, and feedback components. Do not write custom HTML/CSS for elements MUI provides. Use `@jsonforms/react` + `@jsonforms/material-renderers` for form rendering (inherits MUI theme). Use `@monaco-editor/react` for JSON editing. Use `@xyflow/react` for workflow diagrams. Use `@serverlessworkflow/sdk-typescript` for sw.json schema validation and Monaco schema autocomplete.
4. **Unified model:** Canonical models use `jsonSchema` + `uiSchema` in one object. ajv validates on server. Zod schema is generated (not hand-maintained). JSON Forms renders from these directly.
5. **Pagination everywhere:** All list endpoints return `{ items, total, page, pageSize }`. Default page size 50.
6. **Repository pattern:** Implement a shared `AssetRepository` interface that handles CRUD, search, and pagination for all governance asset types. `MemoryRepository` is the development implementation (in-memory Map + JSON file persistence). `CosmosRepository` is the production stub using `@azure/cosmos`. Type-specific services compose the repository, not extend it.
7. **Seed data:** Ship the seed data from Section 8 as JSON files in `/data/seed/`. `MemoryRepository` loads these on startup. Configuration selects which repository backend to use.
8. **Feature folders:** Client code organized by feature (`catalog/`, `models/`, `workflows/`, `decisions/`, `mappings/`, `inspector/`).
9. **No auth:** PoC scope. CORS enabled for local dev.
10. **Dark theme:** Match the existing Anax design system (`#0f172a` background, `#1e293b` cards, `#334155` borders, `#e2e8f0` text).
11. **Testing:** Vitest + supertest for server. Vitest + React Testing Library for client. Prioritize API integration tests.
12. **DMN evaluation:** Implement a simple rule evaluator for the JSON-based DMN representation (not a full DMN engine). Match rules by hit policy (FIRST, COLLECT).

---

## 12. Success Criteria

The PoC is complete when:

1. **Asset Catalog** — Can browse, search, and filter across all 10 seed governance assets (4 models, 2 workflows, 2 decisions, 2 mappings). Pagination works.
2. **Model Editor** — Can create/edit a unified model. JSON Schema editor + UI Schema layout + live JSON Forms preview renders correctly. Zod output is generated.
3. **Model Validation** — `POST /api/models/:id/validate` returns structured errors. Errors display inline on the form preview.
4. **Workflow Editor** — Monaco editor with `sw.json` schema validation. State diagram renders alongside and updates live.
5. **Decision Editor** — Can create/edit DMN tables. `POST /api/decisions/:id/evaluate` returns the correct rule match.
6. **Data Inspector** — Can render an order payload through its model's form in read-only mode.
7. **Tests pass** — Server API tests cover CRUD + validation + evaluation for all asset types.
8. **Seed data loads** — Server starts with all seed assets. No manual setup.

---

## 13. Relationship to Dynamic Forms PoC

The Dynamic Forms PoC (v1) proved metadata-driven form rendering works. This PoC (v2) **subsumes and extends it:**

| v1 (Dynamic Forms) | v2 (Metadata Platform) |
|---|---|
| Custom form definition format | JSON Forms standard (jsonSchema + uiSchema) |
| Custom `FormEngine` + `FieldRegistry` | `@jsonforms/react` renderers |
| react-hook-form validation | Zod (generated from JSON Schema) + ajv (server) |
| Form definitions only | Models + Workflows + Decisions + Mappings |
| Client-only | Full-stack (Express + React) |
| Single-purpose renderer | Multi-feature platform (catalog, editors, inspector) |

The design principle remains: **metadata is the single source of truth. Code interprets metadata at runtime.** This PoC makes that principle production-grade by using industry standards (JSON Forms, JSON Schema, CNCF sw.json, DMN) instead of custom formats.

---

## 14. Future Path

| Current (PoC) | Future (Production) |
|---|---|
| `MemoryRepository` (in-memory + JSON files) | `CosmosRepository` with Azure Cosmos DB |
| No auth | Azure AD / OAuth2 + RBAC per asset type |
| Simple DMN evaluator | Full DMN engine (e.g., dmn-js / Camunda) |
| Monaco + JSON Schema for sw.json | Custom language server with jq expression support |
| Read-only workflow diagram | Interactive diagram editing (drag states, draw transitions) |
| In-place mutation | Immutable versioning with draft/active/deprecated lifecycle |
| Single instance | Kubernetes deployment + CDN for static assets |
| Generated Zod strings | Published Zod schemas as npm packages for consumer apps |

---

## 15. Consumer Integration Patterns

This section documents the five runtime integration patterns between the Metadata Management Platform and its consumers. These patterns define the **external contract** — each is independently testable and demoable, and together they describe the complete runtime story of the platform.

### 15.1 Pattern: Workflow Build-Time Delivery

**The Kogito constraint:** Kogito/SonataFlow enforces `sw.json` as a **build-time input** to `kogito-codegen`. The Gradle plugin reads workflow files during `gradle build`, generates Java process classes, REST endpoints, and event listeners, and bakes them into the compiled artifact. The running JAR **is** the workflow — it does not fetch definitions at startup. The metadata server is therefore the **golden source for the build pipeline**, not a runtime peer.

> This distinguishes Kogito from reference CNCF SW runtimes (Synapse, the Node.js SDK) which do fetch-and-execute at runtime. Pattern 15.1 applies specifically to the Kogito build model.

`GET /api/workflows/:workflowId/definition` remains the correct endpoint shape. Its consumers and timing are what change.

#### Sub-pattern A — Pipeline Pull (production path)

A Gradle task in the Kogito project fetches the `sw.json` from the metadata server before `kogito-codegen` runs. The `sw.json` committed to the Kogito project repo is a **generated artifact** — the metadata server is authoritative.

```
Metadata Management Platform
(authoring — metadata server)
    │
    │  GET /api/workflows/process-legal-order/definition
    ▼
Gradle fetchWorkflows task
    │  writes: src/main/resources/process-legal-order.sw.json
    ▼
kogito-codegen
    │  generates Java process classes
    ▼
gradle build → compiled JAR / container image
```

The metadata server's `status` lifecycle gates promotion: the Gradle task only fetches assets with `status == "active"`. A `draft` workflow cannot enter a build.

**Pros:** Clean authoring/compilation separation. Pipeline enforces that the metadata server is authoritative.  
**Cons:** Metadata server must be reachable from the build environment (solvable via CI service account + network policy).

#### Sub-pattern B — Export + Commit (PoC approach)

The Workflow Editor in the metadata server provides an **"Export to project"** action. The developer downloads the `sw.json` and commits it to the Kogito project under `src/main/resources/`. No pipeline coupling — the metadata server is the authoring UX; the Kogito project repo holds the build-time snapshot.

```
Metadata Management Platform
    │
    │  Developer clicks "Export sw.json"
    ▼
Download: process-legal-order.sw.json
    │
    │  Developer commits to Kogito project
    ▼
src/main/resources/process-legal-order.sw.json
    │
    ▼
kogito-codegen → gradle build
```

**Pros:** Zero infrastructure coupling. Works immediately with no CI changes.  
**Cons:** Manual sync step creates drift risk — the committed file can diverge from the metadata server. Acceptable for PoC; not acceptable for production.

_PoC uses Sub-pattern B. Sub-pattern A is documented as the production path._

#### Future path — VS Code Extension

The intended long-term solution eliminates the manual export step. A VS Code extension authenticates against the metadata server, presents the asset catalog in a sidebar, and provides:

- **Pull** — fetch a workflow definition from the catalog directly into `src/main/resources/` in the current workspace (replacing the manual export download)
- **Push** — read a locally modified `sw.json` back to the metadata server as a new draft version (with diff preview)
- **Build trigger** — invoke the Gradle task after a successful pull

This mirrors what the SonataFlow VS Code extension does for the KIE tooling ecosystem. The `/definition` API endpoint is exactly the right shape for a pull action. The extension targets this metadata server rather than KIE tooling, allowing the same authoring workflow regardless of the downstream CNCF SW runtime.

**`serverDefinitions` note:** Function `operation` URLs in `sw.json` reference the metadata server and other downstream services by symbolic hostname. In CNCF SW 0.8, the base URL is injected at runtime from a `serverDefinitions` block keyed to an environment variable — keeping the exported `sw.json` environment-agnostic.

```json
"serverDefinitions": {
  "metadata-server": { "href": "${METADATA_SERVER_URL}" }
}
```

_PoC: `METADATA_SERVER_URL=http://localhost:3001` in devcontainer `.env`. Production: injected by Kubernetes environment config._

---

### 15.2 Pattern: Ingest Workflow — Runtime Calls to Metadata Server

**When:** A raw inbound document arrives (PDF extract, EDI file, or digital X9 record). The ingest service — itself a Kogito workflow (`ingest-legal-order`) authored in the metadata server and delivered at build time (Pattern 15.1) — processes the document end-to-end and emits an event for downstream processing.

**Two data stores are involved:** the metadata server (governance assets — this PoC) and the control record store (canonical order data — separate Cosmos DB, out of scope for this PoC).

```
ingest-legal-order workflow (Kogito)
    │
    │  receives: raw document + { sourceFormat, jurisdiction, documentType, effectiveDate }
    │
    ▼  ResolveModel state — metadata server
GET /api/models/resolve?jurisdiction=NY&documentType=LEGAL_ORDER&effectiveDate=2026-03-06
    │  → { modelId: "X9_129_LEGAL_ORDER_HEADER_V1" }
    │
    ▼  ResolveMapping state — metadata server
GET /api/mappings/resolve?sourceFormat=PDF_EXTRACT&targetModelId=X9_129_LEGAL_ORDER_HEADER_V1
    │  → { mappingId: "PDF_EXTRACT_TO_X9_129_LEGAL_ORDER_V1" }
    │
    ▼  TransformDocument state — metadata server
POST /api/mappings/PDF_EXTRACT_TO_X9_129_LEGAL_ORDER_V1/transform
Body: { raw extracted fields }
    │  → { canonical payload }
    │
    ▼  ValidateOrder state — metadata server
POST /api/models/X9_129_LEGAL_ORDER_HEADER_V1/validate
Body: { canonical payload }
    │  → { valid: true } or { valid: false, errors: [...] }
    │
    ▼  StoreControlRecord state — control record store (NOT metadata server)
POST http://control-record-store/api/records
Body: { modelId, canonical payload }
    │  → { controlRecordId: "CR-2026-001" }
    │
    ▼  EmitEvent state
emit: { controlRecordId: "CR-2026-001", modelId: "X9_129_LEGAL_ORDER_HEADER_V1" }
    │
    ▼  triggers process-legal-order workflow
```

**Audit trail integrity:** The `effectiveDate` passed to `/resolve` is the document's own effective date, not the processing date. The resolve algorithm (§7.2) returns the model that **was active on that date**, ensuring the governing model version is reproducible for future audits regardless of when the record is inspected.

**Validation failure path:** If `ValidateOrder` returns `valid: false`, the workflow transitions to an error state, emits a rejection event, and does **not** store a control record. No `controlRecordId` is issued for invalid documents.

---

### 15.3 Pattern: Claim Check — Processing Workflow Hydration

**When:** The `process-legal-order` workflow is triggered by an ingest event. The event carries only a `controlRecordId` — no order payload. The workflow fetches the canonical data it needs from the control record store using that ID.

**Why no payload in the event:** Order data can be large and sensitive. The claim check pattern keeps event payloads minimal (just the reference ID) and access-controlled (only services with credentials to the control record store can retrieve the data). The metadata server never sees or stores order data.

```
Ingest event: { controlRecordId: "CR-2026-001", modelId: "X9_129_LEGAL_ORDER_HEADER_V1" }
    │
    │  triggers process-legal-order workflow
    │
    ▼  HydrateOrder state — control record store (NOT metadata server)
GET http://control-record-store/api/records/CR-2026-001
    │  → { canonical orderData }
    │
    ▼  EvaluateRouting state — metadata server
POST /api/decisions/legal-order-routing/evaluate
Body: { orderAmount: 75000, jurisdictionCode: "NY", orderTypeCode: "TAX_LEVY" }
    │  → { requiresReview: true, priority: "high", assignee: "senior-reviewer" }
    │
    ▼  RouteOrder switch state
    branches to ManualReview or AutoApprove
```

**Control record store is out of scope for this PoC.** The `process-legal-order` sw.json governance asset stored in the metadata server is complete and correct — `fetchControlRecord` is a fully specified function reference pointing to `http://control-record-store/api/records/{controlRecordId}`. The metadata server stores this URL as metadata; it never calls it. The control record store is a separate service that neither exists in the PoC environment nor is emulated by the metadata server. Integration tests for the processing workflow mock the `fetchControlRecord` response directly to exercise routing and decision logic in isolation.

---

### 15.4 Pattern: Decision as a Service

**When:** A workflow needs routing, prioritization, or compliance logic that is owned by compliance/operations teams and must evolve independently of the workflow definition.

```
sw.json CheckRouting state
    │
    ▼
POST /api/decisions/legal-order-routing/evaluate
Body: { "orderAmount": 75000, "jurisdictionCode": "NY", "orderTypeCode": "TAX_LEVY" }
    │
    ▼
{
  "ruleId": 1,
  "description": "High-value orders require manual review",
  "matchedConditions": { "orderAmount": "> 50000" },
  "output": { "requiresReview": true, "priority": "high", "assignee": "senior-reviewer" }
}
    │
    ▼
RouteOrder switch state — branches to ManualReview
```

**Why DMN over jq `dataConditions` in the switch state:**

A CNCF SW `switch` state can express routing logic as inline jq expressions without any external call. DMN is the right tool here for three reasons:

| Reason | Detail |
|---|---|
| **Team ownership** | Routing thresholds are owned by compliance and operations teams — not developers. A compliance analyst can edit a decision table row in the Decision Editor (a spreadsheet-like UI) without touching workflow JSON or triggering a workflow deployment cycle. |
| **Testability in isolation** | The Decision Editor's test panel lets a compliance analyst validate that `orderAmount > 50000 → requiresReview: true` fires correctly before any workflow involvement. A jq `dataCondition` cannot be tested without executing the workflow. |
| **Auditability** | Decision tables are first-class governance assets: versioned, status-tracked, and queryable. An auditor can inspect the exact rule set and version that fired for a given order without reading workflow JSON. |

---

### 15.5 Pattern: Operational Inspection

**When:** An operator or developer needs to inspect a live order payload in human-readable form — without building a custom UI for each order type.

```
Operator has:
  - orderPayload (raw JSON from case management or order database)
  - modelId ("X9_129_LEGAL_ORDER_HEADER_V1")
    │
    ▼
GET /api/models/X9_129_LEGAL_ORDER_HEADER_V1
    │
    ▼  Returns: { jsonSchema, uiSchema, ... }
    │
    ▼
React Data Inspector:
  <JsonForms
    schema={jsonSchema}
    uischema={uiSchema}
    data={orderPayload}
    readonly={true}
  />
    │
    ▼
Form renders the order in grouped, labeled, human-readable layout
with validation overlay showing any field-level errors in the live data
```

**Why this closes the authoring/operations loop:** The developer who authored the canonical model previewed a form in the Model Editor. The operator inspecting a live order a year later sees **exactly the same form**, driven by the same metadata. No per-order-type UI code is ever written; the platform's metadata drives all rendering. This is the design principle from §3.3 made concrete.

**Workflow trace extension (Phase 5):** The same pattern applies to workflow execution inspection. Given an execution trace, the State Diagram renders the traversed path highlighted in the diagram, with data snapshots at each state — composed from the Workflow Editor's diagram (Phase 3) rendered in read-only mode.