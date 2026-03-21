package com.anax.kogito.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service that loads the static catalog.json generated at build time
 * and augments it with live ApplicationContext scanning to discover
 * beans added after the last build.
 *
 * This enables both build-time metadata (DMN models, workflows) and
 * runtime bean discovery for AI-assisted workflow authoring.
 */
@Service
public class AnaxCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AnaxCatalogService.class);
    private static final String CATALOG_LOCATION = "classpath:META-INF/anax/catalog.json";

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private CatalogModel staticCatalog;

    @Autowired
    public AnaxCatalogService(ApplicationContext applicationContext, ObjectMapper objectMapper) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        loadStaticCatalog();
    }

    /**
     * Load the static catalog.json generated at build time.
     * If the file doesn't exist (e.g., during development before first build),
     * initialize with an empty catalog.
     */
    private void loadStaticCatalog() {
        try {
            Resource resource = applicationContext.getResource(CATALOG_LOCATION);
            if (resource.exists()) {
                staticCatalog = objectMapper.readValue(resource.getInputStream(), CatalogModel.class);
                log.info("Loaded static catalog from {} with {} schemes, {} DMN models, {} workflows, {} beans",
                    CATALOG_LOCATION,
                    staticCatalog.schemes().size(),
                    staticCatalog.dmnModels().size(),
                    staticCatalog.workflows().size(),
                    staticCatalog.springBeans().size());
            } else {
                log.warn("Static catalog not found at {}. Initializing empty catalog. Run a build to generate it.",
                    CATALOG_LOCATION);
                staticCatalog = createEmptyCatalog();
            }
        } catch (IOException e) {
            log.error("Failed to load static catalog from {}. Initializing empty catalog.", CATALOG_LOCATION, e);
            staticCatalog = createEmptyCatalog();
        }
    }

    /**
     * Get the full catalog, augmented with live ApplicationContext scanning.
     */
    public CatalogModel getCatalog() {
        List<SpringBeanEntry> liveBeans = scanApplicationContextForBeans();
        List<SpringBeanEntry> mergedBeans = mergeBeanEntries(staticCatalog.springBeans(), liveBeans);

        return new CatalogModel(
            staticCatalog.schemaVersion(),
            Instant.now(),
            staticCatalog.schemes(),
            staticCatalog.dmnModels(),
            staticCatalog.workflows(),
            mergedBeans,
            staticCatalog.formSchemas()
        );
    }

    /**
     * Get only the scheme definitions.
     */
    public List<SchemeEntry> getSchemes() {
        return staticCatalog.schemes();
    }

    /**
     * Get only the DMN model entries.
     */
    public List<DmnModelEntry> getDmnModels() {
        return staticCatalog.dmnModels();
    }

    /**
     * Get only the workflow entries.
     */
    public List<WorkflowEntry> getWorkflows() {
        return staticCatalog.workflows();
    }

    /**
     * Get only the Spring bean entries (augmented with live scan).
     */
    public List<SpringBeanEntry> getSpringBeans() {
        List<SpringBeanEntry> liveBeans = scanApplicationContextForBeans();
        return mergeBeanEntries(staticCatalog.springBeans(), liveBeans);
    }

    /**
     * Scan the ApplicationContext for beans that can be invoked via anax:// or map:// URIs.
     *
     * For anax:// beans: Look for beans with methods matching signature:
     *   Map<String, Object> methodName(Map<String, Object> params)
     *
     * For map:// beans: Look for beans implementing Function<Map<String, Object>, Map<String, Object>>
     */
    private List<SpringBeanEntry> scanApplicationContextForBeans() {
        List<SpringBeanEntry> beans = new ArrayList<>();

        String[] beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            // Skip Spring internal beans
            if (beanName.startsWith("org.springframework") ||
                beanName.startsWith("spring.") ||
                beanName.contains("InternalConfigurationAnnotation")) {
                continue;
            }

            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> beanClass = bean.getClass();

                // Skip proxies and framework classes
                if (beanClass.getName().contains("EnhancerBySpringCGLIB") ||
                    beanClass.getName().contains("$$") ||
                    beanClass.getPackage() == null ||
                    beanClass.getPackage().getName().startsWith("org.springframework") ||
                    beanClass.getPackage().getName().startsWith("org.kie.kogito")) {
                    continue;
                }

                // Check if bean implements Function<Map, Map> for map:// support
                if (bean instanceof Function) {
                    SpringBeanEntry entry = createFunctionBeanEntry(beanName, beanClass);
                    if (entry != null) {
                        beans.add(entry);
                    }
                    continue;
                }

                // Check for methods matching anax:// signature
                List<MethodEntry> methods = findAnaxCompatibleMethods(beanClass);
                if (!methods.isEmpty()) {
                    String uri = String.format("anax://%s/{methodName}", beanName);
                    SpringBeanEntry entry = new SpringBeanEntry(
                        beanName,
                        beanClass.getName(),
                        methods,
                        uri,
                        String.format("Spring bean: %s", beanClass.getSimpleName())
                    );
                    beans.add(entry);
                }
            } catch (Exception e) {
                log.debug("Skipping bean {} due to error: {}", beanName, e.getMessage());
            }
        }

        log.debug("Discovered {} beans via ApplicationContext scan", beans.size());
        return beans;
    }

    /**
     * Create a bean entry for a Function<Map, Map> bean (map:// scheme).
     */
    private SpringBeanEntry createFunctionBeanEntry(String beanName, Class<?> beanClass) {
        try {
            Method applyMethod = beanClass.getMethod("apply", Object.class);
            String uri = String.format("map://%s", beanName);

            MethodEntry methodEntry = new MethodEntry(
                "apply",
                "Map<String,Object>",
                List.of("Map<String,Object>"),
                uri,
                "Data mapping function"
            );

            return new SpringBeanEntry(
                beanName,
                beanClass.getName(),
                List.of(methodEntry),
                uri,
                String.format("Mapping function: %s", beanClass.getSimpleName())
            );
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Find methods on a bean that match the anax:// signature:
     * - Public, non-static
     * - Returns Map (or compatible type)
     * - Takes single Map parameter (or compatible type)
     */
    private List<MethodEntry> findAnaxCompatibleMethods(Class<?> beanClass) {
        List<MethodEntry> methods = new ArrayList<>();

        for (Method method : beanClass.getDeclaredMethods()) {
            if (isAnaxCompatibleMethod(method)) {
                String beanName = deriveBeanName(beanClass);
                String uri = String.format("anax://%s/%s", beanName, method.getName());

                MethodEntry entry = new MethodEntry(
                    method.getName(),
                    method.getReturnType().getSimpleName(),
                    Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.toList()),
                    uri,
                    String.format("Invokes %s on %s", method.getName(), beanClass.getSimpleName())
                );
                methods.add(entry);
            }
        }

        return methods;
    }

    /**
     * Check if a method matches the anax:// contract.
     */
    private boolean isAnaxCompatibleMethod(Method method) {
        int modifiers = method.getModifiers();

        // Must be public and non-static
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
            return false;
        }

        // Must return Map or compatible type
        Class<?> returnType = method.getReturnType();
        if (!Map.class.isAssignableFrom(returnType)) {
            return false;
        }

        // Must take single Map parameter
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != 1) {
            return false;
        }

        return Map.class.isAssignableFrom(paramTypes[0]);
    }

    /**
     * Derive the Spring bean name from a class (lowercase first letter).
     */
    private String deriveBeanName(Class<?> beanClass) {
        String simpleName = beanClass.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    /**
     * Merge static beans from catalog.json with live-scanned beans.
     * Prefer static entries (they may have richer metadata), but add any
     * beans discovered at runtime that weren't in the static catalog.
     */
    private List<SpringBeanEntry> mergeBeanEntries(List<SpringBeanEntry> staticBeans,
                                                     List<SpringBeanEntry> liveBeans) {
        List<SpringBeanEntry> merged = new ArrayList<>(staticBeans);

        for (SpringBeanEntry liveBean : liveBeans) {
            boolean exists = staticBeans.stream()
                .anyMatch(sb -> sb.beanName().equals(liveBean.beanName()));

            if (!exists) {
                merged.add(liveBean);
            }
        }

        return merged;
    }

    /**
     * Create an empty catalog when the static catalog.json is not available.
     */
    private CatalogModel createEmptyCatalog() {
        return new CatalogModel(
            "1.0",
            Instant.now(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
}
