package com.anax.kogito.gradle.metadata;

import java.util.List;
import java.util.Optional;

/**
 * Client interface for the Metadata Management Platform.
 * Used at build time by the Gradle plugin to resolve governance assets
 * referenced by custom URI schemes in .sw.json workflow definitions.
 */
public interface MetadataServerClient {

    List<DecisionSearchResult> findDecisions(String namespace, String modelName);

    Optional<byte[]> exportDecisionDmn(String decisionId);

    Optional<byte[]> exportMappingJolt(String mappingId);

    boolean isAvailable();
}
