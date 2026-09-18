package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.OrderExecutionClient;
import com.rigour.order.api.v1.model.FulfillmentExecutionView;
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

/** 从 Order 读取已持久化执行意图，保留原用户身份以再次核验当前授权。 */
@Component
public final class HttpOrderExecutionClient implements OrderExecutionClient {
    private final RestClient client;
    private final URI base;
    private final TrustedContextSigner signer;

    public HttpOrderExecutionClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.erp.order-execution-base-url:${ORDER_BASE_URL:http://rigour-order-center-service:26885}}")
                    String base) {
        this.signer = signer;
        this.base = URI.create(base.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(this.base.getScheme()))
            throw new IllegalArgumentException("Order 地址必须为 HTTP");
        var f =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        f.setReadTimeout(Duration.ofSeconds(10));
        client = RestClient.builder().requestFactory(f).build();
    }

    public FulfillmentExecutionView claim(CallerIdentity actor, String id) {
        UUID.fromString(id);
        URI uri = base.resolve("/internal/v1/order/fulfillments/" + id + "/claim");
        try {
            ApiResponse<FulfillmentExecutionView> r =
                    client.post()
                            .uri(uri)
                            .headers(h -> headers("POST", uri, actor).forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (r == null
                    || !"OK".equals(r.code())
                    || r.data() == null
                    || !id.equals(r.data().executionId())
                    || !actor.tenantId().toString().equals(r.data().tenantId()))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order 返回无效执行意图");
            return r.data();
        } catch (HttpClientErrorException.Forbidden e) {
            throw new AuthorizationDeniedException("order:outbound:confirm");
        } catch (RestClientException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "订单执行暂时不可核验，未执行扣库", e);
        }
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
}
