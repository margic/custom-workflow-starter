# Feature Plan: End-to-End DMN Decision in Hello World Workflow

**Date:** March 23, 2026  
**Updated:** March 24, 2026 — revised for self-contained build + runtime registration architecture  
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
| 2 | **Metadata Server** | Decision stored and managed as the governance record of truth |
| 3 | **Local DMN authoring** | DMN XML authored (via Copilot or MCP export) and committed to `src/main/resources/` |
| 4 | **Gradle Plugin — generateKogitoSources** | Kogito codegen processes the `.dmn` file alongside the updated `.sw.json` |
| 5 | **Codegen Extensions — DmnFunctionTypeHandler** | Emits `WorkItemNode` with `workName("dmn")`, `DmnNamespace`, `ModelName` params |
| 6 | **Spring Boot Starter — DmnWorkItemHandler** | Evaluates the DMN decision at runtime using `DecisionModels` |
| 7 | **Auto-Configuration** | `@ConditionalOnBean(DecisionModels.class)` activates now that DMN is present |
| 8 | **drools-decisions-spring-boot-starter** | Provides `DecisionModels` bean and DMN runtime auto-configuration |
| 9 | **Runtime Registration** | On startup, starter publishes catalog to metadata server for observability |
| 10 | **End-to-end workflow** | POST request → workflow engine → DMN evaluation → greeting returned |

## 3. Preconditions

Before starting, verify:

- [x] Dev container is running
- [x] Metadata server is up at `http://metadata-platform:3001` inside container (health check: 200 OK)
- [x] MCP server is connected in VS Code — **fixed:** URL changed from `localhost:3001` to `metadata-platform:3001` in `.vscode/mcp.json`
- [x] MCP SSE transport works — VS Code falls back from Streamable HTTP to legacy SSE (cosmetic 404, then 19 tools discovered)
- [ ] DMN file committed to `src/main/resources/` (restore `greeting-decision.bak` → `greeting-decision.dmn`)
- [ ] `application.yml` configured with `anax.metadata-server.url` for runtime registration
- [ ] Current hello-world workflow executes: `POST http://localhost:8085/hello-world` → greeting returned
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

**Developer action:** Ask Copilot to create a DMN decision on the metadata server AND commit the DMN XML locally.

> **Design change (March 2026):** The architecture no longer fetches DMN files from
> the metadata server at build time. Builds are self-contained — DMN files must be
> committed to `src/main/resources/`. The metadata server is the governance record
> of truth; the local file is the build artifact. Both must be kept in sync.

**Phase A — Create governance record via MCP:**

Copilot invoked MCP `create_decision` and created:

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

**Phase B — Commit DMN XML locally:**

The DMN file must be committed to the project for self-contained builds. Options:
1. **Copilot authors the DMN XML** directly based on the decision specification
2. **Export from metadata server** via `GET /api/decisions/{id}/export?format=dmn` (if available)
3. **Developer writes it manually** (least preferred)

The file is committed as `src/main/resources/greeting-decision.dmn`.

> **Current state:** The DMN XML exists as `greeting-decision.bak` — restore it to `.dmn`.

**Validated:**
- ✅ MCP write tools work
- ✅ Metadata server creates the decision correctly
- ✅ Decision ID auto-derived from name (`greeting-decision`)
- ✅ DMN XML committed locally for self-contained build

---

### Step 3: Activate the Decision ✅ COMPLETE (governance only)

**Developer action:** Promote the decision from `draft` to `active` on the metadata server.

> **Design change:** Activation is a **governance concern only** — it marks the
> decision as production-ready in the metadata server's registry. It does NOT
> gate the build. The build reads from `src/main/resources/`, not the server.

**What should happen:**
- Copilot invokes MCP `update_decision(decisionId: "greeting-decision", status: "active")`
- Verify: `GET /api/decisions?namespace=com.example.decisions&name=Greeting Decision&status=active` returns 1 result
- At runtime, when the starter registers its catalog, the metadata server can compare the registered DMN model against its `active` record for drift detection

**What we're validating:**
- Status lifecycle works (draft → active)
- Governance record is consistent with the committed local file
- Foundation for runtime drift detection

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

### Step 5: Configure Runtime Registration *(NEW)*

**Developer action:** Add metadata server URL to `application.yml` for runtime catalog registration.

> **Design change:** There is no build-time metadata server configuration. The
> `anaxKogito { metadataServerUrl }` extension property has been removed. Instead,
> the starter registers its catalog with the metadata server at runtime.

**Change in `anax-kogito-sample/src/main/resources/application.yml`:**

```yaml
anax:
  metadata-server:
    url: http://metadata-platform:3001
```

**Also: Remove `metadataServerUrl` from `build.gradle`** if still present:

```groovy
// REMOVE this block — no longer used
anaxKogito {
    metadataServerUrl = ...
}
```

**What we're validating:**
- Runtime registration is configured via `application.yml`, not `build.gradle`
- Build succeeds without any metadata server configuration

---

### Step 6: Build — Generate + Compile

**Developer action:** Run the Gradle build.

```bash
# Step 6a: Generate Kogito sources (includes DMN codegen)
./gradlew :anax-kogito-sample:generateKogitoSources

# Step 6b: Full build
./gradlew :anax-kogito-sample:build
```

> **Design change:** There is no `resolveGovernanceAssets` step. The DMN file is
> already committed to `src/main/resources/`. The build is fully self-contained.

**What should happen in 6a:**
- Kogito codegen discovers `decisions` generator (was previously skipped — no `.dmn` files)
- Decisions generator is now **enabled** (`.dmn` file present in `src/main/resources/`)
- Generates DMN evaluation Java classes
- Process generator picks up updated `.sw.json` with `dmn://` function reference
- `DmnFunctionTypeHandler` SPI emits `WorkItemNode` with `workName("dmn")`

**What we're validating:**
- **No metadata server dependency at build time** — build succeeds with server down
- Codegen discovers and processes the committed DMN model
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

**7a — Verify runtime registration:**

On startup (`ApplicationReadyEvent`), the starter should POST its catalog to the metadata server:
- Check application logs for: `"Registering catalog with metadata server at http://metadata-platform:3001"`
- If registration succeeds: `"Catalog registered successfully"`
- If registration fails (server not ready, endpoint not yet implemented): `"Catalog registration failed (non-fatal)"` — app continues normally

```bash
# Verify catalog endpoint is available
curl http://localhost:8085/anax/catalog | jq .
```

The catalog should list the `dmn://com.example.decisions/Greeting Decision` model, the `anax://greetingService/*` beans, and the `hello-world` workflow.

**7b — Verify workflow execution:**

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
- DMN decision model loads from committed `.dmn` in `src/main/resources/`
- Decision evaluation produces correct output
- Workflow completes with greeting from DMN (not from the old GreetingService hardcoded logic)
- Runtime registration fires on startup (fire-and-forget — failure doesn't block the app)
- Catalog endpoint exposes the full asset inventory

**Verification:**
- Run the request multiple times, observe the greeting flips as the clock minute changes
- Or: check the minute when you send the request and confirm the greeting matches

---

## 5. Success Criteria

| Criterion | How to Verify |
|-----------|---------------|
| MCP discovery works | Copilot returns decision list from metadata server |
| MCP authoring works | Decision created via Copilot, visible in metadata server |
| DMN committed locally | `src/main/resources/greeting-decision.dmn` exists and matches governance record |
| Build is self-contained | `./gradlew build` succeeds **without** metadata server running |
| Codegen processes DMN | `generateKogitoSources` log shows `decisions` generator enabled, produces Java files |
| Build succeeds | `./gradlew build` exits 0 |
| DMN handler activates | Boot log shows `DmnWorkItemHandler` registered |
| Runtime registration fires | Boot log shows catalog registration attempt (success or graceful failure) |
| Catalog endpoint works | `GET /anax/catalog` returns JSON with DMN model listed |
| Correct greeting returned | POST returns `"Hello, it's even!"` or `"Hello, it's odd!"` based on current minute |

## 6. Known Gaps & Risks

> **Note:** We own both repos (custom-workflow-starter and metadata management platform). Gaps marked with **[FIX]** are tasks we implement ourselves on the metadata server — not cross-team dependencies.

> **Design change (March 2026):** The architecture no longer uses `resolveGovernanceAssets`
> to fetch DMN files at build time. Builds are self-contained. Several gaps from the
> original plan are now moot.

| # | Gap | Impact | Resolution |
|---|-----|--------|------------|
| ~~1~~ | ~~DMN XML generation from structured decision~~ | ~~Blocks Step 6a~~ | **MOOT** — DMN XML is committed locally by the developer/Copilot. The metadata server's export capability is a convenience, not a build dependency |
| ~~2~~ | ~~Decision activation via MCP~~ | ~~Blocks Step 3~~ | **RESOLVED** — `update_decision` MCP tool exists. Activation is governance-only, does not gate builds |
| 3 | **DMN file naming** — committed file must be discovered by Kogito codegen | Could cause codegen to miss the file | **[VERIFY]** during Step 6. Kogito scans `src/main/resources/` for `*.dmn` files |
| 4 | **Workflow data flow** — DMN decision output keys must match what the workflow expects in `workflowdata` | Greeting might not propagate correctly | **[VERIFY]** during Step 7. May need `actionDataFilter` in `.sw.json` |
| 5 | **GreetingService change** — adding `getCurrentMinute()` method; the current `greet()` method becomes unused or needs removal | Minor — cleanup concern | Refactor incrementally during Step 4 |
| ~~6~~ | ~~Decision status filtering in resolveGovernanceAssets~~ | ~~404 or empty result~~ | **MOOT** — no build-time server dependency |
| 7 | **Runtime registration endpoint** — `POST /api/registrations` does not yet exist on the metadata server | Registration will gracefully fail (fire-and-forget) | **[FILED]** [margic/codespaces-react#15](https://github.com/margic/codespaces-react/issues/15) — app starts normally regardless |
| 8 | **Local/server drift** — committed DMN file may diverge from metadata server record | Governance risk, not build risk | Runtime registration + drift detection will catch this once endpoint is implemented |

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
│  │           │ <─── decisionId + draft ──── │ (governance rec) │    │
│  │           │                               └──────────────────┘    │
│  │           │     Commit DMN XML          ┌──────────────────┐    │
│  │           │ ──── Copilot / manual ────> │ src/main/resources│    │
│  └──────────┘                               │ greeting-dec.dmn │    │
│                                              └──────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│  Step 3: Activate Decision (governance only)                         │
│  ┌──────────┐     MCP or REST              ┌──────────────────┐    │
│  │ Developer │ ──── status=active ────────> │ Metadata Server  │    │
│  └──────────┘                               └──────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│  Step 4: Update Workflow                                             │
│  ┌──────────┐     Edit with Copilot        ┌──────────────────┐    │
│  │ Developer │ ──── hello-world.sw.json ──> │ Local Files      │    │
│  │           │ ──── GreetingService.java ─> │                  │    │
│  └──────────┘                               └──────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│  Step 5: Configure Runtime Registration                              │
│  ┌──────────┐                               ┌──────────────────┐    │
│  │ Developer │ ──── application.yml ──────> │ anax.metadata-   │    │
│  │           │      (remove build.gradle    │ server.url       │    │
│  │           │       metadataServerUrl)      └──────────────────┘    │
│  └──────────┘                                                        │
├─────────────────────────────────────────────────────────────────────┤
│  Step 6: Build (self-contained — no server dependency)               │
│  ┌──────────────────┐                                                │
│  │ Gradle Plugin     │                                                │
│  │                    │  Reads src/main/resources/*.dmn               │
│  │ generateSources ──>│  Kogito codegen (DMN + process)              │
│  │ compile ──────────>│  .class files                                │
│  └──────────────────┘                                                │
├─────────────────────────────────────────────────────────────────────┤
│  Step 7: Run & Verify                                                │
│  ┌──────────┐  POST /hello-world  ┌────────────────────────────┐   │
│  │ Developer │ ─────────────────> │ Spring Boot App (8085)      │   │
│  │           │ <── greeting ───── │  → GetCurrentMinute (anax)  │   │
│  └──────────┘                     │  → EvaluateGreeting (dmn)   │   │
│                                    │  → LogResult (sysout)       │   │
│                                    │                              │   │
│  On startup (ApplicationReadyEvent):                              │   │
│  ┌────────────────────────────┐   POST /api/registrations         │   │
│  │ MetadataServerRegistration │ ──────────────────────────────>  │   │
│  │ Service (fire-and-forget)  │   ┌──────────────────┐           │   │
│  └────────────────────────────┘   │ Metadata Server  │           │   │
│                                    │ (observability)  │           │   │
│                                    └──────────────────┘           │   │
│                                    └────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

## 8. After the Test

If all steps pass, this validates:
- The **full Copilot-assisted developer experience** for adding governed decisions to workflows
- The **self-contained build model** — no metadata server required at build time
- The **Author → Build → Register lifecycle** works end-to-end
- The **starter abstracts all Kogito complexity** — developer never touches `org.kie.kogito` directly
- **Runtime registration** fires on startup (graceful failure until `POST /api/registrations` is implemented on the metadata server)

**Next actions after success:**
- Commit the updated workflow + bean changes + restored `.dmn` file
- Remove `metadataServerUrl` from `build.gradle` (and `resolveGovernanceAssets` task if still registered)
- Implement `MetadataServerRegistrationService` in the starter (Prompts 2.12–2.14 in implementation plan)
- Document any gaps discovered and file issues
- Consider adding a `map://` function to the same workflow (tests the Jolt transformation path)
- Write a reproducible integration test tagged `@Tag("devcontainer")`
