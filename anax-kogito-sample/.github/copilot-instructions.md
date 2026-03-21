## Anax Serverless Workflow Conventions

This project uses the Anax Kogito Spring Boot Starter to build
event-driven CNCF Serverless Workflows (spec v0.8).

### Custom Function URI Schemes

All custom functions use `"type": "custom"` in sw.json:

| Scheme    | Purpose                        | URI Format                      |
|-----------|--------------------------------|---------------------------------|
| `dmn://`  | Evaluate a DMN decision model  | `dmn://{namespace}/{modelName}` |
| `anax://` | Invoke a Spring bean method    | `anax://{beanName}/{methodName}`|
| `map://`  | Apply a data mapping           | `map://{mappingName}`           |

### Discovering Available Operations

Project metadata is in `build/generated/resources/kogito/META-INF/anax/catalog.json`.
At runtime, query `GET /anax/catalog` for the full inventory of:
- DMN models (namespace, name, inputs, outputs)
- Spring beans callable via anax://
- Deployed workflows with events and function references

### Event-Driven Patterns

- Workflows consume CloudEvents via Kafka (`"kind": "consumed"`)
- Callback states implement human-in-the-loop with `eventRef`
- Event `type` must match the CloudEvent type from upstream services

### Function Definition Template

```json
{
  "name": "myFunction",
  "type": "custom",
  "operation": "anax://myService/myMethod"
}
```
