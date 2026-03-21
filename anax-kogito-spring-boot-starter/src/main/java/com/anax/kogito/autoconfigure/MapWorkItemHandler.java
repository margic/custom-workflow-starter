package com.anax.kogito.autoconfigure;

import org.kie.kogito.internal.process.runtime.KogitoWorkItem;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemManager;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Runtime work-item handler for the {@code map://} URI scheme.
 *
 * <p>
 * Applies a named data-mapping transformation function registered as a Spring bean.
 *
 * <p>
 * Work-item parameters (set by {@code MapFunctionTypeHandler} at codegen time):
 * <ul>
 * <li>{@code MappingName} — Spring bean name implementing
 * {@code Function<Map<String,Object>, Map<String,Object>>}</li>
 * </ul>
 *
 * <p>
 * Mapping bean contract:
 * <pre>
 * &#64;Component("x9-field-mapping")
 * public class X9FieldMapping implements Function&lt;Map&lt;String, Object&gt;, Map&lt;String, Object&gt;&gt; {
 *     &#64;Override
 *     public Map&lt;String, Object&gt; apply(Map&lt;String, Object&gt; input) {
 *         // transform input fields to X9 format
 *         return Map.of("x9Field1", input.get("sourceField1"), ...);
 *     }
 * }
 * </pre>
 *
 * <p>
 * All workflow data variables are passed to the mapping function. The function's
 * return value is merged back into the workflow data.
 *
 * <p>
 * Registered as a {@code @Bean} in {@code AnaxKogitoAutoConfiguration}.
 * NOT annotated with {@code @Component} — instantiated via auto-configuration.
 */
public class MapWorkItemHandler extends DefaultKogitoWorkItemHandler {

    private static final Logger logger = LoggerFactory.getLogger(MapWorkItemHandler.class);

    private static final String PARAM_MAPPING_NAME = "MappingName";

    private final ApplicationContext applicationContext;

    /**
     * Constructor injection — no {@code @Autowired} needed.
     *
     * @param applicationContext Spring application context for bean lookup
     */
    public MapWorkItemHandler(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void executeWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        String mappingName = (String) workItem.getParameter(PARAM_MAPPING_NAME);

        logger.debug("Executing map:// function: mappingName={}", mappingName);

        try {
            // Lookup the Spring bean implementing Function<Map, Map>
            Object bean = applicationContext.getBean(mappingName);
            if (bean == null) {
                throw new IllegalStateException("Mapping bean not found: " + mappingName);
            }

            // Validate that the bean implements the expected Function type
            if (!(bean instanceof Function)) {
                throw new IllegalStateException(
                        "Mapping bean must implement Function<Map<String, Object>, Map<String, Object>>, "
                                + "but found: " + bean.getClass().getName());
            }

            @SuppressWarnings("unchecked")
            Function<Map<String, Object>, Map<String, Object>> mappingFunction =
                    (Function<Map<String, Object>, Map<String, Object>>) bean;

            // Build input map from all workflow variables
            Map<String, Object> input = new HashMap<>();
            workItem.getParameters().forEach((key, value) -> {
                // Skip our internal parameter
                if (!PARAM_MAPPING_NAME.equals(key)) {
                    input.put(key, value);
                }
            });

            logger.debug("Applying mapping {} to input: {}", mappingName, input);

            // Apply the mapping function
            Map<String, Object> result = mappingFunction.apply(input);

            if (result == null) {
                throw new IllegalStateException(
                        "Mapping function returned null: " + mappingName);
            }

            logger.debug("Mapping result: {}", result);

            // Complete the work item with mapping results
            // Results are merged back into workflow data
            manager.completeWorkItem(workItem.getStringId(), result);

        } catch (Exception e) {
            logger.error("map:// transformation failed: mappingName={}, error={}",
                    mappingName, e.getMessage(), e);
            manager.abortWorkItem(workItem.getStringId());
        }
    }

    @Override
    public void abortWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        // No-op: Mapping transformations are synchronous and cannot be aborted
        logger.debug("Abort requested for map work item: {}", workItem.getStringId());
    }
}
