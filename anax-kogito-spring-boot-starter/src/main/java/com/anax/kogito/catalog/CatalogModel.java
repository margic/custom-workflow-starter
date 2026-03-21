package com.anax.kogito.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Java records representing the catalog.json structure.
 * The catalog provides a machine-readable inventory of available operations,
 * rules, workflows, and beans for AI-assisted workflow authoring.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogModel(
    @JsonProperty("schemaVersion") String schemaVersion,
    @JsonProperty("generatedAt") Instant generatedAt,
    @JsonProperty("schemes") List<SchemeEntry> schemes,
    @JsonProperty("dmnModels") List<DmnModelEntry> dmnModels,
    @JsonProperty("workflows") List<WorkflowEntry> workflows,
    @JsonProperty("springBeans") List<SpringBeanEntry> springBeans,
    @JsonProperty("formSchemas") List<FormSchemaEntry> formSchemas
) {
    public CatalogModel {
        if (schemaVersion == null) {
            schemaVersion = "1.0";
        }
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
        if (schemes == null) {
            schemes = List.of();
        }
        if (dmnModels == null) {
            dmnModels = List.of();
        }
        if (workflows == null) {
            workflows = List.of();
        }
        if (springBeans == null) {
            springBeans = List.of();
        }
        if (formSchemas == null) {
            formSchemas = List.of();
        }
    }
}

/**
 * Represents a custom URI scheme (dmn://, anax://, map://) with its pattern and parameters.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record SchemeEntry(
    @JsonProperty("scheme") String scheme,
    @JsonProperty("description") String description,
    @JsonProperty("uriPattern") String uriPattern,
    @JsonProperty("parameters") List<ParameterEntry> parameters,
    @JsonProperty("handler") String handler
) {}

/**
 * Represents a parameter that can be extracted from the URI or passed to the handler.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ParameterEntry(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("source") String source,
    @JsonProperty("type") String type,
    @JsonProperty("required") Boolean required
) {}

/**
 * Represents a DMN decision model available for invocation via dmn:// URI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record DmnModelEntry(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("modelName") String modelName,
    @JsonProperty("uri") String uri,
    @JsonProperty("decisions") List<DecisionEntry> decisions,
    @JsonProperty("inputs") List<ParameterEntry> inputs,
    @JsonProperty("outputs") List<ParameterEntry> outputs
) {}

/**
 * Represents a decision within a DMN model.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record DecisionEntry(
    @JsonProperty("name") String name,
    @JsonProperty("id") String id,
    @JsonProperty("description") String description
) {}

/**
 * Represents a deployed serverless workflow with its events and functions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record WorkflowEntry(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("version") String version,
    @JsonProperty("description") String description,
    @JsonProperty("events") List<EventEntry> events,
    @JsonProperty("functions") List<FunctionEntry> functions,
    @JsonProperty("startEvent") String startEvent
) {}

/**
 * Represents a CloudEvent definition consumed or produced by a workflow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record EventEntry(
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("source") String source,
    @JsonProperty("kind") String kind,
    @JsonProperty("metadata") Map<String, Object> metadata
) {}

/**
 * Represents a function definition in a workflow (dmn://, anax://, map://, etc.).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record FunctionEntry(
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("operation") String operation,
    @JsonProperty("metadata") Map<String, Object> metadata
) {}

/**
 * Represents a Spring-managed bean callable via anax:// URI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record SpringBeanEntry(
    @JsonProperty("beanName") String beanName,
    @JsonProperty("beanClass") String beanClass,
    @JsonProperty("methods") List<MethodEntry> methods,
    @JsonProperty("uri") String uri,
    @JsonProperty("description") String description
) {}

/**
 * Represents a method on a Spring bean that can be invoked from a workflow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record MethodEntry(
    @JsonProperty("methodName") String methodName,
    @JsonProperty("returnType") String returnType,
    @JsonProperty("parameterTypes") List<String> parameterTypes,
    @JsonProperty("uri") String uri,
    @JsonProperty("description") String description
) {}

/**
 * Represents a form schema definition for human-in-the-loop workflows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record FormSchemaEntry(
    @JsonProperty("formId") String formId,
    @JsonProperty("title") String title,
    @JsonProperty("schemaUrl") String schemaUrl,
    @JsonProperty("fields") List<FormFieldEntry> fields,
    @JsonProperty("workflowBindings") List<String> workflowBindings
) {}

/**
 * Represents a field in a form schema.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record FormFieldEntry(
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("label") String label,
    @JsonProperty("required") Boolean required,
    @JsonProperty("validation") Map<String, Object> validation
) {}
