package com.anax.kogito.gradle.metadata;

/**
 * Thrown when the metadata server returns an unexpected error (not 404).
 */
public class MetadataServerException extends RuntimeException {

    private final int statusCode;
    private final String url;

    public MetadataServerException(String message, int statusCode, String url) {
        super(message);
        this.statusCode = statusCode;
        this.url = url;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getUrl() {
        return url;
    }
}
