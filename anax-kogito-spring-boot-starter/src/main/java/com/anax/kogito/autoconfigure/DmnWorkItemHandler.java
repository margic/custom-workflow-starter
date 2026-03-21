package com.anax.kogito.autoconfigure;

import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;
import org.kie.dmn.api.core.DMNResult;
import org.kie.kogito.internal.process.workitem.KogitoWorkItem;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemManager;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Runtime work-item handler for the {@code dmn://} URI scheme.
 *
 * <p>
 * Evaluates a DMN decision model in-process using Kogito's {@link DecisionModels} API.
 *
 * <p>
 * Work-item parameters (set by {@code DmnFunctionTypeHandler} at codegen time):
 * <ul>
 * <li>{@code DmnNamespace} — DMN model namespace</li>
 * <li>{@code ModelName} — DMN model name</li>
 * </ul>
 *
 * <p>
 * All workflow data variables are passed as DMN input context. Decision results
 * are merged back into the workflow data.
 *
 * <p>
 * Registered as a {@code @Bean} in {@code AnaxKogitoAutoConfiguration}.
 * NOT annotated with {@code @Component} — instantiated via auto-configuration.
 */
public class DmnWorkItemHandler extends DefaultKogitoWorkItemHandler {

    private static final Logger logger = LoggerFactory.getLogger(DmnWorkItemHandler.class);

    private static final String PARAM_NAMESPACE = "DmnNamespace";
    private static final String PARAM_MODEL_NAME = "ModelName";

    private final DecisionModels decisionModels;

    /**
     * Constructor injection — no {@code @Autowired} needed.
     *
     * @param decisionModels Kogito decision models registry
     */
    public DmnWorkItemHandler(DecisionModels decisionModels) {
        this.decisionModels = decisionModels;
    }

    public void executeWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        String namespace = (String) workItem.getParameter(PARAM_NAMESPACE);
        String modelName = (String) workItem.getParameter(PARAM_MODEL_NAME);

        logger.debug("Executing DMN decision: namespace={}, modelName={}", namespace, modelName);

        try {
            // Lookup the DMN model by namespace and model name
            DecisionModel decisionModel = decisionModels.getDecisionModel(namespace, modelName);
            if (decisionModel == null) {
                throw new IllegalStateException(
                        "DMN model not found: namespace=" + namespace + ", modelName=" + modelName);
            }

            // Build DMN input context from all workflow variables
            Map<String, Object> dmnContext = new HashMap<>();
            workItem.getParameters().forEach((key, value) -> {
                // Skip our internal parameters
                if (!PARAM_NAMESPACE.equals(key) && !PARAM_MODEL_NAME.equals(key)) {
                    dmnContext.put(key, value);
                }
            });

            logger.debug("DMN input context: {}", dmnContext);

            // Evaluate the DMN decision
            DMNResult dmnResult = decisionModel.evaluateAll(
                    decisionModel.newContext(dmnContext));

            // Extract decision results from DMNResult
            Map<String, Object> result = new HashMap<>();
            dmnResult.getDecisionResults().forEach(dr -> {
                result.put(dr.getDecisionName(), dr.getResult());
            });

            logger.debug("DMN decision result: {}", result);

            // Complete the work item with decision results
            // Results are merged back into workflow data
            manager.completeWorkItem(workItem.getStringId(), result);

        } catch (Exception e) {
            logger.error("DMN evaluation failed: namespace={}, modelName={}, error={}",
                    namespace, modelName, e.getMessage(), e);
            manager.abortWorkItem(workItem.getStringId());
        }
    }

    public void abortWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        // No-op: DMN evaluations are synchronous and cannot be aborted
        logger.debug("Abort requested for DMN work item: {}", workItem.getStringId());
    }
}
