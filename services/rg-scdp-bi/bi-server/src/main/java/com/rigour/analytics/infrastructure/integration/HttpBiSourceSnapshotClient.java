package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.application.port.out.BiSourceSnapshotClient;
import com.rigour.shared.context.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/** BI v1 专用源投影客户端。金额和大整数只接受精确文本，避免 JSON 浮点转换损失。 */
@Component
public final class HttpBiSourceSnapshotClient implements BiSourceSnapshotClient {
    private final TrustedContextSigner signer;
    private final RestClient client;
    private final Map<String, String> bases;

    public HttpBiSourceSnapshotClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.analytics.scope.order-base-url:http://rigour-order-center-service}")
                    String order,
            @Value(
                            "${rigour.analytics.scope.crm-base-url:http://rigour-merchant-crm-service}")
                    String crm,
            @Value("${rigour.analytics.scope.erp-base-url:http://rigour-erp-core-service}")
                    String erp,
            @Value(
                            "${rigour.analytics.scope.integration-base-url:http://rigour-integration-migration-service}")
                    String integration,
            @Value("${rigour.analytics.scope.hr-base-url:http://rigour-hr-payroll-service}")
                    String hr,
            @Value(
                            "${rigour.analytics.scope.sales-base-url:http://rigour-sales-work-service}")
                    String sales,
            RestClient.Builder restClientBuilder) {
        this.signer = signer;
        bases =
                Map.of(
                        "ORDER",
                        base(order) + "/api/v1/orders/analytics-source/",
                        "CRM",
                        base(crm) + "/api/v1/crm/analytics-source/",
                        "ERP",
                        base(erp) + "/api/v1/erp/analytics-source/",
                        "INTEGRATION",
                        base(integration) + "/api/v1/integration/analytics-source/",
                        "HR",
                        base(hr) + "/api/v1/hr/analytics-source/",
                        "SALES",
                        base(sales) + "/api/v1/sales/analytics-source/");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        client = restClientBuilder.requestFactory(factory).build();
    }

    public String version(UUID tenant, String source, String dataset) {
        return required(get(tenant, source, dataset, "/version"), "version");
    }

    public Page page(UUID tenant, String source, String dataset, String after) {
        if (!after.isEmpty() && !after.matches("[0-9A-Fa-f]{1,32}"))
            throw new IllegalArgumentException("源游标无效");
        var result = get(tenant, source, dataset, "?after=" + after);
        var rows = result.path("items");
        if (!rows.isArray()) throw new IllegalStateException("源投影分页无效");
        List<Map<String, String>> items = new ArrayList<>();
        for (var row : rows) {
            Map<String, String> item = new LinkedHashMap<>();
            for (var entry : row.properties()) {
                var value = entry.getValue();
                if (!value.isNull() && !value.isString())
                    throw new IllegalStateException("源投影必须使用精确文本");
                item.put(entry.getKey(), value.isNull() ? null : value.asText());
            }
            items.add(item);
        }
        return new Page(required(result, "version"), items);
    }

    private JsonNode get(UUID tenant, String source, String dataset, String suffix) {
        String base = bases.get(source);
        if (base == null || !dataset.matches("[A-Z][A-Z0-9_]{1,99}"))
            throw new IllegalArgumentException("未知源投影");
        URI uri = URI.create(base + dataset + suffix);
        UUID service =
                UUID.nameUUIDFromBytes(
                        "rigour-bi-domain-source-projection"
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
                        Set.of(source.toLowerCase(Locale.ROOT) + ":analytics:source-read"));
        var result =
                client.get()
                        .uri(uri)
                        .headers(h -> signed(actor, uri, "GET").forEach(h::set))
                        .retrieve()
                        .body(JsonNode.class);
        if (result == null) throw new IllegalStateException("源投影无响应");
        if (result.has("code")) {
            if (!"OK".equals(result.path("code").asText()))
                throw new IllegalStateException("源投影读取失败");
            result = result.path("data");
        }
        return result;
    }

    private static String required(JsonNode node, String key) {
        String v = node.path(key).asText(null);
        if (v == null || v.isBlank()) throw new IllegalStateException("源版本缺失");
        return v;
    }

    private static String base(String value) {
        URI u = URI.create(value);
        if (!Set.of("http", "https").contains(u.getScheme())
                || u.getHost() == null
                || u.getUserInfo() != null
                || u.getQuery() != null) throw new IllegalArgumentException("源投影地址无效");
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
