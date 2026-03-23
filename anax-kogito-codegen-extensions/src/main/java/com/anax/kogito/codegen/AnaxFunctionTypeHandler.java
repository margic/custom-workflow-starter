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
 * for any {@code type: custom} function whose {@code operation} uses the
 * {@code anax://} URI scheme.
 *
 * <p>
 * URI format: {@code anax://beanName/methodName}
 * <ul>
 * <li>beanName — the Spring bean name (e.g. {@code helloService})</li>
 * <li>methodName — the method to invoke; defaults to {@code execute} if
 * omitted</li>
 * </ul>
 *
 * <p>
 * Registered via SPI:
 * {@code META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler}
 *
 * <p>
 * At runtime the work item is dispatched to
 * {@code com.anax.kogito.autoconfigure.AnaxWorkItemHandler}.
 */
public class AnaxFunctionTypeHandler extends WorkItemTypeHandler {

    public static final String ANAX_SCHEME = "anax";
    public static final String PARAM_BEAN_NAME = "BeanName";
    public static final String PARAM_METHOD_NAME = "MethodName";

    @Override
    public String type() {
        return ANAX_SCHEME;
    }

    @Override
    public boolean isCustom() {
        return true;
    }

    @Override
    protected <T extends RuleFlowNodeContainerFactory<T, ?>> WorkItemNodeFactory<T> fillWorkItemHandler(
            Workflow workflow, ParserContext context,
            WorkItemNodeFactory<T> factory, FunctionDefinition functionDef) {

        String operationPath = FunctionTypeHandlerFactory.trimCustomOperation(functionDef);
        if (operationPath.startsWith("//")) {
            operationPath = operationPath.substring(2);
        }

        int slash = operationPath.indexOf('/');
        String beanName = slash >= 0 ? operationPath.substring(0, slash) : operationPath;
        String methodName = slash >= 0 ? operationPath.substring(slash + 1) : "execute";

        return factory
                .workName(ANAX_SCHEME)
                .workParameter(PARAM_BEAN_NAME, beanName)
                .workParameter(PARAM_METHOD_NAME, methodName);
    }
}
