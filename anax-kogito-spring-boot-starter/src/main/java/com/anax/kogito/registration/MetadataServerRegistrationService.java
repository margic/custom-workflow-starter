package com.anax.kogito.registration;

import com.anax.kogito.autoconfigure.AnaxKogitoProperties;
import com.anax.kogito.catalog.AnaxCatalogService;
import com.anax.kogito.catalog.CatalogModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Publishes the service catalog to the metadata server on
 * {@link ApplicationReadyEvent}.
 *
 * <p>
 * Registration is fire-and-forget: failure is logged but never prevents
 * startup.
 *
 * <p>
 * Enabled when {@code anax.metadata-server.url} is configured and
 * {@code anax.metadata-server.registration.enabled} is {@code true} (default).
 *
 * <p>
 * Example {@code application.yml}:
 * 
 * <pre>{@code
 * anax:
 *   metadata-server:
 *     url: http://metadata-platform:3001
 * }</pre>
 */
public class MetadataServerRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(MetadataServerRegistrationService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AnaxKogitoProperties properties;
    private final String metadataServerUrl;
    private final String serviceId;
    private final String instanceId;
    private final String version;
    private final AnaxCatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MetadataServerRegistrationService(
            AnaxKogitoProperties properties,
            AnaxCatalogService catalogService,
            ObjectMapper objectMapper,
            String serviceId,
            String version) {
        this.properties = properties;
        this.metadataServerUrl = properties.getMetadataServer().getUrl();
        this.serviceId = serviceId;
        this.instanceId = serviceId + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.version = version;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.getMetadataServer().getRegistration().isEnabled()) {
            log.debug("Metadata server registration is disabled via anax.metadata-server.registration.enabled=false");
            return;
        }
        try {
            register();
        } catch (Exception e) {
            log.warn("Metadata server registration failed — application startup continues. Reason: {}", e.getMessage());
        }
    }

    private void register() throws Exception {
        String url = metadataServerUrl.endsWith("/")
                ? metadataServerUrl + "api/registrations"
                : metadataServerUrl + "/api/registrations";

        CatalogModel.Catalog catalog = catalogService.getCatalog();

        RegistrationPayload.RegistrationRequest request = new RegistrationPayload.RegistrationRequest(
                serviceId,
                instanceId,
                version,
                Instant.now().toString(),
                catalog);

        String body = objectMapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 201) {
            RegistrationPayload.RegistrationResponse resp = objectMapper.readValue(response.body(),
                    RegistrationPayload.RegistrationResponse.class);
            log.info("Registered with metadata server: registrationId={}, status={}, instanceId={}",
                    resp.registrationId(), resp.status(), instanceId);
        } else {
            log.warn("Metadata server returned unexpected status {} during registration. Response: {}",
                    response.statusCode(), response.body());
        }
    }
}
