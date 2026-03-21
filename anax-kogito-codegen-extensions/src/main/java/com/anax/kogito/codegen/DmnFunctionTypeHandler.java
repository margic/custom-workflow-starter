package com.anax.kogito.codegen;

import io.serverlessworkflow.api.Workflow;
import io.serverlessworkflow.api.functions.FunctionDefinition;
import org.jbpm.ruleflow.core.RuleFlowNodeContainerFactory;
import org.jbpm.ruleflow.core.factory.WorkItemNodeFactory;
import org.kie.kogito.serverless.workflow.parser.FunctionTypeHandlerFactory;
import org.kie.kogito.serverless.workflow.parser.ParserContext;
import org.kie.kogito.serverless.workflow.parser.types.WorkItemTypeHandler;

/**
 * Codegen-time extension: teaches the Kogito codegen to emit a WorkItemNode
 * (instead of an empty lambda) for any {@code type: custom} function whose
 * {@code operation} uses the {@code dmn://} URI scheme.
 *
 * <p>
 * URI format: {@code dmn://namespace/Model Name}
 * <ul>
 * <li>namespace — the DMN model namespace (e.g. {@code com.anax.decisions})</li>
 * <li>Model Name — the DMN model name (e.g. {@code Order Type Routing})</li>
 * </ul>
 *
 * <p>
 * Registered via SPI:
 * {@code META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler}
 *
 * <p>
 * At runtime the work item is dispatched to DmnWorkItemHandler.
 */
public class DmnFunctionTypeHandler extends WorkItemTypeHandler {

    /**
     * Work-item handler name — must match what is registered in
     * AnaxKogitoAutoConfiguration.
     */
    public static final String DMN_SCHEME = "dmn";

    /** Work-item parameter carrying the DMN model namespace. */
    public static final String PARAM_NAMESPACE = "DmnNamespace";

    /** Work-item parameter carrying the DMN model name. */
    public static final String PARAM_MODEL_NAME = "ModelName";

    @Override
    public String type() {
        return DMN_SCHEME;
    }

    @Override
    public boolean isCustom() {
        return true;
    }

    /**
     * Sets the work-item handler name to {@code "dmn"} and enriches the node with
     * the static namespace and model-name parameters parsed from the operation URI.
     *
     * <p>
     * {@code FunctionTypeHandlerFactory.trimCustomOperation()} strips the scheme
     * prefix up to and including the first {@code ":"}, so for
     * {@code "dmn://com.anax.decisions/Order Type Routing"} it returns
     * {@code "//com.anax.decisions/Order Type Routing"}.
     */
    @Override
    protected <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T> fillWorkItemHandler(
            Workflow workflow, ParserContext context,
            WorkItemNodeFactory<T> factory, FunctionDefinition functionDef) {

        String operationPath = FunctionTypeHandlerFactory.trimCustomOperation(functionDef);
        if (operationPath.startsWith("//")) {
            operationPath = operationPath.substring(2);
        }

        int slash = operationPath.indexOf('/');
        String namespace = slash >= 0 ? operationPath.substring(0, slash) : "";
        String modelName = slash >= 0 ? operationPath.substring(slash + 1) : operationPath;

        return factory
                .workName(DMN_SCHEME)
                .workParameter(PARAM_NAMESPACE, namespace)
                .workParameter(PARAM_MODEL_NAME, modelName);
    }
}
