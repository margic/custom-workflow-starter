package com.anax.kogito.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        return defaultSchemes();
    }

    public List<CatalogModel.DmnModelEntry> getDmnModels() {
        return catalog.assets().stream()
                .filter(asset -> "decision".equals(asset.assetType()))
                .map(this::toDmnModelEntry)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<CatalogModel.WorkflowEntry> getWorkflows() {
        return catalog.assets().stream()
                .filter(asset -> "workflow".equals(asset.assetType()))
                .map(asset -> new CatalogModel.WorkflowEntry(
                        asset.assetId(),
                        asset.assetId(),
                        null,
                        List.of(),
                        List.of()))
                .toList();
    }

    public List<CatalogModel.SpringBeanEntry> getSpringBeans() {
        return catalog.assets().stream()
                .filter(asset -> "function".equals(asset.assetType()))
                .map(this::toFunctionDescriptor)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        FunctionDescriptor::beanName,
                        Collectors.collectingAndThen(Collectors.toList(), this::toSpringBeanEntry)))
                .values().stream()
                .sorted(Comparator.comparing(CatalogModel.SpringBeanEntry::beanName))
                .toList();
    }

    private CatalogModel.Catalog loadCatalog() {
        Resource resource = resourceLoader.getResource(CATALOG_PATH);
        if (resource.exists()) {
            try {
                CatalogModel.Catalog loaded = objectMapper.readValue(resource.getInputStream(),
                        CatalogModel.Catalog.class);
                if (loaded == null || loaded.assets() == null) {
                    return buildDefaultCatalog();
                }
                return loaded;
            } catch (IOException e) {
                log.warn("Failed to read catalog.json, using default scheme definitions", e);
            }
        }
        return buildDefaultCatalog();
    }

    private CatalogModel.Catalog buildDefaultCatalog() {
        return new CatalogModel.Catalog("2.0", null, List.of());
    }

    private List<CatalogModel.SchemeEntry> defaultSchemes() {
        return List.of(
                new CatalogModel.SchemeEntry("dmn", "Evaluate a DMN decision model in-process",
                        "dmn://{namespace}/{modelName}",
                        List.of(
                                new CatalogModel.ParameterEntry("DmnNamespace", "DMN model namespace", "uri"),
                                new CatalogModel.ParameterEntry("ModelName", "DMN model name", "uri")),
                        "DmnWorkItemHandler"),
                new CatalogModel.SchemeEntry("anax", "Invoke a Spring bean method",
                        "anax://{beanName}/{methodName}",
                        List.of(
                                new CatalogModel.ParameterEntry("BeanName", "Spring bean name", "uri"),
                                new CatalogModel.ParameterEntry("MethodName", "Method to invoke (default: execute)",
                                        "uri")),
                        "AnaxWorkItemHandler"),
                new CatalogModel.SchemeEntry("map", "Apply a Jolt data transformation",
                        "map://{mappingName}",
                        List.of(
                                new CatalogModel.ParameterEntry("MappingName", "Jolt mapping spec name", "uri")),
                        "MapWorkItemHandler"));
    }

    private CatalogModel.Catalog augmentWithLiveBeans(CatalogModel.Catalog base) {
        List<CatalogModel.AssetEntry> assets = new ArrayList<>(base.assets());
        Set<String> existingUris = assets.stream()
                .filter(asset -> "function".equals(asset.assetType()))
                .map(CatalogModel.AssetEntry::uri)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String name : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(name);
                List<CatalogModel.MethodEntry> anaxMethods = findAnaxMethods(bean);
                for (CatalogModel.MethodEntry method : anaxMethods) {
                    String uri = "anax://" + name + "/" + method.name();
                    if (existingUris.add(uri)) {
                        assets.add(new CatalogModel.AssetEntry(
                                uri,
                                name + "/" + method.name(),
                                "function",
                                null,
                                null));
                    }
                }
            } catch (Exception e) {
                // Skip beans that can't be instantiated eagerly
            }
        }

        return new CatalogModel.Catalog(
                base.schemaVersion(), base.generatedAt(), assets);
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

    private CatalogModel.DmnModelEntry toDmnModelEntry(CatalogModel.AssetEntry asset) {
        String uri = asset.uri();
        if (uri == null || !uri.startsWith("dmn://")) {
            return null;
        }
        String remainder = uri.substring("dmn://".length());
        int slash = remainder.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String namespace = remainder.substring(0, slash);
        String name = remainder.substring(slash + 1);
        return new CatalogModel.DmnModelEntry(namespace, name, uri, null, List.of(), List.of());
    }

    private FunctionDescriptor toFunctionDescriptor(CatalogModel.AssetEntry asset) {
        String uri = asset.uri();
        if (uri == null || !uri.startsWith("anax://")) {
            return null;
        }
        String remainder = uri.substring("anax://".length());
        int slash = remainder.indexOf('/');
        String beanName = slash >= 0 ? remainder.substring(0, slash) : remainder;
        String methodName = slash >= 0 ? remainder.substring(slash + 1) : "execute";
        return new FunctionDescriptor(beanName, methodName, uri);
    }

    private CatalogModel.SpringBeanEntry toSpringBeanEntry(List<FunctionDescriptor> functions) {
        FunctionDescriptor first = functions.get(0);
        List<CatalogModel.MethodEntry> methods = functions.stream()
                .map(fn -> new CatalogModel.MethodEntry(fn.methodName(), "Map<String, Object>", "Map<String, Object>"))
                .sorted(Comparator.comparing(CatalogModel.MethodEntry::name))
                .toList();
        return new CatalogModel.SpringBeanEntry(first.beanName(), null, methods, "anax://" + first.beanName());
    }

    private record FunctionDescriptor(String beanName, String methodName, String uri) {
    }
}
