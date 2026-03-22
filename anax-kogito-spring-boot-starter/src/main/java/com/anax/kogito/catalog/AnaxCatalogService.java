package com.anax.kogito.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AnaxCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AnaxCatalogService.class);
    private static final String CATALOG_PATH = "classpath:META-INF/anax/catalog.json";

    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final ResourceLoader resourceLoader;

    private CatalogModel.Catalog catalog;

    public AnaxCatalogService(ObjectMapper objectMapper,
                              ApplicationContext applicationContext,
                              ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() {
        catalog = loadCatalog();
        catalog = augmentWithLiveBeans(catalog);
    }

    public CatalogModel.Catalog getCatalog() {
        return catalog;
    }

    public List<CatalogModel.SchemeEntry> getSchemes() {
        return catalog.schemes();
    }

    public List<CatalogModel.DmnModelEntry> getDmnModels() {
        return catalog.dmnModels();
    }

    public List<CatalogModel.WorkflowEntry> getWorkflows() {
        return catalog.workflows();
    }

    public List<CatalogModel.SpringBeanEntry> getSpringBeans() {
        return catalog.springBeans();
    }

    private CatalogModel.Catalog loadCatalog() {
        Resource resource = resourceLoader.getResource(CATALOG_PATH);
        if (resource.exists()) {
            try {
                return objectMapper.readValue(resource.getInputStream(), CatalogModel.Catalog.class);
            } catch (IOException e) {
                log.warn("Failed to read catalog.json, using default scheme definitions", e);
            }
        }
        return buildDefaultCatalog();
    }

    private CatalogModel.Catalog buildDefaultCatalog() {
        List<CatalogModel.SchemeEntry> schemes = List.of(
                new CatalogModel.SchemeEntry("dmn", "Evaluate a DMN decision model in-process",
                        "dmn://{namespace}/{modelName}",
                        List.of(
                                new CatalogModel.ParameterEntry("DmnNamespace", "DMN model namespace", "uri"),
                                new CatalogModel.ParameterEntry("ModelName", "DMN model name", "uri")
                        ), "DmnWorkItemHandler"),
                new CatalogModel.SchemeEntry("anax", "Invoke a Spring bean method",
                        "anax://{beanName}/{methodName}",
                        List.of(
                                new CatalogModel.ParameterEntry("BeanName", "Spring bean name", "uri"),
                                new CatalogModel.ParameterEntry("MethodName", "Method to invoke (default: execute)", "uri")
                        ), "AnaxWorkItemHandler"),
                new CatalogModel.SchemeEntry("map", "Apply a Jolt data transformation",
                        "map://{mappingName}",
                        List.of(
                                new CatalogModel.ParameterEntry("MappingName", "Jolt mapping spec name", "uri")
                        ), "MapWorkItemHandler")
        );
        return new CatalogModel.Catalog("1.0", null, schemes,
                List.of(), List.of(), List.of(), List.of());
    }

    private CatalogModel.Catalog augmentWithLiveBeans(CatalogModel.Catalog base) {
        List<CatalogModel.SpringBeanEntry> beans = new ArrayList<>(base.springBeans());
        List<String> existingNames = beans.stream()
                .map(CatalogModel.SpringBeanEntry::beanName)
                .toList();

        for (String name : applicationContext.getBeanDefinitionNames()) {
            if (existingNames.contains(name)) {
                continue;
            }
            try {
                Object bean = applicationContext.getBean(name);
                List<CatalogModel.MethodEntry> anaxMethods = findAnaxMethods(bean);
                if (!anaxMethods.isEmpty()) {
                    beans.add(new CatalogModel.SpringBeanEntry(
                            name, bean.getClass().getName(), anaxMethods,
                            "anax://" + name));
                }
            } catch (Exception e) {
                // Skip beans that can't be instantiated eagerly
            }
        }

        return new CatalogModel.Catalog(
                base.schemaVersion(), base.generatedAt(), base.schemes(),
                base.dmnModels(), base.workflows(), beans, base.formSchemas());
    }

    private List<CatalogModel.MethodEntry> findAnaxMethods(Object bean) {
        List<CatalogModel.MethodEntry> result = new ArrayList<>();
        for (Method m : bean.getClass().getMethods()) {
            if (m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Map.class
                    && Map.class.isAssignableFrom(m.getReturnType())) {
                result.add(new CatalogModel.MethodEntry(
                        m.getName(), "Map<String, Object>", "Map<String, Object>"));
            }
        }
        return result;
    }
}
