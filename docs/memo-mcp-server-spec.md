# Request: MCP Server for GitHub Copilot Agent Integration

**From:** Custom Workflow Starter Team  
**To:** Metadata Management Platform Team  
**Date:** March 21, 2026  
**Subject:** Adding an MCP (Model Context Protocol) server to the metadata platform for AI-assisted governance asset authoring  

---

## Context

The Custom Workflow Starter integrates with GitHub Copilot through three layers:

1. **`.github/copilot-instructions.md`** — static guidance on URI schemes and conventions
2. **`catalog.json`** (build artifact) — machine-readable inventory of a project's available operations
3. **`/anax/catalog`** (runtime endpoint) — live-augmented catalog for dashboards and tooling

These layers let Copilot **read** project-specific metadata to generate valid `.sw.json` workflow definitions. But the flow is one-directional — Copilot can *consume* existing assets, it cannot *create* new ones.

We're aware of the existing VS Code extension (`@anax.metadata` chat participant, §14.1) that provides Copilot Chat tools for browsing the asset catalog. That extension was built to aid development of the metadata platform itself — specifically the React frontend. It is not intended as the general-purpose integration layer for consuming developers.

### The opportunity: generate metadata from metadata

Our developers want to use Copilot to author new governance assets by reasoning over existing ones:

- Existing canonical model (Tax Levy) → new model (Child Support) with similar structure, adapted fields
- Existing decision table (Legal Order Routing) → new decision table for a new order type
- Existing workflow → variant workflow for a related domain
- Existing Jolt mapping → new mapping for a different inbound format

This requires Copilot to have **read access** to the metadata server's full asset inventory and **write access** to create new assets — not just the local project's `catalog.json`.

### Why MCP

[Model Context Protocol](https://modelcontextprotocol.io/) (MCP) is the open standard that GitHub Copilot Agent Mode in VS Code uses for tool integration. An MCP server exposes **tools** (callable functions) that Copilot can invoke during a conversation. The developer stays in VS Code; Copilot calls the metadata server directly through MCP.

MCP is the right foundation because:

- **Native to VS Code Copilot** — no custom extension needed, just a `mcp.json` config file
- **Two-way interaction** — Copilot reads existing assets AND creates/validates new ones
- **Thin adapter** — MCP wraps the existing REST API; no new business logic in the metadata server
- **Works in the devcontainer** — metadata server is already running at `localhost:3001`
- **Client-agnostic** — any MCP-compatible client (Copilot Agent Mode, Claude, custom agents) can use the tools
- **Extension consolidation** — the existing VS Code extension can be refactored to use the MCP server as its backend, centralizing tool definitions in one place and separating the extension's UX concerns from the server-side tool logic

---

## What We're Requesting

Add an MCP server endpoint to the metadata management platform. This can be implemented as either:

- **(a)** A new route (`/mcp`) on the existing Express server using SSE (Server-Sent Events) transport, or
- **(b)** A separate lightweight process using stdio transport (simpler but requires a separate process)

**Our preference is (a)** — a single Express app serving both REST and MCP. The `@modelcontextprotocol/sdk` npm package provides the server implementation.

---

## MCP Tool Definitions

The MCP server should expose the following tools. Each tool maps to an existing REST API endpoint — this is a thin adapter layer, not new business logic.

### Discovery Tools (Read-Only)

These tools let Copilot browse the asset catalog and understand what's available.

#### `list_models`

| Field | Value |
|-------|-------|
| Description | List canonical models. Returns model IDs, names, standards, statuses. |
| Parameters | `status` (optional, string): Filter by status (`draft`, `active`, `deprecated`). `standard` (optional, string): Filter by standard (e.g., `X9.129`). `q` (optional, string): Free-text search. |
| Maps to | `GET /api/models?status={status}&q={q}` |
| Returns | Array of model summaries: `{ modelId, name, standard, version, status }` |

#### `get_model`

| Field | Value |
|-------|-------|
| Description | Get a canonical model's full definition including JSON Schema, UI Schema, and metadata. |
| Parameters | `modelId` (required, string): The model identifier. |
| Maps to | `GET /api/models/{modelId}` |
| Returns | Full model object including `jsonSchema`, `uiSchema`, `metadata` |

#### `list_decisions`

| Field | Value |
|-------|-------|
| Description | List decision tables. Returns decision IDs, names, namespaces, statuses. |
| Parameters | `status` (optional, string): Filter by status. `namespace` (optional, string): Filter by DMN namespace. `name` (optional, string): Filter by decision name. `q` (optional, string): Free-text search. |
| Maps to | `GET /api/decisions?status={status}&namespace={namespace}&name={name}&q={q}` |
| Returns | Array of decision summaries: `{ decisionId, name, namespace, version, status }` |

#### `get_decision`

| Field | Value |
|-------|-------|
| Description | Get a decision table's full definition including rules, inputs, outputs, and DMN source. |
| Parameters | `decisionId` (required, string): The decision identifier. |
| Maps to | `GET /api/decisions/{decisionId}` |
| Returns | Full decision object including `rules[]`, `inputs[]`, `outputs[]`, `sourceContent` (DMN XML) |

#### `list_workflows`

| Field | Value |
|-------|-------|
| Description | List workflows. Returns workflow IDs, names, categories, statuses. |
| Parameters | `status` (optional, string): Filter by status. `category` (optional, string): Filter by category (`ingestion`, `processing`, `enrichment`, `notification`). `q` (optional, string): Free-text search. |
| Maps to | `GET /api/workflows?status={status}&category={category}&q={q}` |
| Returns | Array of workflow summaries: `{ workflowId, name, category, version, status }` |

#### `get_workflow`

| Field | Value |
|-------|-------|
| Description | Get a workflow's full definition including the sw.json and metadata. |
| Parameters | `workflowId` (required, string): The workflow identifier. |
| Maps to | `GET /api/workflows/{workflowId}` |
| Returns | Full workflow object including `definition` (the `sw.json` content) and `metadata` |

#### `list_mappings`

| Field | Value |
|-------|-------|
| Description | List transformation mappings. Returns mapping IDs, names, source/target info, statuses. |
| Parameters | `status` (optional, string): Filter by status. `sourceFormat` (optional, string): Filter by source format. `q` (optional, string): Free-text search. |
| Maps to | `GET /api/mappings?status={status}&sourceFormat={sourceFormat}&q={q}` |
| Returns | Array of mapping summaries: `{ mappingId, name, sourceFormat, targetModelId, version, status }` |

#### `get_mapping`

| Field | Value |
|-------|-------|
| Description | Get a mapping's full definition including the Jolt transformation spec. |
| Parameters | `mappingId` (required, string): The mapping identifier. |
| Maps to | `GET /api/mappings/{mappingId}` |
| Returns | Full mapping object including `spec` (the Jolt spec) and `metadata` |

#### `resolve_model`

| Field | Value |
|-------|-------|
| Description | Resolve a canonical model by jurisdiction, document type, and effective date. Uses the deterministic version selection algorithm (filter active, match jurisdiction, match date range, select highest version). |
| Parameters | `jurisdiction` (required, string): Jurisdiction code (e.g., `US`, `NY`, `CA`). `documentType` (optional, string): Document type filter. `effectiveDate` (optional, string): ISO date — selects the model that was active on this date. |
| Maps to | `GET /api/models/resolve?jurisdiction={jurisdiction}&documentType={documentType}&effectiveDate={effectiveDate}` |
| Returns | The resolved model object, or 404 if no match |

#### `search_assets`

| Field | Value |
|-------|-------|
| Description | Search across all asset types (models, decisions, workflows, mappings) by keyword. Useful for discovering related assets. |
| Parameters | `q` (required, string): Search query. `type` (optional, string): Filter to one asset type (`model`, `decision`, `workflow`, `mapping`). `status` (optional, string): Filter by status. |
| Maps to | `GET /api/assets?q={q}&type={type}&status={status}` |
| Returns | Array of asset summaries with type discriminator |

#### `get_related_assets`

| Field | Value |
|-------|-------|
| Description | Get assets related to a given asset (e.g., decisions used by a workflow, models referenced by a mapping). |
| Parameters | `assetType` (required, string): The asset type (`model`, `decision`, `workflow`, `mapping`). `assetId` (required, string): The asset identifier. |
| Maps to | `GET /api/assets/{assetType}/{assetId}/related` |
| Returns | Array of related assets grouped by relationship type |

### Authoring Tools (Write)

These tools let Copilot create new governance assets. All write operations should create assets in **`draft`** status regardless of what the tool call specifies — the human author promotes to `active` manually via the platform UI.

#### `create_model`

| Field | Value |
|-------|-------|
| Description | Create a new canonical model. The model will be created in `draft` status. Provide the full JSON Schema and optionally, a UI Schema. |
| Parameters | `name` (required, string): Human-readable model name. `standard` (required, string): The standard this model belongs to (e.g., `X9.129`, `X9.144`). `jsonSchema` (required, object): The model's JSON Schema definition. `uiSchema` (optional, object): JSON Forms UI Schema for rendering. `description` (optional, string): Model description. |
| Maps to | `POST /api/models` (with `status: "draft"` forced) |
| Returns | The created model object with assigned `modelId` |

#### `create_decision`

| Field | Value |
|-------|-------|
| Description | Create a new decision table. The decision will be created in `draft` status. Provide the rules, inputs, and outputs. |
| Parameters | `name` (required, string): Human-readable decision name. `namespace` (required, string): DMN namespace (e.g., `com.anax.decisions`). `inputs` (required, array of objects): Input columns with `name`, `type`, `description`. `outputs` (required, array of objects): Output columns with `name`, `type`, `description`. `rules` (required, array of objects): Decision rules — each rule has input entries and output entries. `hitPolicy` (optional, string, default `FIRST`): Hit policy (`FIRST`, `COLLECT`, `UNIQUE`, `ANY`). `description` (optional, string): Decision description. |
| Maps to | `POST /api/decisions` (with `status: "draft"` forced) |
| Returns | The created decision object with assigned `decisionId` |

#### `create_workflow`

| Field | Value |
|-------|-------|
| Description | Create a new CNCF Serverless Workflow. The workflow will be created in `draft` status. Provide the sw.json definition and wrapper metadata. |
| Parameters | `name` (required, string): Human-readable workflow name. `category` (required, string): Workflow category (`ingestion`, `processing`, `enrichment`, `notification`). `definition` (required, object): The full CNCF Serverless Workflow `sw.json` definition. `description` (optional, string): Workflow description. `relatedModels` (optional, array of strings): Model IDs this workflow references. |
| Maps to | `POST /api/workflows` (with `status: "draft"` forced) |
| Returns | The created workflow object with assigned `workflowId` |

#### `create_mapping`

| Field | Value |
|-------|-------|
| Description | Create a new transformation mapping. The mapping will be created in `draft` status. Provide the Jolt transformation spec. |
| Parameters | `name` (required, string): Human-readable mapping name. `sourceFormat` (required, string): The source data format (e.g., `X9.129-NACHA`, `CSV`). `targetModelId` (required, string): The canonical model this mapping transforms into. `spec` (required, array of objects): The Jolt transformation spec. `description` (optional, string): Mapping description. |
| Maps to | `POST /api/mappings` (with `status: "draft"` forced) |
| Returns | The created mapping object with assigned `mappingId` |

### Validation Tools

These tools let Copilot verify assets before or after creation — a "test before you save" capability.

#### `validate_model`

| Field | Value |
|-------|-------|
| Description | Validate a data payload against a canonical model's JSON Schema. Useful for testing whether sample data conforms to a model. |
| Parameters | `modelId` (required, string): The model to validate against. `data` (required, object): The data payload to validate. |
| Maps to | `POST /api/models/{modelId}/validate` |
| Returns | Validation result: `{ valid: boolean, errors: [{ path, message }] }` |

#### `validate_decision`

| Field | Value |
|-------|-------|
| Description | Validate a decision table's structural integrity — checks hit policy, input/output definitions, and rule completeness. |
| Parameters | `decisionId` (required, string): The decision to validate. |
| Maps to | `POST /api/decisions/{decisionId}/validate` |
| Returns | Validation result: `{ valid: boolean, errors: [{ path, message }] }` |

> **Note:** Full DMN evaluation (`POST /api/decisions/:id/evaluate`) is listed in the platform spec §15.4 as a planned future enhancement. When it ships, we should add an `evaluate_decision` MCP tool. For now, structural validation is the available capability.

#### `validate_workflow`

| Field | Value |
|-------|-------|
| Description | Validate a workflow's sw.json definition against the CNCF Serverless Workflow schema and check referential integrity (sub-workflows, function references exist). |
| Parameters | `workflowId` (required, string): The workflow to validate. |
| Maps to | `POST /api/workflows/{workflowId}/validate` |
| Returns | Validation result: `{ valid: boolean, errors: [{ path, message, type }] }` |

#### `validate_mapping`

| Field | Value |
|-------|-------|
| Description | Validate a mapping's Jolt spec structure — checks operation types, spec requirements, and warns on duplicates or empty specs. |
| Parameters | `mappingId` (required, string): The mapping to validate. |
| Maps to | `POST /api/mappings/{mappingId}/validate` |
| Returns | Validation result: `{ valid: boolean, errors: [{ path, message }] }` |

> **Note:** Runtime Jolt transform execution (`POST /api/mappings/:id/transform`) is listed in the platform spec §15.2 as a planned future enhancement. When it ships, we should add a `transform_mapping` MCP tool. For now, structural validation is the available capability.

---

## Tool Summary

| Tool | Category | HTTP Method | Endpoint |
|------|----------|-------------|----------|
| `list_models` | Discovery | GET | `/api/models` |
| `get_model` | Discovery | GET | `/api/models/{modelId}` |
| `list_decisions` | Discovery | GET | `/api/decisions` |
| `get_decision` | Discovery | GET | `/api/decisions/{decisionId}` |
| `list_workflows` | Discovery | GET | `/api/workflows` |
| `get_workflow` | Discovery | GET | `/api/workflows/{workflowId}` |
| `list_mappings` | Discovery | GET | `/api/mappings` |
| `get_mapping` | Discovery | GET | `/api/mappings/{mappingId}` |
| `resolve_model` | Discovery | GET | `/api/models/resolve` |
| `search_assets` | Discovery | GET | `/api/assets` |
| `get_related_assets` | Discovery | GET | `/api/assets/{assetType}/{assetId}/related` |
| `create_model` | Authoring | POST | `/api/models` |
| `create_decision` | Authoring | POST | `/api/decisions` |
| `create_workflow` | Authoring | POST | `/api/workflows` |
| `create_mapping` | Authoring | POST | `/api/mappings` |
| `validate_model` | Validation | POST | `/api/models/{modelId}/validate` |
| `validate_decision` | Validation | POST | `/api/decisions/{decisionId}/validate` |
| `validate_workflow` | Validation | POST | `/api/workflows/{workflowId}/validate` |
| `validate_mapping` | Validation | POST | `/api/mappings/{mappingId}/validate` |

---

## Implementation Notes

### Transport

The MCP spec supports two transports:

| Transport | How it works | Pros | Cons |
|-----------|-------------|------|------|
| **SSE** (Server-Sent Events) | HTTP endpoint on the existing Express server (e.g., `POST /mcp`) | Single process, shared port, simple deployment | Slightly more complex HTTP handling |
| **stdio** | Separate Node.js process, communicates via stdin/stdout | Simplest implementation, easy to test | Requires a second process, separate package |

**Recommendation:** SSE on the existing Express server at `/mcp`. This keeps the devcontainer simple (one service, one port) and aligns with how VS Code configures remote MCP servers.

### SDK

The `@modelcontextprotocol/sdk` npm package provides:

- `McpServer` class with `.tool()` method for registering tools
- Zod-based parameter validation (you already use Zod)
- SSE transport adapter for Express

Example skeleton:

```typescript
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { z } from "zod";

const mcp = new McpServer({
  name: "anax-metadata",
  version: "0.2.0",
});

mcp.tool(
  "list_decisions",
  "List decision tables with optional filters",
  {
    status: z.string().optional().describe("Filter by status"),
    namespace: z.string().optional().describe("Filter by DMN namespace"),
    name: z.string().optional().describe("Filter by decision name"),
    q: z.string().optional().describe("Free-text search"),
  },
  async ({ status, namespace, name, q }) => {
    // Call the existing decisions service/repository
    const result = await decisionService.list({ status, namespace, name, q });
    return {
      content: [{ type: "text", text: JSON.stringify(result.data, null, 2) }],
    };
  }
);

// Mount on Express
app.get("/mcp/sse", async (req, res) => {
  const transport = new SSEServerTransport("/mcp/messages", res);
  await mcp.connect(transport);
});

app.post("/mcp/messages", async (req, res) => {
  await transport.handlePostMessage(req, res);
});
```

### Safety Constraints

All write tools **must** force `status: "draft"` on the created asset regardless of what the caller provides. This ensures:

- Copilot cannot create assets that immediately become active in the build pipeline
- A human must review and promote every AI-generated asset via the platform UI
- Accidental or malformed assets cannot affect production workflows

### VS Code Configuration

Consumers add the MCP server to their project or user settings:

**`.vscode/mcp.json`:**
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

In the devcontainer, the metadata server is at `localhost:3001`, so this works immediately.

---

## Expected Developer Workflow

Once the MCP server is available, the developer workflow for "generate metadata from metadata" looks like this:

```
Developer (in VS Code, Copilot Agent Mode):
  "Create a Child Support canonical model similar to Tax Levy but with 
   different field requirements and a CHILD_SUPPORT order type."
         │
         ▼
Copilot calls list_models → discovers existing models
Copilot calls get_model("tax-levy-order") → reads full JSON Schema + UI Schema
         │
         ▼
Copilot generates a new model adapted for Child Support:
  - Same structural pattern (header, parties, amounts, dates)
  - Different orderType enum, required fields, validation rules
  - Adapted UI Schema for the new field set
         │
         ▼
Copilot calls create_model → creates the new model in draft status
Copilot calls validate_model → tests it with sample data
         │
         ▼
Developer reviews in metadata platform UI → promotes draft → active
```

**The same pattern applies for decisions, workflows, and mappings.** Copilot can read existing assets, generate variants, create them as drafts, and validate them — all within a single VS Code conversation.

---

## Integration Test Results

> **Update (v0.3.0 shipped, v0.3.1 fixes all bugs):** The metadata team incorporated MCP support in v0.3.0 and fixed all reported bugs in v0.3.1. Key design decisions confirmed:
>
> - **SSE on existing Express (option a)** — single process, single port ✅
> - **All write tools force `status: "draft"`** — Copilot cannot create active assets ✅
> - **Thin adapter** — every tool delegates to existing services/repository, no new business logic ✅
> - **All existing tests still pass** ✅

### v0.3.1 Results (March 22, 2026)

**REST API: 30/30 passed** (unchanged from v0.2.0).

**MCP protocol:**
- SSE connection at `/mcp/sse` → returns session ID ✅
- `initialize` → server identifies as `anax-metadata v0.3.0`, protocol `2024-11-05`, capabilities: `logging`, `tools` ✅
- `tools/list` → **19 tools returned, all with `inputSchema`** ✅ (was Zod `_zod` crash in v0.3.0 — **FIXED**)

**All 19 MCP tools pass:**

| Category | Tool | Status | Notes |
|----------|------|--------|-------|
| Discovery | `list_models` | ✅ | 32 seed items |
| Discovery | `get_model` | ✅ | Param: `modelId` (uppercase IDs like `X9_129_ACCOUNT_DETAIL_V1`) |
| Discovery | `list_decisions` | ✅ | 4 seed items |
| Discovery | `get_decision` | ✅ | Param: `decisionId` |
| Discovery | `list_workflows` | ✅ | 6 seed items |
| Discovery | `get_workflow` | ✅ | Param: `workflowId` (kebab-case IDs like `ingest-legal-order`) |
| Discovery | `list_mappings` | ✅ | 3 seed items |
| Discovery | `get_mapping` | ✅ | Param: `mappingId` |
| Discovery | `resolve_model` | ✅ | Params: `jurisdiction` + `namespace` + `name` |
| Discovery | `search_assets` | ✅ | Param: `q`. `{"q": "legal"}` → 8 results |
| Discovery | `get_related_assets` | ✅ | Params: `assetId` + `assetType` |
| Authoring | `create_model` | ✅ | Creates in `draft` status. Params: `name`, `standard`, `jsonSchema` (**was Zod crash in v0.3.0**) |
| Authoring | `create_decision` | ✅ | Creates in `draft` status. Params: `name`, `namespace`, `inputs`, `outputs`, `rules` (**was "missing decisionId" in v0.3.0**) |
| Authoring | `create_workflow` | ✅ | Creates in `draft` status. Params: `name`, `category`, `definition` (**was Zod crash in v0.3.0**) |
| Authoring | `create_mapping` | ✅ | Creates in `draft` status. Params: `name`, `sourceFormat`, `targetModelId`, `spec` (**was param name issue in v0.3.0**) |
| Validation | `validate_model` | ✅ | Params: `modelId` + `data`. Returns `{valid, errors}` (**was Zod crash in v0.3.0**) |
| Validation | `validate_decision` | ✅ | Returns `{valid: true, errors: [], warnings: []}` |
| Validation | `validate_workflow` | ✅ | Same structure |
| Validation | `validate_mapping` | ✅ | Same structure |

**Summary: 19/19 tools pass. All 5 bugs from v0.3.0 are fixed.**

### v0.3.0 Bug Resolution

| Bug | v0.3.0 Symptom | v0.3.1 Status |
|-----|---------------|---------------|
| `tools/list` Zod crash | `Cannot read properties of undefined (reading '_zod')` | **Fixed** — returns 19 tools with inputSchema |
| `create_model` Zod crash | Same `_zod` error | **Fixed** — creates model in draft status |
| `create_workflow` Zod crash | Same `_zod` error | **Fixed** — creates workflow in draft status |
| `validate_model` Zod crash | Same `_zod` error | **Fixed** — validates data against model schema |
| `create_decision` missing field | "Missing required field: decisionId" | **Fixed** — creates decision in draft status |
| `create_mapping` param name | Zod validation rejection | **Fixed** — accepts `targetModelId` |

### Parameter Names (confirmed in v0.3.1)

| Tool | Key Parameters |
|------|---------------|
| `resolve_model` | `jurisdiction` + `namespace` + `name` |
| `search_assets` | `q` (free-text search) |
| `create_model` | `jsonSchema` (not `schema`), `standard` required |
| `create_mapping` | `targetModelId` (not `targetModel`) |

### Next Steps

1. **Create `.vscode/mcp.json`** — configure Copilot Agent Mode to connect to the MCP server. All tools are now functional.
2. **End-to-end Copilot workflow test** — "Create a Child Support model similar to Tax Levy" using Agent Mode.
3. **Extension migration** — we envision the existing VS Code extension (`@anax.metadata`) being refactored to use the MCP server as its backend. Does that align with your team's plans?

---

## Relationship to Existing Integration

This MCP server complements — does not replace — the existing integration points:

| Integration | Purpose | Status |
|-------------|---------|--------|
| `GET /api/decisions/{id}/export` | Build-time DMN file download for Kogito codegen | **Shipped in v0.2.0** |
| `GET /api/mappings/{id}/export` | Build-time Jolt spec download for Kogito codegen | **Shipped in v0.2.0** |
| `GET /api/decisions?namespace=...&name=...&status=active` | Build-time decision resolution by namespace + name | **Shipped in v0.2.0** |
| VS Code extension (`@anax.metadata` chat participant) | Internal dev tool — aids building the React frontend | **Shipped (§14.1)** |
| MCP server at `/mcp/sse` | Design-time Copilot ↔ metadata server two-way tool access | **Shipped in v0.3.0, all bugs fixed in v0.3.1 — 19/19 tools pass** |

The build-time endpoints serve the Gradle plugin (automated, no human in the loop). The MCP server serves developers in VS Code (interactive, Copilot-mediated, human reviews all outputs).

**Future:** The existing VS Code extension will be refactored to use the MCP server as its backend. The extension will continue to own UX concerns (chat participant, system prompt, domain-aware formatting), while the MCP server centralizes all tool definitions. This separation ensures tool logic is maintained in one place and is available to any MCP client.
