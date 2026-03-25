package com.anax.kogito.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/anax/catalog")
@ConditionalOnProperty(prefix = "anax.catalog", name = "enabled", matchIfMissing = true)
public class AnaxCatalogController {

    private final AnaxCatalogService catalogService;

    public AnaxCatalogController(AnaxCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public CatalogModel.Catalog getCatalog() {
        return catalogService.getCatalog();
    }

    @GetMapping("/schemes")
    public List<CatalogModel.SchemeEntry> getSchemes() {
        return catalogService.getSchemes();
    }

    @GetMapping("/dmn")
    public List<CatalogModel.DmnModelEntry> getDmnModels() {
        return catalogService.getDmnModels();
    }

    @GetMapping("/workflows")
    public List<CatalogModel.WorkflowEntry> getWorkflows() {
        return catalogService.getWorkflows();
    }

    @GetMapping("/beans")
    public List<CatalogModel.SpringBeanEntry> getSpringBeans() {
        return catalogService.getSpringBeans();
    }
}
