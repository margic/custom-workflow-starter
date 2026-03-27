package com.anax.kogito.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public final class CatalogModel {

        private CatalogModel() {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Catalog(
                        String schemaVersion,
                        String generatedAt,
                        List<AssetEntry> assets) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AssetEntry(
                        String uri,
                        String assetId,
                        String assetType,
                        String checksum,
                        String resolvedAt) {
        }

        public record SchemeEntry(
                        String scheme, String description, String uriPattern,
                        List<ParameterEntry> parameters, String handler) {
        }

        public record ParameterEntry(
                        String name, String description, String source) {
        }

        public record DmnModelEntry(
                        String namespace, String name, String uri,
                        String resource, List<String> inputs, List<String> outputs) {
        }

        public record WorkflowEntry(
                        String id, String name, String resource,
                        List<EventEntry> events, List<FunctionEntry> functions) {
        }

        public record EventEntry(
                        String name, String type, String kind) {
        }

        public record FunctionEntry(
                        String name, String operation) {
        }

        public record SpringBeanEntry(
                        String beanName, String className,
                        List<MethodEntry> methods, String uri) {
        }

        public record MethodEntry(
                        String name, String parameterType, String returnType) {
        }
}
