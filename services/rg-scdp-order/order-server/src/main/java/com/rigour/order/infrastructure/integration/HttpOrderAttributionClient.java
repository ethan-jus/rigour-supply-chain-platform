package com.rigour.order.infrastructure.integration;

import com.rigour.merchant.api.v1.model.*;
import com.rigour.order.application.port.out.OrderAttributionClient;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** 原用户只能选择自己当前可见客户；归属本身由 CRM/HR 返回，不采信前端字段。 */
@Component
public final class HttpOrderAttributionClient implements OrderAttributionClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI base;

    public HttpOrderAttributionClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.order.crm-attribution-base-url:${CRM_BASE_URL:http://rigour-merchant-crm-service}}")
                    String base,
            RestClient.Builder restClientBuilder) {
        this.signer = signer;
        this.base = URI.create(base.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(this.base.getScheme()))
            throw new IllegalArgumentException("CRM 地址必须为 HTTP");
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = restClientBuilder.requestFactory(factory).build();
    }

    @Override
    public CustomerOrderAttributionView resolve(CallerIdentity actor, long id) {
        if (actor == null || actor.tenantId() == null)
            throw new AuthorizationDeniedException("tenant-caller");
        try {
            if ("TENANT".equals(actor.principalScope())) {
                URI lookup = base.resolve("/api/v1/crm/internal-customers/" + id);
                ApiResponse<InternalCustomerDetailView> result =
                        client.get()
                                .uri(lookup)
                                .headers(h -> headers(actor, lookup).forEach(h::set))
                                .retrieve()
                                .body(new ParameterizedTypeReference<>() {});
                if (result == null
                        || !"OK".equals(result.code())
                        || result.data() == null
                        || result.data().id() != id)
                    throw new AuthorizationDeniedException("crm:customer:read");
            }
            URI uri = base.resolve("/internal/v1/crm/customers/" + id + "/order-attribution");
            CallerIdentity service =
                    new CallerIdentity(
                            "SERVICE",
                            UUID.nameUUIDFromBytes(
                                    "rigour-order-attribution-reader"
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            actor.tenantId(),
                            null,
                            null,
                            UUID.randomUUID(),
                            0,
                            0,
                            0,
                            Set.of(),
                            Set.of("crm:customer:attribution-read"));
            ApiResponse<CustomerOrderAttributionView> response =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers(service, uri).forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (response == null
                    || !"OK".equals(response.code())
                    || response.data() == null
                    || !actor.tenantId().toString().equals(response.data().tenantId())
                    || response.data().customerId() != id)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "订单归属核验返回无效结果");
            return response.data();
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.NotFound e) {
            throw new AuthorizationDeniedException("crm:customer:read");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "客户归属暂不可核验，请稍后重试", e);
        }
    }

    @Override
    public com.rigour.merchant.api.v1.model.CustomerPaymentOwnerView paymentOwner(CallerIdentity actor,long id,java.time.Instant at) {
        var service=new CallerIdentity("SERVICE",UUID.nameUUIDFromBytes("order-receipt-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            actor.tenantId(),null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("crm:customer:attribution-read"));
        URI uri=org.springframework.web.util.UriComponentsBuilder.fromUri(base)
            .path("/internal/v1/crm/customers/"+id+"/payment-owner").queryParam("at",at.toString()).build().encode().toUri();
        ApiResponse<com.rigour.merchant.api.v1.model.CustomerPaymentOwnerView> response=client.get().uri(uri)
            .headers(h->headers(service,uri).forEach(h::set)).retrieve().body(new ParameterizedTypeReference<>(){});
        if(response==null||!"OK".equals(response.code())||response.data()==null)throw new IllegalStateException("CRM历史归属未返回有效结果");
        return response.data();
    }

    private Map<String, String> headers(CallerIdentity c, URI uri) {
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
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return h;
    }

    private static void put(Map<String, String> h, String key, Object value) {
        if (value != null) h.put(key, value.toString());
    }
}
