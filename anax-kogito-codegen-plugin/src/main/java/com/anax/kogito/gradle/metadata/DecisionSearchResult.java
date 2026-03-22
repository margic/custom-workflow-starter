package com.anax.kogito.gradle.metadata;

/**
 * A decision returned from the metadata server search endpoint.
 */
public record DecisionSearchResult(
    String decisionId,
    String name,
    String namespace,
    String version,
    String status
) {}
