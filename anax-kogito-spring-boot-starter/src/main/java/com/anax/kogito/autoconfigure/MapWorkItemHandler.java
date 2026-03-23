package com.anax.kogito.autoconfigure;

import org.kie.kogito.internal.process.workitem.KogitoWorkItem;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemManager;
import org.kie.kogito.internal.process.workitem.Policy;
import org.kie.kogito.internal.process.workitem.WorkItemTransition;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime handler for {@code map://} custom functions.
 *
 * STUB implementation. The Jolt transformation engine will be wired in a
 * later iteration. For now, the handler verifies the Jolt spec exists on the
 * classpath and passes input data through unchanged.
 *
 * Jolt specs are placed at {@code META-INF/anax/mappings/{mappingName}.json}
 * by the {@code resolveGovernanceAssets} Gradle task at build time.
 *
 * Registered as the {@code "map"} handler via
 * {@link AnaxKogitoAutoConfiguration}.
 */
public class MapWorkItemHandler extends DefaultKogitoWorkItemHandler {

    private static final Logger log = LoggerFactory.getLogger(MapWorkItemHandler.class);

    static final String PARAM_MAPPING_NAME = "MappingName";

    private final ResourceLoader resourceLoader;

    public MapWorkItemHandler(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public Optional<WorkItemTransition> activateWorkItemHandler(
            KogitoWorkItemManager manager,
            KogitoWorkItemHandler handler,
            KogitoWorkItem workItem,
            WorkItemTransition transition) {

        String mappingName = (String) workItem.getParameter(PARAM_MAPPING_NAME);

        Map<String, Object> inputs = new HashMap<>(workItem.getParameters());
        inputs.remove(PARAM_MAPPING_NAME);
        inputs.remove("TaskName");

        Resource spec = resourceLoader.getResource(
                "classpath:META-INF/anax/mappings/" + mappingName + ".json");

        if (spec.exists()) {
            log.info("map:// handler: Jolt spec found for '{}' but Jolt execution is not yet implemented. "
                    + "Returning input data unchanged.", mappingName);
        } else {
            log.warn("map:// handler: Jolt spec not found for '{}'. Returning input data unchanged.", mappingName);
        }

        // STUB: pass through input unchanged — replace with Jolt transformation later
        manager.completeWorkItem(workItem.getStringId(), inputs,
                transition.policies().toArray(Policy[]::new));
        return Optional.empty();
    }
}
