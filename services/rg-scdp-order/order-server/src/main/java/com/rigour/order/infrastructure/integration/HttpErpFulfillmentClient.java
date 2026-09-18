package com.rigour.order.infrastructure.integration;

import com.rigour.erp.api.v1.model.SalesExecutionReceipt;
import com.rigour.order.application.port.out.ErpFulfillmentClient;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** 扣库使用原用户身份；收据与仓库有效性查询使用能力受限的 SERVICE 身份。 */
@Component
public final class HttpErpFulfillmentClient implements ErpFulfillmentClient {
    private final RestClient client;
    private final URI base;
    private final TrustedContextSigner signer;

    public HttpErpFulfillmentClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.order.erp-execution-base-url:${ERP_BASE_URL:http://rigour-erp-core-service:26884}}")
                    String base) {
        this.signer = signer;
        this.base = URI.create(base.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(this.base.getScheme()))
            throw new IllegalArgumentException("ERP 地址必须为 HTTP");
        var f =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        f.setReadTimeout(Duration.ofSeconds(15));
        client = RestClient.builder().requestFactory(f).build();
    }

    public Receipt execute(CallerIdentity actor, String id) {
        UUID.fromString(id);
        URI uri = base.resolve("/api/v1/erp/order-executions/" + id);
        try {
            ApiResponse<SalesExecutionReceipt> r =
                    client.post()
                            .uri(uri)
                            .headers(h -> headers("POST", uri, actor).forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (r == null || !"OK".equals(r.code()) || r.data() == null) throw unavailable();
            return receipt(r.data());
        } catch (HttpClientErrorException.Forbidden e) {
            throw new AuthorizationDeniedException("order:outbound:confirm");
        } catch (RestClientException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "ERP 执行结果未确认，可安全重试查询", e);
        }
    }

    public Optional<Receipt> receipt(String tenant, String id) {
        UUID.fromString(id);
        URI uri = base.resolve("/internal/v1/erp/order-executions/" + id + "/receipt");
        try {
            ApiResponse<SalesExecutionReceipt> r =
                    client.get()
                            .uri(uri)
                            .headers(
                                    h ->
                                            headers(
                                                            "GET",
                                                            uri,
                                                            reader(
                                                                    tenant,
                                                                    "erp:execution:receipt-read"))
                                                    .forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (r == null || !"OK".equals(r.code())) throw unavailable();
            return Optional.ofNullable(r.data()).map(HttpErpFulfillmentClient::receipt);
        } catch (RestClientException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "ERP 回执暂时不可查询，停止新执行", e);
        }
    }

    public void requireWarehouse(String tenant, long id) {
        if (id < 1) throw new IllegalArgumentException("仓库 ID 无效");
        URI uri = base.resolve("/internal/v1/erp/warehouses/" + id + "/usable");
        try {
            ApiResponse<Boolean> r =
                    client.get()
                            .uri(uri)
                            .headers(
                                    h ->
                                            headers(
                                                            "GET",
                                                            uri,
                                                            reader(
                                                                    tenant,
                                                                    "erp:warehouse:identity-read"))
                                                    .forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (r == null || !"OK".equals(r.code())) throw unavailable();
            if (!Boolean.TRUE.equals(r.data())) throw new IllegalArgumentException("仓库不存在或已停用");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "仓库暂时不可核验", e);
        }
    }

    public List<com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption> warehouses(
            String tenant) {
        URI uri = base.resolve("/api/v1/erp/authorization/references");
        try {
            ApiResponse<List<com.rigour.erp.api.v1.model.AuthorizationReferenceView>> r =
                    client.get()
                            .uri(uri)
                            .headers(
                                    h ->
                                            headers(
                                                            "GET",
                                                            uri,
                                                            reader(
                                                                    tenant,
                                                                    "supply:scope:reference-read"))
                                                    .forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (r == null || !"OK".equals(r.code()) || r.data() == null) throw unavailable();
            return r.data().stream()
                    .filter(w -> "ACTIVE".equals(w.status()))
                    .map(
                            w ->
                                    new com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption(
                                            Long.parseLong(w.key()), w.name()))
                    .toList();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "可选仓库暂不可查询", e);
        }
    }

    private static Receipt receipt(SalesExecutionReceipt r) {
        return new Receipt(
                r.executionId(),
                r.orderId(),
                r.warehouseId(),
                r.stockOutId(),
                r.stockOutNo(),
                r.stockOutTime());
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ERP 返回无效结果");
    }

    private Map<String, String> headers(String method, URI uri, CallerIdentity c) {
        Map<String, String> h = new LinkedHashMap<>();
        put(h, RequestHeaders.PRINCIPAL_SCOPE, c.principalScope());
        put(h, RequestHeaders.PRINCIPAL_ID, c.principalId());
        put(h, RequestHeaders.TENANT_ID, c.tenantId());
        put(h, RequestHeaders.USER_ID, c.userId());
        put(h, RequestHeaders.SESSION_ID, c.sessionId());
        put(h, RequestHeaders.SESSION_VERSION, c.sessionVersion());
        put(h, RequestHeaders.USER_SECURITY_VERSION, c.userSecurityVersion());
        put(h, RequestHeaders.TENANT_POLICY_VERSION, c.tenantPolicyVersion());
        put(h, RequestHeaders.PERMISSIONS, String.join(",", c.permissions()));
        var s = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, s.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, s.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, s.signature());
        return h;
    }

    private static void put(Map<String, String> h, String key, Object value) {
        if (value != null) h.put(key, value.toString());
    }

    private static CallerIdentity reader(String tenant, String permission) {
        return new CallerIdentity(
                "SERVICE",
                UUID.nameUUIDFromBytes(
                        "order-fulfillment-recovery"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.fromString(tenant),
                null,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of(),
                Set.of(permission));
    }
}
