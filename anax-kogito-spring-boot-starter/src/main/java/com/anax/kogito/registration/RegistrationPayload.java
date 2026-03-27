package com.anax.kogito.registration;

import com.anax.kogito.catalog.CatalogModel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTOs for the {@code POST /api/registrations} endpoint on the metadata server.
 *
 * The request wraps the service identity fields around the existing
 * {@link CatalogModel.Catalog} (schemaVersion 2.0 unified assets array).
 */
public final class RegistrationPayload {

    private RegistrationPayload() {
    }

    /**
     * Request body sent to {@code POST /api/registrations}.
     */
    public record RegistrationRequest(
            String serviceId,
            String instanceId,
            String version,
            String registeredAt,
            CatalogModel.Catalog catalog) {
    }

    /**
     * Response body from {@code POST /api/registrations} — 201 Created.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegistrationResponse(
            String registrationId,
            String status,
            String receivedAt) {
    }
}
