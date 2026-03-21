package com.anax.kogito.autoconfigure;

import org.kie.kogito.internal.process.runtime.KogitoWorkItem;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemManager;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime work-item handler for the {@code anax://} URI scheme.
 *
 * <p>
 * Invokes a method on any Spring-managed bean by name.
 *
 * <p>
 * Work-item parameters (set by {@code AnaxFunctionTypeHandler} at codegen time):
 * <ul>
 * <li>{@code BeanName} — Spring bean name to invoke</li>
 * <li>{@code MethodName} — Method name to invoke (default: {@code execute})</li>
 * </ul>
 *
 * <p>
 * Bean method contract:
 * <pre>
 * public Map&lt;String, Object&gt; methodName(Map&lt;String, Object&gt; params)
 * </pre>
 *
 * <p>
 * The method receives all workflow data variables as a {@code Map} and must return
 * a {@code Map} whose entries are merged back into the workflow data.
 *
 * <p>
 * Registered as a {@code @Bean} in {@code AnaxKogitoAutoConfiguration}.
 * NOT annotated with {@code @Component} — instantiated via auto-configuration.
 */
public class AnaxWorkItemHandler extends DefaultKogitoWorkItemHandler {

    private static final Logger logger = LoggerFactory.getLogger(AnaxWorkItemHandler.class);

    private static final String PARAM_BEAN_NAME = "BeanName";
    private static final String PARAM_METHOD_NAME = "MethodName";

    private final ApplicationContext applicationContext;

    /**
     * Constructor injection — no {@code @Autowired} needed.
     *
     * @param applicationContext Spring application context for bean lookup
     */
    public AnaxWorkItemHandler(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void executeWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        String beanName = (String) workItem.getParameter(PARAM_BEAN_NAME);
        String methodName = (String) workItem.getParameter(PARAM_METHOD_NAME);

        logger.debug("Executing anax:// function: beanName={}, methodName={}", beanName, methodName);

        try {
            // Lookup the Spring bean
            Object bean = applicationContext.getBean(beanName);
            if (bean == null) {
                throw new IllegalStateException("Spring bean not found: " + beanName);
            }

            // Build parameter map from all workflow variables
            Map<String, Object> params = new HashMap<>();
            workItem.getParameters().forEach((key, value) -> {
                // Skip our internal parameters
                if (!PARAM_BEAN_NAME.equals(key) && !PARAM_METHOD_NAME.equals(key)) {
                    params.put(key, value);
                }
            });

            logger.debug("Invoking {}.{}() with params: {}", beanName, methodName, params);

            // Invoke the method reflectively
            Method method = bean.getClass().getMethod(methodName, Map.class);
            Object result = method.invoke(bean, params);

            // Validate result type
            if (!(result instanceof Map)) {
                throw new IllegalStateException(
                        "Bean method must return Map<String, Object>, but returned: "
                                + (result != null ? result.getClass().getName() : "null"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) result;

            logger.debug("Bean method result: {}", resultMap);

            // Complete the work item with results
            // Results are merged back into workflow data
            manager.completeWorkItem(workItem.getStringId(), resultMap);

        } catch (Exception e) {
            logger.error("anax:// invocation failed: beanName={}, methodName={}, error={}",
                    beanName, methodName, e.getMessage(), e);
            manager.abortWorkItem(workItem.getStringId());
        }
    }

    @Override
    public void abortWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        // No-op: Bean invocations are synchronous and cannot be aborted
        logger.debug("Abort requested for anax work item: {}", workItem.getStringId());
    }
}
