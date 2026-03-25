package com.anax.kogito.gradle.metadata;

/**
 * Factory that creates a MetadataServerClient based on the configured URL.
 * Returns a StubMetadataServerClient for "stub" URL, otherwise
 * HttpMetadataServerClient.
 */
public class MetadataServerClientFactory {

    private MetadataServerClientFactory() {
    }

    public static MetadataServerClient create(String metadataServerUrl) {
        if ("stub".equalsIgnoreCase(metadataServerUrl)) {
            return new StubMetadataServerClient();
        }
        return new HttpMetadataServerClient(metadataServerUrl);
    }
}
