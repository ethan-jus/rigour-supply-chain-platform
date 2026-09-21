package com.rigour.merchant.infrastructure.integration;

import com.rigour.merchant.application.port.out.CustomerAssignmentTargetClient;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** 保留原操作者的签名上下文；IAM 在线复核其会话和权限，不能伪造 SERVICE 的用户权限。 */
@Component
public final class HttpCustomerAssignmentTargetClient implements CustomerAssignmentTargetClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI base;

    public HttpCustomerAssignmentTargetClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.supply-authorization.iam-base-url:${IAM_BASE_URL:http://rigour-tenant-iam-service}}")
                    String base,
            RestClient.Builder restClientBuilder) {
        this.signer = signer;
        this.base = URI.create(base.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(this.base.getScheme())
                || this.base.getUserInfo() != null) throw new IllegalArgumentException("IAM 地址无效");
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = restClientBuilder.requestFactory(factory).build();
    }

    public CustomerAssignmentTargetView byUser(String tenant, UUID userId) {
        return read(tenant, "userId", userId.toString());
    }

    public CustomerAssignmentTargetView byEmployee(String tenant, String employeeCode) {
        return read(tenant, "employeeCode", employeeCode);
    }

    private CustomerAssignmentTargetView read(String tenant, String parameter, String value) {
        var caller = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(caller.principalScope())
                || !tenant.equals(caller.tenantId().toString()))
            throw new AuthorizationDeniedException("customer-assignment-target");
        URI uri =
                UriComponentsBuilder.fromUri(base)
                        .path("/internal/v1/iam/supply/customer-assignment-target")
                        .queryParam(parameter, value)
                        .build()
                        .encode()
                        .toUri();
        var h = headers(caller, uri);
        try {
            var result =
                    client.get()
                            .uri(uri)
                            .headers(headers -> h.forEach(headers::set))
                            .retrieve()
                            .body(CustomerAssignmentTargetView.class);
            if (result == null
                    || !caller.tenantId().equals(result.tenantId())
                    || result.regionLimit() == null
                    || ("userId".equals(parameter)
                            && !value.equals(Objects.toString(result.userId(), null)))
                    || ("employeeCode".equals(parameter) && !value.equals(result.employeeCode())))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "目标用户授权响应不一致");
            return result;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403)
                throw new AuthorizationDeniedException("customer-assignment-target");
            if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 404)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标用户或员工关联无效", ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "目标用户权限暂无法核验", ex);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "目标用户权限暂无法核验", ex);
        }
    }

    private Map<String, String> headers(CallerIdentity caller, URI uri) {
        var h = new LinkedHashMap<String, String>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        h.put(RequestHeaders.PRINCIPAL_ID, caller.principalId().toString());
        h.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        h.put(RequestHeaders.USER_ID, caller.userId().toString());
        h.put(RequestHeaders.SESSION_ID, caller.sessionId().toString());
        h.put(RequestHeaders.SESSION_VERSION, Long.toString(caller.sessionVersion()));
        h.put(RequestHeaders.USER_SECURITY_VERSION, Long.toString(caller.userSecurityVersion()));
        h.put(RequestHeaders.TENANT_POLICY_VERSION, Long.toString(caller.tenantPolicyVersion()));
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return h;
    }
}
