# Memo: Metadata Server Integration Test Results & Requests

**From:** Custom Workflow Starter Team  
**To:** Metadata Management Platform Team  
**Date:** March 21, 2026  
**Subject:** API verification results, seed data requests, and minor fixes

---

## Summary

We ran a comprehensive API verification against the `margic/anax-metadata-platform:latest` image in our dev container. **The results are excellent — all critical endpoints work correctly.** The metadata team delivered on every functional requirement from our [integration memo](memo-metadata-server-integration.md).

**Score: 27 passed, 0 failed, 3 warnings** (warnings are minor field-naming differences, not blockers).

---

## 1. What's Working (Confirmed)

| Requirement | Result | Notes |
|---|---|---|
| `GET /health` | **PASS** | Returns `{"status":"ok"}` — good for pre-flight check |
| `GET /api/decisions?namespace=...&name=...&status=active` | **PASS** | Returns exactly 1 result for seeded decision; returns empty for nonexistent — this is the critical `dmn://` resolution endpoint |
| `GET /api/decisions/{id}/export?format=dmn` | **PASS** | Returns raw DMN XML, `Content-Type: application/xml`, proper `Content-Disposition` header with `.dmn` filename |
| `GET /api/mappings/{id}/export?format=jolt` | **PASS** | Returns valid Jolt spec as JSON array, `Content-Type: application/json` |
| 404 for missing assets | **PASS** | Both decisions and mappings return `404` with structured JSON error body `{"error":{"code":"NOT_FOUND","message":"..."}}` |
| Decision detail has `namespace` + `sourceContent` | **PASS** | Both `legal-order-routing` and `subpoena-compliance` have these fields populated with valid DMN XML |
| Status filtering | **PASS** | `?status=active` correctly filters the list |

Thank you — this is solid work and we can build our plugin client against it with confidence.

---

## 2. Minor Fixes Requested (Non-blocking)

These are cosmetic inconsistencies between the agreed spec and the actual API. They won't block us — we'll adapt our client — but fixing them would make the API more self-consistent and match the contract in our [Phase 3 spec](0007-phase3-plugin-spec.md).

### 2.1 List Response: `items` vs `data`

**Agreed spec:** Search/list responses return results in a `data` array with a nested `pagination` object:
```json
{ "data": [{ "decisionId": "legal-order-routing", ... }], "pagination": { "total": 1 } }
```

**Actual response:** Results are in an `items` array with flat pagination fields:
```json
{ "items": [{ "id": "legal-order-routing", ... }], "total": 2, "page": 1, "pageSize": 20 }
```

**Ask:** Could you either:
- **(a)** Rename `items` → `data` and nest pagination into `{ "pagination": { "total", "page", "pageSize" } }`, or
- **(b)** Confirm `items` + flat pagination is the intended contract so we update our spec to match

We're fine adapting to either convention — we just need one source of truth.

### 2.2 List Item `id` vs Detail `decisionId`

**List endpoint** (`GET /api/decisions`) returns items with an `id` field:
```json
{ "id": "legal-order-routing", "name": "Legal Order Routing", ... }
```

**Detail endpoint** (`GET /api/decisions/{id}`) returns the same value as `decisionId`:
```json
{ "decisionId": "legal-order-routing", "name": "Legal Order Routing", ... }
```

The same pattern applies for mappings (`id` in list, `mappingId` in detail).

**Ask:** Ideally both should use the same field name. Our preference would be `decisionId` / `mappingId` everywhere (list + detail) since it's more descriptive. But again, either convention is fine — we just need consistency documented.

---

## 3. Seed Data Request

The current seed data has **2 decisions** and **2 mappings**. This is a great start. To support our end-to-end integration tests and the sample application (`anax-kogito-sample`), we'd like the following additional seed data in the default image:

### 3.1 Additional Decision Needed: `risk-assessment`

Our spec's [Resolution Output Contract](0007-phase3-plugin-spec.md#24-resolution-output-contract) references a `risk-assessment` decision. This would give us a second `dmn://` URI to test multi-decision resolution.

| Field | Value |
|---|---|
| `decisionId` | `risk-assessment` |
| `name` | `Risk Assessment` |
| `namespace` | `com.anax.decisions` |
| `status` | `active` |
| `sourceContent` | A valid DMN XML file (can be minimal — a single-rule decision table is fine) |

**Suggested DMN content** (minimal but valid):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
             id="risk-assessment"
             name="Risk Assessment"
             namespace="com.anax.decisions">
  <decision id="decision_risk" name="Risk Assessment">
    <decisionTable id="dt_risk" hitPolicy="FIRST">
      <input id="input_amount" label="Order Amount">
        <inputExpression id="ie_amount" typeRef="number">
          <text>orderAmount</text>
        </inputExpression>
      </input>
      <input id="input_type" label="Order Type">
        <inputExpression id="ie_type" typeRef="string">
          <text>orderTypeCode</text>
        </inputExpression>
      </input>
      <output id="output_risk" label="Risk Level" name="riskLevel" typeRef="string"/>
      <output id="output_score" label="Risk Score" name="riskScore" typeRef="number"/>
      <rule id="rule_1">
        <description>High-value orders are high risk</description>
        <inputEntry id="ie1_1"><text>&gt; 100000</text></inputEntry>
        <inputEntry id="ie1_2"><text>-</text></inputEntry>
        <outputEntry id="oe1_1"><text>"high"</text></outputEntry>
        <outputEntry id="oe1_2"><text>90</text></outputEntry>
      </rule>
      <rule id="rule_2">
        <description>Levies are medium risk</description>
        <inputEntry id="ie2_1"><text>-</text></inputEntry>
        <inputEntry id="ie2_2"><text>"LEVY"</text></inputEntry>
        <outputEntry id="oe2_1"><text>"medium"</text></outputEntry>
        <outputEntry id="oe2_2"><text>60</text></outputEntry>
      </rule>
      <rule id="rule_3">
        <description>Default: low risk</description>
        <inputEntry id="ie3_1"><text>-</text></inputEntry>
        <inputEntry id="ie3_2"><text>-</text></inputEntry>
        <outputEntry id="oe3_1"><text>"low"</text></outputEntry>
        <outputEntry id="oe3_2"><text>20</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>
```

### 3.2 Additional Mapping Needed: `x9-field-mapping`

Our spec references `map://x9-field-mapping` as the canonical example URI. The current image has `pdf-extract-to-legal-order` and `edi-to-subpoena` but not `x9-field-mapping`.

| Field | Value |
|---|---|
| `mappingId` | `x9-field-mapping` |
| `name` | `X9 Field Mapping` |
| `status` | `active` |
| `sourceFormat` | `RAW_JSON` |
| `targetModelId` | `X9_129_LEGAL_ORDER_HEADER_V1` |
| `joltSpec` | A valid Jolt spec (below) |

**Suggested Jolt spec:**

```json
[
  {
    "operation": "shift",
    "spec": {
      "orderType": "orderTypeCode",
      "caseNumber": "orderNumber",
      "agency": "issuingAgencyName",
      "agencyCode": "issuingAgencyId",
      "state": "jurisdictionCode",
      "amount": "orderAmount",
      "dateIssued": "issueDate",
      "dateEffective": "effectiveDate"
    }
  },
  {
    "operation": "default",
    "spec": {
      "recordType": "20",
      "currencyCode": "USD",
      "orderStatus": "RECEIVED",
      "priorityCode": "STANDARD"
    }
  }
]
```

### 3.3 Edge-Case Test Data: Draft Decision (for negative testing)

We'd like one decision in `draft` status so we can verify our `?status=active` filter correctly excludes it.

| Field | Value |
|---|---|
| `decisionId` | `draft-test-decision` |
| `name` | `Draft Test Decision` |
| `namespace` | `com.anax.decisions` |
| `status` | `draft` |
| `sourceContent` | Any valid DMN XML (can be a copy of `risk-assessment` with different `id`/`name`) |

**Test case:** `GET /api/decisions?namespace=com.anax.decisions&name=Draft%20Test%20Decision&status=active` should return **0 results**, while without `&status=active` it should return 1.

### 3.4 Summary of Requested Seed Data

| Asset Type | ID | Name | Status | Purpose |
|---|---|---|---|---|
| Decision | `risk-assessment` | Risk Assessment | `active` | Multi-decision resolution test |
| Mapping | `x9-field-mapping` | X9 Field Mapping | `active` | Canonical `map://` example from spec |
| Decision | `draft-test-decision` | Draft Test Decision | `draft` | Status filter negative test |

---

## 4. Questions

1. **Is the `items` + flat pagination structure intentional?** If so we'll update our spec. If not, let us know the target structure and we'll wait on that change.

2. **`id` vs `decisionId` in lists** — Same question. Will you unify, or should we treat list and detail as different shapes?

3. **Image versioning** — Once the new seed data is added, could you tag the image (e.g., `margic/anax-metadata-platform:v0.2.0`) so we can pin our dev container to a known-good version? We're currently on `:latest`.

---

## 5. No Blockers

To be clear: **none of the above blocks our work.** We verified the critical happy-path flow:

```
dmn://com.anax.decisions/Legal Order Routing
  → GET /api/decisions?namespace=com.anax.decisions&name=Legal%20Order%20Routing&status=active
  → items[0].id = "legal-order-routing"
  → GET /api/decisions/legal-order-routing/export?format=dmn
  → ✅ Valid DMN XML (3833 bytes, Content-Type: application/xml)

map://pdf-extract-to-legal-order
  → GET /api/mappings/pdf-extract-to-legal-order/export?format=jolt
  → ✅ Valid Jolt spec (3 operations, Content-Type: application/json)
```

We'll code our plugin client to handle `items` / `id` as-is and will update if you change the field names. The seed data additions will make our integration test suite more robust but aren't blocking initial development.

Thanks for the fast turnaround on the export endpoints — they work exactly as discussed.
