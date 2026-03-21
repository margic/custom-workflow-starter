package com.anax.kogito.catalog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing the Anax catalog endpoints.
 *
 * These endpoints provide machine-readable metadata about available
 * DMN models, workflows, Spring beans, and custom URI schemes.
 *
 * Designed for consumption by:
 * - AI coding assistants (GitHub Copilot, etc.)
 * - Developer tooling and dashboards
 * - Runtime introspection tools
 *
 * The catalog is conditionally enabled via the property:
 *   anax.catalog.enabled=true (default)
 *
 * Set to false in production if you want to disable metadata exposure.
 */
@RestController
@RequestMapping("/anax/catalog")
@ConditionalOnProperty(
    prefix = "anax.catalog",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AnaxCatalogController {

    private final AnaxCatalogService catalogService;

    @Autowired
    public AnaxCatalogController(AnaxCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * GET /anax/catalog
     *
     * Returns the full catalog including:
     * - schemes: Available URI schemes (dmn://, anax://, map://)
     * - dmnModels: DMN decision models with namespaces and inputs/outputs
     * - workflows: Deployed serverless workflows with events and functions
     * - springBeans: Spring-managed beans callable via anax:// or map://
     * - formSchemas: Form definitions for human-in-the-loop workflows
     *
     * The catalog is augmented with live ApplicationContext scanning,
     * so beans added after the last build are included.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CatalogModel> getCatalog() {
        CatalogModel catalog = catalogService.getCatalog();
        return ResponseEntity.ok(catalog);
    }

    /**
     * GET /anax/catalog/schemes
     *
     * Returns only the URI scheme definitions (dmn://, anax://, map://)
     * with their patterns, parameters, and handler implementations.
     *
     * Example response:
     * [
     *   {
     *     "scheme": "dmn",
     *     "description": "Evaluate a DMN decision model in-process",
     *     "uriPattern": "dmn://{namespace}/{modelName}",
     *     "parameters": [...],
     *     "handler": "com.anax.kogito.autoconfigure.DmnWorkItemHandler"
     *   },
     *   ...
     * ]
     */
    @GetMapping(path = "/schemes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<SchemeEntry>> getSchemes() {
        List<SchemeEntry> schemes = catalogService.getSchemes();
        return ResponseEntity.ok(schemes);
    }

    /**
     * GET /anax/catalog/dmn
     *
     * Returns only the DMN model entries with namespaces, model names,
     * constructed dmn:// URIs, and input/output variable definitions.
     *
     * This enables AI assistants to generate valid dmn:// function references
     * with correct namespaces and model names.
     */
    @GetMapping(path = "/dmn", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DmnModelEntry>> getDmnModels() {
        List<DmnModelEntry> dmnModels = catalogService.getDmnModels();
        return ResponseEntity.ok(dmnModels);
    }

    /**
     * GET /anax/catalog/workflows
     *
     * Returns only the workflow entries with IDs, events, and function definitions.
     *
     * This allows tooling to discover:
     * - Which workflows are deployed
     * - Which CloudEvents they consume/produce
     * - Which custom functions they reference
     */
    @GetMapping(path = "/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<WorkflowEntry>> getWorkflows() {
        List<WorkflowEntry> workflows = catalogService.getWorkflows();
        return ResponseEntity.ok(workflows);
    }

    /**
     * GET /anax/catalog/beans
     *
     * Returns only the Spring bean entries with method signatures.
     *
     * Includes beans discovered via live ApplicationContext scanning,
     * so it reflects the current runtime state even if the static
     * catalog.json is out of date.
     *
     * This enables AI assistants to generate valid anax:// and map://
     * function references with correct bean names and method names.
     */
    @GetMapping(path = "/beans", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<SpringBeanEntry>> getSpringBeans() {
        List<SpringBeanEntry> beans = catalogService.getSpringBeans();
        return ResponseEntity.ok(beans);
    }
}
