# Feature Plan: End-to-End DMN Decision in Hello World Workflow

**Date:** March 23, 2026  
**Updated:** March 24, 2026  
**Branch:** `feature/spring-boot-starter`  
**Objective:** Validate the full Copilot-assisted developer experience by adding a governed DMN decision to the existing hello-world workflow, exercising every system component end-to-end.  
**Metadata Server Repo:** `margic/codespaces-react`  
**Metadata Server Image:** `margic/anax-metadata-platform:v0.3.3`

---

## 1. Scenario

> **Business requirement:** The hello-world greeting should change based on the current time. If the current minute is **even**, the greeting is `"Hello, it's even!"`. If the current minute is **odd**, the greeting is `"Hello, it's odd!"`.

This requirement is deliberately simple so we can focus on the **developer experience and system integration**, not business logic complexity.

## 2. What This Tests

This single scenario exercises every component in the architecture:

| # | Component | What Gets Tested |
|---|-----------|------------------|
| 1 | **MCP Server** | Copilot discovers existing assets, creates a new decision via MCP tools |
| 2 | **Metadata Server** | Decision stored, activated, DMN XML exported via REST API |
| 3 | **Gradle Plugin — resolveGovernanceAssets** | Parses `dmn://` URI from `.sw.json`, fetches DMN XML from metadata server |
| 4 | **Gradle Plugin — generateKogitoSources** | Kogito codegen processes the `.dmn` file alongside the updated `.sw.json` |
| 5 | **Codegen Extensions — DmnFunctionTypeHandler** | Emits `WorkItemNode` with `workName("dmn")`, `DmnNamespace`, `ModelName` params |
| 6 | **Spring Boot Starter — DmnWorkItemHandler** | Evaluates the DMN decision at runtime using `DecisionModels` |
| 7 | **Auto-Configuration** | `@ConditionalOnBean(DecisionModels.class)` activates now that DMN is present |
| 8 | **drools-decisions-spring-boot-starter** | Provides `DecisionModels` bean and DMN runtime auto-configuration |
| 9 | **End-to-end workflow** | POST request → workflow engine → DMN evaluation → greeting returned |

## 3. Preconditions

Before starting, verify:

- [x] Dev container is running
- [x] Metadata server is up at `http://metadata-platform:3001` inside container (health check: 200 OK)
- [x] MCP server is connected in VS Code — **fixed:** URL changed from `localhost:3001` to `metadata-platform:3001` in `.vscode/mcp.json`
- [x] MCP SSE transport works — VS Code falls back from Streamable HTTP to legacy SSE (cosmetic 404, then 19 tools discovered)
- [ ] Current hello-world workflow executes: `POST http://localhost:8085/hello-world` → `{"greeting": "Hello, Kogito!"}`
- [ ] Branch `feature/spring-boot-starter` is clean (commit pending changes first)

## 4. Developer Journey

The developer drives each step. Copilot assists at every stage. This is the experience we're validating.

**Personas:**
- **Paul** — Principal engineer, owns both repos, drives the test
- **Developer** — The test persona (played by Paul) experiencing the Copilot-assisted workflow
- **Copilot** — AI assistant, primary interface for the developer; MCP is preferred over REST

---

### Step 1: Discovery — "What decisions exist?" ✅ COMPLETE

**Developer action:** Ask Copilot to search the metadata server for existing decisions.

**Result:** Copilot invoked MCP `list_decisions` and returned 4 existing decisions:
- `draft-test-decision` (draft) — test data
- `legal-order-routing` (active) — legal order domain
- `risk-assessment` (active) — risk domain
- `subpoena-compliance` (active) — subpoena domain

**Conclusion:** No existing decision matches the even/odd greeting requirement → proceed to author one.

**Validated:**
- ✅ MCP SSE connection is live
- ✅ Copilot can invoke discovery tools
- ✅ Metadata server responds with structured results

**What should happen:**
- Copilot invokes MCP tool `list_decisions` (or `search_assets`) against the metadata server
- Returns the current inventory (likely empty or unrelated decisions)
- Developer confirms: no existing decision fits the even/odd greeting requirement → need to author one

**What we're validating:**
- MCP SSE connection is live
- Copilot can invoke discovery tools
- Metadata server responds with structured results

---

### Step 2: Author the Decision — "Create a greeting decision" ✅ COMPLETE

**Developer action:** Ask Copilot to create a DMN decision for the even/odd greeting.

**Result:** Copilot invoked MCP `create_decision` and created:

| Property | Value |
|----------|-------|
| Decision ID | `greeting-decision` |
| Name | `Greeting Decision` |
| Namespace | `com.example.decisions` |
| Status | `draft` (forced by server) |
| Hit Policy | `FIRST` |
| Input | `currentMinute` (number) — the current minute of the hour |
| Output | `greeting` (string) — the greeting message |
| Rule 1 | If `currentMinute` is even → `"Hello, it's even!"` |
| Rule 2 | If `currentMinute` is odd → `"Hello, it's odd!"` |

**URI for workflow:** `dmn://com.example.decisions/Greeting Decision`

**Validated:**
- ✅ MCP write tools work
- ✅ Metadata server creates the decision correctly
- ✅ Decision ID auto-derived from name (`greeting-decision`)

**Open question — DMN XML generation:**
The `resolveGovernanceAssets` task needs the **original DMN XML** from the export endpoint (`GET /api/decisions/{id}/export?format=dmn`). Two possibilities:
1. The metadata server **generates** DMN XML from the structured representation (inputs, outputs, rules, hit policy) — ideal
2. The metadata server requires a **separate DMN file upload** — we'd need to author the XML manually and upload it

**Status:** v0.3.2 of the metadata server addresses this. To be verified after container rebuild.

---

### Step 3: Activate the Decision — 🔄 BLOCKED → UNBLOCKED (v0.3.3)

**Developer action:** Promote the decision from `draft` to `active`.

The Gradle plugin's `resolveGovernanceAssets` filters by `status=active`. A draft decision will 404.

**Gaps discovered (v0.3.2):**
1. No `update_decision` MCP tool exists — filed as **[margic/codespaces-react#4](https://github.com/margic/codespaces-react/issues/4)**
2. No REST `PATCH /api/decisions/:id` endpoint exists — filed as **[margic/codespaces-react#6](https://github.com/margic/codespaces-react/issues/6)**

**Resolution:** Metadata server v0.3.3 expected to add update support. Container rebuild required.

**What should happen (after v0.3.3):**
- Copilot invokes MCP `update_decision(decisionId: "greeting-decision", status: "active")`
- Verify: `GET /api/decisions?namespace=com.example.decisions&name=Greeting Decision&status=active` returns 1 result

**What we're validating:**
- Status lifecycle works (draft → active)
- Search-by-namespace+name returns the activated decision
- Export endpoint returns DMN XML for the active decision

---

### Step 4: Update the Workflow

**Developer action:** Modify `hello-world.sw.json` to call the DMN decision.

**Target workflow design:**

```
[Start] → [GetCurrentMinute] → [EvaluateGreeting] → [LogResult] → [End]
```

1. **GetCurrentMinute** — `anax://greetingService/getCurrentMinute` — returns `{ "currentMinute": 42 }`
2. **EvaluateGreeting** — `dmn://com.example.decisions/Greeting Decision` — evaluates DMN, returns `{ "greeting": "Hello, it's even!" }`
3. **LogResult** — `sysout` — logs the greeting

**Changes required:**

| File | Change |
|------|--------|
| `hello-world.sw.json` | Add `dmn://` function definition, add `GetCurrentMinute` state, add `EvaluateGreeting` state, rewire transitions |
| `GreetingService.java` | Add `getCurrentMinute()` method that returns the current minute |

**What we're validating:**
- Developer can author `dmn://` function references in `.sw.json` with Copilot assistance
- Copilot uses catalog awareness / instructions to suggest the correct URI format

---

### Step 5: Configure the Build for Real Metadata Server

**Developer action:** Switch from stub to real metadata server.

**Change in `anax-kogito-sample/build.gradle`:**

```groovy
anaxKogito {
    metadataServerUrl = providers.environmentVariable('METADATA_SERVER_URL')
            .orElse('http://metadata-platform:3001')  // was 'stub'
}
```

Or set the environment variable:
```bash
export METADATA_SERVER_URL=http://metadata-platform:3001
```

**What we're validating:**
- Plugin configuration accepts the real server URL
- Environment variable override works

---

### Step 6: Build — Resolve + Generate

**Developer action:** Run the Gradle build.

```bash
# Step 6a: Resolve governance assets from metadata server
./gradlew :anax-kogito-sample:resolveGovernanceAssets

# Step 6b: Generate Kogito sources (includes DMN codegen)
./gradlew :anax-kogito-sample:generateKogitoSources

# Step 6c: Full build
./gradlew :anax-kogito-sample:build
```

**What should happen in 6a:**
- Plugin parses `hello-world.sw.json`
- Finds `dmn://com.example.decisions/Greeting Decision`
- Calls `GET /api/decisions?namespace=com.example.decisions&name=Greeting Decision&status=active`
- Gets `decisionId`, calls `GET /api/decisions/{decisionId}/export?format=dmn`
- Writes `.dmn` file to `build/generated/resources/kogito/`
- Also finds `anax://greetingService/getCurrentMinute` → logs "local Spring bean (no fetch)"

**What should happen in 6b:**
- Kogito codegen discovers `decisions` generator (was previously skipped — no `.dmn` files)
- Decisions generator is now **enabled** (`.dmn` file present in generated resources)
- Generates DMN evaluation Java classes
- Process generator picks up updated `.sw.json` with `dmn://` function reference
- `DmnFunctionTypeHandler` SPI emits `WorkItemNode` with `workName("dmn")`

**What we're validating:**
- Metadata server HTTP client works end-to-end
- DMN file is fetched and placed correctly for codegen
- Codegen discovers and processes the DMN model
- DmnFunctionTypeHandler produces correct work-item node
- Build succeeds with no errors

**Potential issues to watch for:**
- DMN file naming — must match what Kogito codegen expects
- DMN namespace/name in XML must match the URI components
- Codegen classpath must include `kogito-codegen-decisions` (already confirmed: it does)

---

### Step 7: Run & Verify

**Developer action:** Boot the app and test.

```bash
./gradlew :anax-kogito-sample:bootRun
```

```bash
# Test the workflow
curl -X POST http://localhost:8085/hello-world \
  -H 'Content-Type: application/json' \
  -d '{"name": "World"}'
```

**What should happen:**
- Application boots with `DmnWorkItemHandler` registered (log: `DecisionModels` bean present)
- POST triggers workflow:
  1. `GetCurrentMinute` → `anax://greetingService/getCurrentMinute` → `{ "currentMinute": <N> }`
  2. `EvaluateGreeting` → `dmn://com.example.decisions/Greeting Decision` → DMN evaluation → `{ "greeting": "Hello, it's even!" }` or `"Hello, it's odd!"`
  3. `LogResult` → sysout logs the greeting
- Response contains the greeting

**What we're validating:**
- `DmnWorkItemHandler` activates (was previously conditional-skipped)
- DMN decision model loads from generated code
- Decision evaluation produces correct output
- Workflow completes with greeting from DMN (not from the old GreetingService hardcoded logic)

**Verification:**
- Run the request multiple times, observe the greeting flips as the clock minute changes
- Or: check the minute when you send the request and confirm the greeting matches

---

## 5. Success Criteria

| Criterion | How to Verify |
|-----------|---------------|
| MCP discovery works | Copilot returns decision list from metadata server |
| MCP authoring works | Decision created via Copilot, visible in metadata server |
| DMN export works | `resolveGovernanceAssets` downloads `.dmn` file |
| Codegen processes DMN | `generateKogitoSources` log shows `decisions` generator enabled, produces Java files |
| Build succeeds | `./gradlew build` exits 0 |
| DMN handler activates | Boot log shows `DmnWorkItemHandler` registered |
| Correct greeting returned | POST returns `"Hello, it's even!"` or `"Hello, it's odd!"` based on current minute |

## 6. Known Gaps & Risks

> **Note:** We own both repos (custom-workflow-starter and metadata management platform). Gaps marked with **[FIX]** are tasks we implement ourselves on the metadata server — not cross-team dependencies.

| # | Gap | Impact | Resolution |
|---|-----|--------|------------|
| 1 | **DMN XML generation from structured decision** — unclear if metadata server auto-generates DMN XML from the `create_decision` MCP tool output, or requires a separate file upload | Blocks Step 6a — no DMN XML to export | **[DISCOVER]** Test during Step 2. If the metadata server doesn't auto-generate DMN XML: **[FIX]** either add DMN generation to the metadata server, or author DMN XML with Copilot and upload via REST |
| 2 | **Decision activation via MCP** — the MCP spec defines `create_decision` but not `update_decision` or `activate_decision` | Blocks Step 3 — can't promote draft → active | **[FIXED]** Filed [margic/codespaces-react#4](https://github.com/margic/codespaces-react/issues/4). Resolved in metadata server v0.3.2 — adds `update_decision` MCP tool |
| 3 | **DMN file naming** — `resolveGovernanceAssets` names the file `{decisionId}.dmn`; Kogito codegen must discover it | Could cause codegen to miss the file | **[VERIFY]** during Step 6. Fix naming in plugin if needed |
| 4 | **Workflow data flow** — DMN decision output keys must match what the workflow expects in `workflowdata` | Greeting might not propagate correctly | **[VERIFY]** during Step 7. May need `actionDataFilter` in `.sw.json` |
| 5 | **GreetingService change** — adding `getCurrentMinute()` method; the current `greet()` method becomes unused or needs removal | Minor — cleanup concern | Refactor incrementally during Step 4 |
| 6 | **Decision status filtering** — `resolveGovernanceAssets` sends `?status=active`; metadata server must support this query parameter | 404 or empty result if not supported | **[VERIFY]** during Step 1 discovery. **[FIX]** on metadata server if not supported |

## 7. Execution Sequence

```
┌─────────────────────────────────────────────────────────────────────┐
│  Step 1: Discovery                                                   │
│  ┌──────────┐     MCP: list_decisions      ┌──────────────────┐    │
│  │ Developer │ ──── Copilot Agent ────────> │ Metadata Server  │    │
│  │ (VS Code) │ <─── decision list ───────── │ (port 3001)      │    │
│  └──────────┘                               └──────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│  Step 2: Author Decision                                             │
│  ┌──────────┐     MCP: create_decision     ┌──────────────────┐    │
│  │ Developer │ ──── Copilot Agent ────────> │ Metadata Server  │    │
│  │           │ <─── decisionId + draft ──── │                  │    │
│  └──────────┘                               └──────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│  Step 3: Activate Decision                                           │
│  ┌──────────┐     REST or UI               ┌──────────────────┐    │
│  │ Developer │ ──── PATCH status=active ──> │ Metadata Server  │    │
│  └──────────┘                               └──────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│  Step 4: Update Workflow                                             │
│  ┌──────────┐     Edit with Copilot        ┌──────────────────┐    │
│  │ Developer │ ──── hello-world.sw.json ──> │ Local Files      │    │
│  │           │ ──── GreetingService.java ─> │                  │    │
│  └──────────┘                               └──────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│  Step 5: Configure Build                                             │
│  ┌──────────┐                               ┌──────────────────┐    │
│  │ Developer │ ──── build.gradle ─────────> │ metadataServerUrl│    │
│  └──────────┘                               └──────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│  Step 6: Build                                                       │
│  ┌──────────────────┐                       ┌──────────────────┐    │
│  │ Gradle Plugin     │                       │ Metadata Server  │    │
│  │                    │  GET /api/decisions   │                  │    │
│  │ resolveAssets ─────┼─────────────────────>│                  │    │
│  │                    │  GET ../export?dmn    │                  │    │
│  │ generateSources ──>│  .dmn file           │                  │    │
│  │                    │                       └──────────────────┘    │
│  │ Kogito codegen ───>│  Java sources                                │
│  │ compile ──────────>│  .class files                                │
│  └──────────────────┘                                                │
├─────────────────────────────────────────────────────────────────────┤
│  Step 7: Run & Verify                                                │
│  ┌──────────┐  POST /hello-world  ┌────────────────────────────┐   │
│  │ Developer │ ─────────────────> │ Spring Boot App (8085)      │   │
│  │           │ <── greeting ───── │  → GetCurrentMinute (anax)  │   │
│  └──────────┘                     │  → EvaluateGreeting (dmn)   │   │
│                                    │  → LogResult (sysout)       │   │
│                                    └────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

## 8. After the Test

If all steps pass, this validates:
- The **full Copilot-assisted developer experience** for adding governed decisions to workflows
- The **metadata server → build → runtime** pipeline works end-to-end
- The **starter abstracts all Kogito complexity** — developer never touches `org.kie.kogito` directly
- The **three-phase model** (author in metadata server → resolve at build time → execute at runtime) is sound

**Next actions after success:**
- Commit the updated workflow + bean changes
- Document any gaps discovered and file issues
- Consider adding a `map://` function to the same workflow (tests the Jolt transformation path)
- Write a reproducible integration test tagged `@Tag("devcontainer")`
