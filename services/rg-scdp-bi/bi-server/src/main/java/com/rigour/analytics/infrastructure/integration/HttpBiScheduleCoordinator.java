package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.application.port.out.BiScheduleCoordinator;
import com.rigour.shared.context.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class HttpBiScheduleCoordinator implements BiScheduleCoordinator {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String base;

    public HttpBiScheduleCoordinator(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value(
                            "${rigour.analytics.scope.integration-base-url:http://rigour-integration-migration-service}")
                    String base) {
        this.signer = signer;
        this.base = base.replaceAll("/+$", "") + "/internal/v1/integration/sync-schedules/bi";
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(15));
        client = builder.requestFactory(factory).build();
    }

    public List<Work> due() {
        var rows = request(null, "GET", "/due", null);
        if (!rows.isArray()) throw new IllegalStateException("派发列表响应无效");
        var result = new ArrayList<Work>();
        for (var row : rows)
            result.add(
                    new Work(
                            UUID.fromString(row.path("tenantId").asText()),
                            row.path("version").asLong()));
        return result;
    }

    public boolean managed(UUID tenant) {
        return booleanResult(request(tenant, "GET", "/managed", null));
    }

    public boolean claim(UUID tenant, long version, UUID token) {
        return booleanResult(
                request(tenant, "POST", "/claim", Map.of("version", version, "token", token)));
    }

    public void heartbeat(UUID tenant, UUID token) {
        request(tenant, "POST", "/" + token + "/heartbeat", Map.of());
    }

    public void complete(UUID tenant, UUID token, String status, String message) {
        request(
                tenant,
                "POST",
                "/" + token + "/complete",
                Map.of("status", status, "message", message == null ? "" : message));
    }

    private boolean booleanResult(JsonNode node) {
        if (!node.isBoolean()) throw new IllegalStateException("派发状态响应无效");
        return node.asBoolean();
    }

    private JsonNode request(UUID tenant, String method, String path, Object body) {
        var uri = URI.create(base + path);
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        headers.put(
                RequestHeaders.PRINCIPAL_ID,
                UUID.nameUUIDFromBytes("rigour-bi-schedule-worker".getBytes(StandardCharsets.UTF_8))
                        .toString());
        if (tenant != null) headers.put(RequestHeaders.TENANT_ID, tenant.toString());
        headers.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        headers.put(RequestHeaders.SESSION_VERSION, "0");
        headers.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        headers.put(RequestHeaders.ROLES, "");
        headers.put(RequestHeaders.PERMISSIONS, "integration:schedule:execute-bi");
        var signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        var request =
                client.method(org.springframework.http.HttpMethod.valueOf(method))
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set));
        if (body != null)
            request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        var result = request.retrieve().body(JsonNode.class);
        if (result == null || !"OK".equals(result.path("code").asText()) || !result.has("data"))
            throw new IllegalStateException("集中调度响应无效");
        return result.path("data");
    }
}
