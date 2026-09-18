package com.rigour.merchant.infrastructure.integration;

import com.rigour.hr.api.v1.model.HrEmployeeIdentityView;
import com.rigour.merchant.application.port.out.CrmEmployeeClient;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** HR 身份只读调用，签名权限限定为身份核验。 */
@Component
public final class HttpCrmEmployeeClient implements CrmEmployeeClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI base;

    public HttpCrmEmployeeClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.hr.base-url:${rigour.crm.hr-base-url:${HR_PAYROLL_BASE_URL:http://rigour-hr-payroll-service:26889}}}")
                    String base) {
        this.signer = signer;
        this.base = URI.create(base.replaceAll("/+$", ""));
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Owner owner(String tenant, String code) {
        URI uri =
                UriComponentsBuilder.fromUri(base)
                        .path("/api/v1/hr/employee-identities/{code}")
                        .buildAndExpand(code)
                        .encode()
                        .toUri();
        Map<String, String> h = headers(tenant, uri);
        try {
            ApiResponse<HrEmployeeIdentityView> result =
                    client.get()
                            .uri(uri)
                            .headers(headers -> h.forEach(headers::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (result == null
                    || !"OK".equals(result.code())
                    || result.data() == null
                    || !code.equals(result.data().employeeCode()))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "客户主责员工暂时无法核验");
            var e = result.data();
            return new Owner(
                    e.employeeCode(),
                    e.employeeName(),
                    e.usable(),
                    e.unavailableReason(),
                    e.employeeRevision(),
                    e.departmentId(),
                    e.departmentName(),
                    e.departmentAncestorIds(),
                    e.organizationVersion(),
                    e.accessVersion());
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HR 员工核验暂不可用", e);
        }
    }

    @Override
    public List<Owner> search(String tenant, String keyword) {
        URI uri =
                UriComponentsBuilder.fromUri(base)
                        .path("/api/v1/hr/employee-identities")
                        .queryParam("keyword", keyword == null ? "" : keyword)
                        .queryParam("begin", 0)
                        .queryParam("step", 50)
                        .build()
                        .encode()
                        .toUri();
        try {
            ApiResponse<com.rigour.hr.api.v1.model.HrPageView<HrEmployeeIdentityView>> result =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers(tenant, uri).forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (result == null || !"OK".equals(result.code()) || result.data() == null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HR 员工目录暂不可用");
            return result.data().items().stream()
                    .map(
                            e ->
                                    new Owner(
                                            e.employeeCode(),
                                            e.employeeName(),
                                            e.usable(),
                                            e.unavailableReason(),
                                            e.employeeRevision(),
                                            e.departmentId(),
                                            e.departmentName(),
                                            e.departmentAncestorIds(),
                                            e.organizationVersion(),
                                            e.accessVersion()))
                    .toList();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HR 员工目录暂不可用", e);
        }
    }

    private Map<String, String> headers(String tenant, URI uri) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        h.put(
                RequestHeaders.PRINCIPAL_ID,
                UUID.nameUUIDFromBytes(
                                "rigour-crm-owner-reader"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString());
        h.put(RequestHeaders.TENANT_ID, tenant);
        h.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        h.put(RequestHeaders.SESSION_VERSION, "0");
        h.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        h.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        h.put(RequestHeaders.PERMISSIONS, "hr:employee:identity-read");
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return h;
    }
}
