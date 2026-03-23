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
 * {@code map://} URI scheme.
 *
 * <p>
 * URI format: {@code map://mappingName}
 * <ul>
 * <li>mappingName — the mapping identifier on the metadata server
 * (e.g. {@code x9-field-mapping})</li>
 * </ul>
 *
 * <p>
 * Registered via SPI:
 * {@code META-INF/services/org.kie.kogito.serverless.workflow.parser.FunctionTypeHandler}
 *
 * <p>
 * At runtime the work item is dispatched to
 * {@code com.anax.kogito.autoconfigure.MapWorkItemHandler}.
 */
public class MapFunctionTypeHandler extends WorkItemTypeHandler {

    public static final String MAP_SCHEME = "map";
    public static final String PARAM_MAPPING_NAME = "MappingName";

    @Override
    public String type() {
        return MAP_SCHEME;
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

        return factory
                .workName(MAP_SCHEME)
                .workParameter(PARAM_MAPPING_NAME, operationPath);
    }
}
