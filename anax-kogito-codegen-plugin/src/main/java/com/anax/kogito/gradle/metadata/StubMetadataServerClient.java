package com.anax.kogito.gradle.metadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory stub for testing and offline development.
 */
public class StubMetadataServerClient implements MetadataServerClient {

    private final Map<String, DecisionSearchResult> decisions = new HashMap<>();
    private final Map<String, byte[]> decisionDmnXml = new HashMap<>();
    private final Map<String, byte[]> mappingJoltSpecs = new HashMap<>();

    public StubMetadataServerClient withDecision(DecisionSearchResult decision, byte[] dmnXml) {
        decisions.put(decision.decisionId(), decision);
        decisionDmnXml.put(decision.decisionId(), dmnXml);
        return this;
    }

    public StubMetadataServerClient withMapping(String mappingId, byte[] joltSpec) {
        mappingJoltSpecs.put(mappingId, joltSpec);
        return this;
    }

    @Override
    public List<DecisionSearchResult> findDecisions(String namespace, String modelName) {
        List<DecisionSearchResult> results = new ArrayList<>();
        for (DecisionSearchResult d : decisions.values()) {
            if (namespace.equalsIgnoreCase(d.namespace())
                    && modelName.equalsIgnoreCase(d.name())) {
                results.add(d);
            }
        }
        return results;
    }

    @Override
    public Optional<byte[]> exportDecisionDmn(String decisionId) {
        return Optional.ofNullable(decisionDmnXml.get(decisionId));
    }

    @Override
    public Optional<byte[]> exportMappingJolt(String mappingId) {
        return Optional.ofNullable(mappingJoltSpecs.get(mappingId));
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
