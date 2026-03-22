package com.anax.kogito.autoconfigure;

import org.kie.kogito.internal.process.workitem.KogitoWorkItem;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemManager;
import org.kie.kogito.internal.process.workitem.Policy;
import org.kie.kogito.internal.process.workitem.WorkItemTransition;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime handler for {@code anax://} custom functions.
 *
 * Resolves a Spring bean by name and invokes the specified method, passing
 * the remaining work-item parameters as a {@code Map<String, Object>}.
 *
 * The target method must have the signature:
 * <pre>{@code Map<String, Object> methodName(Map<String, Object> params)}</pre>
 *
 * Registered as the {@code "anax"} handler via {@link AnaxKogitoAutoConfiguration}.
 */
public class AnaxWorkItemHandler extends DefaultKogitoWorkItemHandler {

    static final String PARAM_BEAN_NAME = "BeanName";
    static final String PARAM_METHOD_NAME = "MethodName";

    private final ApplicationContext applicationContext;

    public AnaxWorkItemHandler(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<WorkItemTransition> activateWorkItemHandler(
            KogitoWorkItemManager manager,
            KogitoWorkItemHandler handler,
            KogitoWorkItem workItem,
            WorkItemTransition transition) {

        String beanName = (String) workItem.getParameter(PARAM_BEAN_NAME);
        String methodName = (String) workItem.getParameter(PARAM_METHOD_NAME);

        Map<String, Object> params = new HashMap<>(workItem.getParameters());
        params.remove(PARAM_BEAN_NAME);
        params.remove(PARAM_METHOD_NAME);
        params.remove("TaskName");

        Object bean = applicationContext.getBean(beanName);
        Map<String, Object> result;
        try {
            Method method = bean.getClass().getMethod(methodName, Map.class);
            result = (Map<String, Object>) method.invoke(bean, params);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "anax:// handler: bean '" + beanName + "' has no method '"
                            + methodName + "(Map<String,Object>)'",
                    e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "anax:// handler invocation failed for " + beanName + "/" + methodName, e);
        }

        manager.completeWorkItem(workItem.getStringId(),
                result != null ? result : Collections.emptyMap(),
                transition.policies().toArray(Policy[]::new));
        return Optional.empty();
    }
}
