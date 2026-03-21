# Inquiry: Metadata Server API Requirements for Build-Time Asset Resolution

**From:** Custom Workflow Starter Team  
**To:** Metadata Management Platform Team  
**Date:** March 21, 2026  
**Subject:** Cross-service integration — API requirements for build-time governance asset resolution  

---

## Context

We are building a Gradle plugin (`anax-kogito-codegen-plugin`) that consumes governance assets stored on the Metadata Management Platform at **build time**. The plugin reads workflow definitions (`.sw.json` files) that reference DMN decision models and mapping specs via custom URI schemes, then fetches the corresponding assets from the metadata server so the Kogito codegen engine can process them.

The workflow is:

1. Authors create governance resources (DMN models, Jolt mapping specs) and publish them to the metadata server
2. Workflow developers reference those resources in `.sw.json` files using custom URIs:
   - `"operation": "dmn://com.anax.decisions/Order Type Routing"`
   - `"operation": "map://x9-field-mapping"`
3. At build time, our Gradle plugin **parses** these URIs, **fetches** the original artifacts from the metadata server, and writes them to the build output directory
4. The Kogito codegen engine then processes the downloaded `.dmn` files alongside the `.sw.json` to generate Java source code
5. If any referenced asset is missing on the metadata server, the build **fails** — this is intentional build validation

**The Kogito codegen engine requires the original DMN XML file, not a derived JSON representation.** Similarly, our mapping handler will need the original Jolt spec JSON, not a parsed field-level representation.

---

## What We Need From the Metadata Server

### 1. Original Artifact Download Endpoints

We need endpoints that return the **original uploaded file content** for decisions and mappings:

| Proposed Endpoint | Method | Content-Type | Returns |
|-------------------|--------|--------------|---------|
| `/api/decisions/{decisionId}/export?format=dmn` | GET | `application/xml` | The original `.dmn` XML file as uploaded by the author |
| `/api/mappings/{mappingId}/export?format=jolt` | GET | `application/json` | The original Jolt spec JSON as uploaded by the author |

**Key point:** We need the raw file bytes of the original artifact — not the metadata server's parsed/structured representation. The existing `GET /api/decisions/:decisionId` and `GET /api/mappings/:mappingId` endpoints return the server's internal JSON model, which is useful for the UI but not consumable by the Kogito codegen engine.

If the metadata server already stores original uploaded files as attachments, exposing them via a download endpoint may be straightforward. If it only stores the parsed representation, this is a gap we need to discuss.

**Alternative approach:** If export endpoints are too heavy for the first iteration, a simpler option is a raw file storage model — the metadata server stores the original uploaded file as a binary blob and serves it verbatim on request.

### 2. Decision Lookup by Namespace + Model Name (Critical Question)

Our `dmn://` URI scheme encodes two components:

```
dmn://com.anax.decisions/Order Type Routing
       └── namespace ──┘  └── modelName ──┘
```

These map to the DMN standard's `<definitions namespace="..." name="...">` attributes. **The URI does not contain a `decisionId` that corresponds to the metadata server's internal ID.**

We need a way to resolve a decision using the namespace and model name from the URI. Three options we've considered:

| Option | API Call | What We Need From You |
|--------|---------|----------------------|
| **A. Search/filter endpoint** | `GET /api/decisions?namespace=com.anax.decisions&name=Order Type Routing&status=active` | Support `namespace` and `name` query parameters on the existing list/search endpoint. Return results filterable to a single active version. |
| **B. Convention-based ID** | `GET /api/decisions/com.anax.decisions--order-type-routing` | Agree on a `decisionId` convention that deterministically maps from namespace+name. Fragile — we'd prefer not to go this route. |
| **C. URI-as-ID** | `GET /api/decisions/com.anax.decisions%2FOrder%20Type%20Routing` | The metadata server uses the full URI path as the `decisionId`. Requires URL-encoded slashes and spaces in path segments. |

**Our preference is Option A.** It's the most robust and doesn't require changes to the metadata server's ID scheme. Mappings are simpler — the `map://` URI directly contains the `mappingId`:

```
map://x9-field-mapping  →  GET /api/mappings/x9-field-mapping/export?format=jolt
```

**Questions for your team:**

1. Does the decisions API currently support filtering by `namespace` and `name` query parameters? If not, how much effort would it be to add?
2. Is there a relationship between the DMN `<definitions namespace="...">` attribute and any existing field on the decision model in the metadata server?
3. What is the current `decisionId` format? Is it user-defined, auto-generated, or derived from the decision name?

### 3. Asset Status and Versioning

Our plugin should only resolve **active, published** assets. Questions:

1. Do decisions and mappings have a `status` field (e.g., `draft`, `active`, `deprecated`)?
2. If multiple versions of the same decision exist, how do we get the latest active version? Is there a `?status=active` filter or similar?
3. Should the build plugin pin to a specific version, or always pull the latest active?

### 4. Error Responses

For build-time validation, we need predictable error responses:

| Scenario | Expected Response |
|----------|-------------------|
| Asset exists | `200 OK` with content |
| Asset not found | `404 Not Found` with JSON error body |
| Asset exists but not active | Your recommendation — 404? 403? 200 with status field? |
| Server error | `5xx` with JSON error body |

Our plugin will **fail the build** with an actionable error message on any non-200 response for a referenced asset.

### 5. Health Check

We'd like to verify the metadata server is reachable before starting asset resolution (to give a clear error early rather than timing out on the first asset request).

Does the server expose a health endpoint? We'd use `HEAD /health` or `GET /health` — any lightweight endpoint returning a 2xx is fine.

---

## Summary of Requested API Surface

| Endpoint | Priority | Purpose |
|----------|----------|---------|
| `GET /api/decisions?namespace={ns}&name={name}&status=active` | **Critical** | Resolve `dmn://` URI to `decisionId` |
| `GET /api/decisions/{decisionId}/export?format=dmn` | **Critical** | Download original DMN XML |
| `GET /api/mappings/{mappingId}/export?format=jolt` | **Critical** | Download original Jolt spec |
| `GET /health` | Nice to have | Pre-flight connectivity check |

---

## Timeline

We are in the specification phase for the Gradle plugin. Implementation will begin once we have agreement on the API contract. We're flexible on endpoint naming and structure — the key requirement is the ability to:

1. **Resolve** a `dmn://` URI (namespace + model name) to a server-side identifier
2. **Download** the original artifact files (DMN XML, Jolt spec) — not the parsed JSON representations
3. **Fail deterministically** when an asset is missing (404)

Happy to schedule a call to discuss or iterate on this async. We can also share our full [Phase 3 Plugin Specification](0007-phase3-plugin-spec.md) for additional context.
