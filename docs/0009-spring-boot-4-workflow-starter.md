# ADR 0009 — YAML-Driven Metadata Workflow Engine (Spring Boot 4)

**Status:** Draft  
**Supersedes:** ADR 0006  
**Issue:** [#4](https://github.com/margic/custom-workflow-starter/issues/4)

---

## Context

The existing starter (ADR 0006) used Kogito 10.1.0 to parse CNCF Serverless Workflow `.sw.json`
definitions and generate Spring Boot REST endpoints at build time. Kogito 10.1.0 is incompatible
with Spring Boot 4.0 / Spring Framework 7. No compatible Kogito release exists.

This ADR replaces Kogito with a runtime-interpreted YAML DSL backed by Spring Statemachine,
with state persistence in Cosmos DB and event-driven orchestration via Azure Event Hubs and the
CloudEvents 1.0 standard.

---

## Decision

Replace the Kogito-based codegen model with a **runtime YAML engine** that:

- Parses workflow YAML at application startup and constructs `StateMachineModel` instances dynamically
- Persists `StateMachineContext` to Cosmos DB keyed by `controlRecordId`
- Consumes CloudEvents from an internal Azure Event Hub (KEDA-scaled consumers)
- Invokes coarse-grained skills via `skill://` URIs through a Skill SDK
- Emits state change CloudEvents to a broadcast Event Hub for downstream consumers and WebSocket UI push

---

## Architecture

### Event Flow

```
External domain event
  → Event Bridge (validates + translates to CloudEvent)
  → Internal Workflow Hub
  → KEDA consumer
  → engine rehydrates state machine from Cosmos DB
  → transition + skill invocation
  → Broadcast Hub
  → WebSocket Gateway / downstream consumers
```

### Correlation

`controlRecordId` is the partition key on both Cosmos DB and Event Hub. All CloudEvents for a
workflow instance carry `subject: controlRecordId` and `partitionkey: controlRecordId`,
guaranteeing ordered, non-concurrent processing per instance.

### Async Skill Pattern (201 Accepted)

1. Transition fires → engine invokes skill via SDK (POST with `controlRecordId` + mapped payload)
2. Skill returns **201 Accepted** → engine persists context, emits broadcast CloudEvent, parks
3. Skill completes → external system emits domain event → bridge translates → Internal Hub
4. KEDA consumer claims event, extracts `subject` → Cosmos DB lookup → rehydrate → transition

---

## CloudEvents Envelope Standard

All messages flowing in and out of the workflow engine adhere to the CloudEvents 1.0 specification.
The `partitionkey` extension is enforced and must match `controlRecordId`.

```json
{
  "specversion": "1.0",
  "id": "a89b-43c2-9e8a-1132a",
  "source": "/bridge/doc-intelligence",
  "type": "EXTRACTION_COMPLETED",
  "subject": "CR-99482-11A",
  "partitionkey": "CR-99482-11A",
  "time": "2026-04-15T18:44:00Z",
  "data": {
    "extractionDataId": "ext-doc-8821"
  }
}
```

---

## DSL Shape

Workflow definitions are YAML files committed to `src/main/resources/workflows/`. Schema version
is controlled via the `version` field on the `workflow` block.

### Key Constructs

| Construct | Purpose |
|---|---|
| `workflow` | Root — id, name, version, description, initial_state |
| `states` | Nodes of the state machine |
| `events` | Expected CloudEvent `type` values that trigger transitions |
| `transitions` | Rules connecting states: source, event, target, action |
| `action` | Executed on transition: type `skill`, `uri`, `payload_mapping` |

### State Types

| Type | Behaviour |
|---|---|
| `INITIAL` | Starting state when a new control record is created |
| `ACTIVE_WAIT` | Engine parks after invoking skill; awaits wakeup CloudEvent |
| `PARKED` | Engine emits to Broadcast Hub; awaits human or external trigger |
| `END` | Terminal state |

### Payload Mapping JSONPath Sources

| Prefix | Source |
|---|---|
| `$.extendedState.*` | Values read from Cosmos DB extended state for this control record |
| `$.event.data.*` | Values extracted from the triggering CloudEvent `data` block |

### Example DSL (Legal Order Processing)

```yaml
workflow:
  id: "legal-order-onboarding"
  name: "Legal Order Initial Automation"
  version: "1.0.0"
  description: "Automates doc extraction, validation, and party search before HITL."
  initial_state: "ORDER_CAPTURED"

  states:
    - name: "ORDER_CAPTURED"
      type: "INITIAL"
    - name: "EXTRACTING_DOCUMENT"
      type: "ACTIVE_WAIT"
    - name: "VALIDATING_EXTRACTION"
      type: "ACTIVE_WAIT"
    - name: "SEARCHING_PARTIES"
      type: "ACTIVE_WAIT"
    - name: "READY_FOR_HITL"
      type: "PARKED"
    - name: "HANDED_OFF_TO_LEGACY"
      type: "END"

  events:
    - name: "START_PROCESSING"
    - name: "EXTRACTION_COMPLETED"
    - name: "VALIDATION_COMPLETED"
    - name: "PARTY_SEARCH_COMPLETED"
    - name: "HITL_APPROVED"

  transitions:
    - source: "ORDER_CAPTURED"
      event: "START_PROCESSING"
      target: "EXTRACTING_DOCUMENT"
      action:
        type: "skill"
        uri: "skill://doc-intelligence/extract-legal-order"
        payload_mapping:
          documentId: "$.extendedState.capturedDocumentId"
          location: "$.extendedState.capturedDocumentLocation"

    - source: "EXTRACTING_DOCUMENT"
      event: "EXTRACTION_COMPLETED"
      target: "VALIDATING_EXTRACTION"
      action:
        type: "skill"
        uri: "skill://legal-rules/validate-extraction"
        payload_mapping:
          extractionDataId: "$.event.data.extractionDataId"

    - source: "VALIDATING_EXTRACTION"
      event: "VALIDATION_COMPLETED"
      target: "SEARCHING_PARTIES"
      action:
        type: "skill"
        uri: "skill://customer-mdm/party-account-search"
        payload_mapping:
          parties: "$.extendedState.validatedParties"

    - source: "SEARCHING_PARTIES"
      event: "PARTY_SEARCH_COMPLETED"
      target: "READY_FOR_HITL"
      # No action — engine emits state change CloudEvent to Broadcast Hub.
      # UI reacts via WebSocket; human submits HITL_APPROVED via API trigger.

    - source: "READY_FOR_HITL"
      event: "HITL_APPROVED"
      target: "HANDED_OFF_TO_LEGACY"
      action:
        type: "skill"
        uri: "skill://legacy-integration/submit-order"
        payload_mapping:
          reviewerComments: "$.event.data.comments"
```

---

## Starter Responsibilities

| Concern | Owner |
|---|---|
| YAML DSL parser + `StateMachineModel` builder | Starter |
| Spring Statemachine engine wiring | Starter |
| Cosmos DB state persistence | Starter |
| Internal Event Hub consumer (KEDA-scaled) | Starter |
| Broadcast Event Hub producer | Starter |
| Skill SDK (`skill://` invocation + 201 handling) | Starter |
| CloudEvents envelope assembly/validation | Starter |
| Metadata server catalog registration on startup | Starter (carries over from ADR 0006) |
| Event Bridges (external → internal translation) | Consumer application |
| WebSocket Gateway | Consumer application |

---

## Open Questions

> These must be resolved before this ADR moves to **Accepted**.

1. **Skill SDK contract** — exact HTTP request shape, authentication, timeout/retry policy
2. **YAML storage** — committed to `src/main/resources/` only, or also fetchable/overridable from the metadata server at startup?
3. **Error handling** — timeout on `ACTIVE_WAIT` states, dead-lettering strategy — in scope for v1?
4. **Guard conditions** — conditional transitions based on `$.extendedState.*` values — in scope for v1?
5. **Metadata server integration** — catalog registration carries over from ADR 0006; confirm `skill://` URIs are registered as a new asset type alongside `dmn://`, `anax://`, `map://`
