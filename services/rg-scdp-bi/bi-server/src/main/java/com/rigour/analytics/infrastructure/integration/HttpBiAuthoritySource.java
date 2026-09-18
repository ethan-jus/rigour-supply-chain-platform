package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.application.port.out.BiAuthoritySource;
import com.rigour.shared.context.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/** 专用服务身份只读取订单冻结归属及 CRM 当前主责、地区树。 */
@Component
public final class HttpBiAuthoritySource implements BiAuthoritySource {
    private final TrustedContextSigner signer;
    private final RestClient client;
    private final Map<String, String> bases;
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpBiAuthoritySource(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.analytics.scope.order-base-url:http://rigour-order-center-service:26885}")
                    String order,
            @Value(
                            "${rigour.analytics.scope.crm-base-url:http://rigour-merchant-crm-service:26883}")
                    String crm) {
        this.signer = signer;
        this.bases =
                Map.of(
                        "ORDER",
                        base(order) + "/api/v1/orders/analytics-authority",
                        "CRM",
                        base(crm) + "/api/v1/crm/analytics-authority");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(15));
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String version(UUID tenant, String source) {
        return required(get(tenant, source, "/version"), "version");
    }

    @Override
    public Page page(UUID tenant, String source, long afterId) {
        var result = get(tenant, source, "?afterId=" + afterId + "&step=1000");
        return new Page(
                required(result, "version"),
                rows(result.path("items")),
                rows(result.path("regions")));
    }

    private JsonNode get(UUID tenant, String source, String suffix) {
        String base = bases.get(source);
        if (base == null) throw new IllegalArgumentException("未知归属来源");
        URI uri = URI.create(base + suffix);
        UUID service =
                UUID.nameUUIDFromBytes(
                        "rigour-bi-authority-projection"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var actor =
                new CallerIdentity(
                        "SERVICE",
                        service,
                        tenant,
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(
                                "ORDER".equals(source)
                                        ? "order:analytics:projection-read"
                                        : "crm:analytics:projection-read"));
        var result =
                client.get()
                        .uri(uri)
                        .headers(h -> signed(actor, uri, "GET").forEach(h::set))
                        .retrieve()
                        .body(JsonNode.class);
        if (result == null) throw new IllegalStateException("归属来源无响应");
        if (result.has("code")) {
            if (!"OK".equals(result.path("code").asText()))
                throw new IllegalStateException("归属来源读取失败");
            result = result.path("data");
        }
        return result;
    }

    private List<Map<String, Object>> rows(JsonNode node) {
        if (!node.isArray()) throw new IllegalStateException("归属来源分页结构无效");
        List<Map<String, Object>> values = new ArrayList<>();
        for (var item : node) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (var entry : item.properties()) {
                var v = entry.getValue();
                row.put(
                        entry.getKey(),
                        v.isNull()
                                ? null
                                : v.isArray()
                                        ? json.convertValue(v, List.class)
                                        : v.isNumber() ? v.numberValue() : v.asText());
            }
            values.add(row);
        }
        return values;
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        if (v == null || v.isBlank()) throw new IllegalStateException("归属来源缺少版本");
        return v;
    }

    private static String base(String value) {
        URI uri = URI.create(value);
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null) throw new IllegalArgumentException("归属来源地址无效");
        return value.replaceAll("/+$", "");
    }

    private Map<String, String> signed(CallerIdentity caller, URI uri, String method) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        headers.put(RequestHeaders.PRINCIPAL_ID, caller.principalId().toString());
        headers.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        if (caller.userId() != null)
            headers.put(RequestHeaders.USER_ID, caller.userId().toString());
        headers.put(RequestHeaders.SESSION_ID, caller.sessionId().toString());
        headers.put(RequestHeaders.SESSION_VERSION, Long.toString(caller.sessionVersion()));
        headers.put(
                RequestHeaders.USER_SECURITY_VERSION, Long.toString(caller.userSecurityVersion()));
        headers.put(
                RequestHeaders.TENANT_POLICY_VERSION, Long.toString(caller.tenantPolicyVersion()));
        headers.put(RequestHeaders.ROLES, String.join(",", caller.roles()));
        headers.put(RequestHeaders.PERMISSIONS, String.join(",", caller.permissions()));
        var signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }
}
