package com.anax.kogito.autoconfigure;

import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNResult;
import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;
import org.kie.kogito.internal.process.workitem.KogitoWorkItem;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemManager;
import org.kie.kogito.internal.process.workitem.Policy;
import org.kie.kogito.internal.process.workitem.WorkItemTransition;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runtime handler for {@code dmn://} custom functions.
 *
 * Evaluates a Kogito-managed DMN model entirely in-process.
 * The model namespace and name are passed as static work-item parameters set by
 * {@code DmnFunctionTypeHandler} during codegen. Any additional work-item
 * parameters are forwarded as DMN input variables.
 *
 * Registered as the {@code "dmn"} handler via
 * {@link AnaxKogitoAutoConfiguration}.
 */
public class DmnWorkItemHandler extends DefaultKogitoWorkItemHandler {

    static final String PARAM_NAMESPACE = "DmnNamespace";
    static final String PARAM_MODEL_NAME = "ModelName";

    private final DecisionModels decisionModels;

    public DmnWorkItemHandler(DecisionModels decisionModels) {
        this.decisionModels = decisionModels;
    }

    @Override
    public Optional<WorkItemTransition> activateWorkItemHandler(
            KogitoWorkItemManager manager,
            KogitoWorkItemHandler handler,
            KogitoWorkItem workItem,
            WorkItemTransition transition) {

        String namespace = (String) workItem.getParameter(PARAM_NAMESPACE);
        String modelName = (String) workItem.getParameter(PARAM_MODEL_NAME);

        Map<String, Object> inputs = new HashMap<>(workItem.getParameters());
        inputs.remove(PARAM_NAMESPACE);
        inputs.remove(PARAM_MODEL_NAME);
        inputs.remove("TaskName");

        DecisionModel model = decisionModels.getDecisionModel(namespace, modelName);
        DMNContext ctx = model.newContext(inputs);
        DMNResult result = model.evaluateAll(ctx);

        Map<String, Object> dmnOutputs = result.getDecisionResults().stream()
                .filter(dr -> dr.getEvaluationStatus() == DMNDecisionResult.DecisionEvaluationStatus.SUCCEEDED)
                .collect(Collectors.toMap(
                        DMNDecisionResult::getDecisionName,
                        dr -> dr.getResult() != null ? dr.getResult() : ""));

        // Wrap under "Result" key — the generated work item output data
        // association maps this key to the process variable
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("Result", dmnOutputs);

        manager.completeWorkItem(workItem.getStringId(), outputs,
                transition.policies().toArray(Policy[]::new));
        return Optional.empty();
    }
}
