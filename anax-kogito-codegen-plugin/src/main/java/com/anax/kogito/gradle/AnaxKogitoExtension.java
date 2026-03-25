package com.anax.kogito.gradle;

import org.gradle.api.provider.Property;

/**
 * Plugin extension for configuring the Anax Kogito codegen plugin.
 *
 * <pre>
 * anaxKogito {
 *     metadataServerUrl = 'http://localhost:3001'
 *     kogitoVersion = '10.1.0'
 * }
 * </pre>
 */
public abstract class AnaxKogitoExtension {

    /**
     * URL of the Metadata Management Platform.
     * Set to 'stub' for offline/test builds.
     * Can also be set via METADATA_SERVER_URL environment variable.
     */
    public abstract Property<String> getMetadataServerUrl();

    /**
     * Kogito version to use for codegen dependencies.
     * Defaults to the version in gradle.properties.
     */
    public abstract Property<String> getKogitoVersion();
}
