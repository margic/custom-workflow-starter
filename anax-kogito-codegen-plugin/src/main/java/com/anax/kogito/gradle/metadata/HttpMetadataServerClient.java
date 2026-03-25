package com.anax.kogito.gradle.metadata;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * HTTP-based implementation of MetadataServerClient.
 * Uses java.net.http.HttpClient (Java 11+) — no external dependencies beyond
 * Gson.
 */
public class HttpMetadataServerClient implements MetadataServerClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson;

    public HttpMetadataServerClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.gson = new Gson();
    }

    @Override
    public List<DecisionSearchResult> findDecisions(String namespace, String modelName) {
        String url = baseUrl + "/api/decisions?namespace="
                + URLEncoder.encode(namespace, StandardCharsets.UTF_8)
                + "&name="
                + URLEncoder.encode(modelName, StandardCharsets.UTF_8)
                + "&status=active";
        HttpResponse<String> response = doGet(url);

        if (response.statusCode() == 404) {
            return Collections.emptyList();
        }
        validateResponse(response, url);

        return parseDecisionSearchResults(response.body());
    }

    @Override
    public Optional<byte[]> exportDecisionDmn(String decisionId) {
        String url = baseUrl + "/api/decisions/"
                + URLEncoder.encode(decisionId, StandardCharsets.UTF_8)
                + "/export?format=dmn";
        HttpResponse<byte[]> response = doGetBytes(url);

        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        validateResponse(response, url);

        return Optional.of(response.body());
    }

    @Override
    public Optional<byte[]> exportMappingJolt(String mappingId) {
        String url = baseUrl + "/api/mappings/"
                + URLEncoder.encode(mappingId, StandardCharsets.UTF_8)
                + "/export?format=jolt";
        HttpResponse<byte[]> response = doGetBytes(url);

        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        validateResponse(response, url);

        return Optional.of(response.body());
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpResponse<Void> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/health"))
                            .timeout(Duration.ofSeconds(5))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private List<DecisionSearchResult> parseDecisionSearchResults(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        JsonArray data = root.getAsJsonArray("data");
        if (data == null) {
            return Collections.emptyList();
        }
        List<DecisionSearchResult> results = new ArrayList<>();
        for (JsonElement el : data) {
            JsonObject obj = el.getAsJsonObject();
            results.add(new DecisionSearchResult(
                    getStringOrNull(obj, "decisionId"),
                    getStringOrNull(obj, "name"),
                    getStringOrNull(obj, "namespace"),
                    getStringOrNull(obj, "version"),
                    getStringOrNull(obj, "status")));
        }
        return results;
    }

    private String getStringOrNull(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : null;
    }

    private HttpResponse<String> doGet(String url) {
        try {
            return httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MetadataServerException(
                    "Failed to connect to metadata server: " + e.getMessage(), 0, url);
        }
    }

    private HttpResponse<byte[]> doGetBytes(String url) {
        try {
            return httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(REQUEST_TIMEOUT)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new MetadataServerException(
                    "Failed to connect to metadata server: " + e.getMessage(), 0, url);
        }
    }

    private void validateResponse(HttpResponse<?> response, String url) {
        if (response.statusCode() >= 400) {
            throw new MetadataServerException(
                    "Metadata server returned HTTP " + response.statusCode() + " for " + url,
                    response.statusCode(), url);
        }
    }
}
