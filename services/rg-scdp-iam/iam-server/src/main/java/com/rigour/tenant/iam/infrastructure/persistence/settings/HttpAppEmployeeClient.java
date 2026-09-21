package com.rigour.tenant.iam.infrastructure.persistence.settings;

import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.tenant.iam.application.port.out.AppEmployeeClient;

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
import java.util.function.Function;
import java.util.stream.Collectors;

/** HR 返回版本化身份；请求级核验失败时关闭普通业务资格，不使用旧姓名推定身份。 */
@Component
public final class HttpAppEmployeeClient implements AppEmployeeClient {
    private static final UUID SERVICE_ID =
            UUID.nameUUIDFromBytes(
                    "rigour-iam-employee-reader".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI base;

    public HttpAppEmployeeClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.iam.hr-base-url:${HR_PAYROLL_BASE_URL:http://rigour-hr-payroll-service}}")
                    String baseUrl,
            RestClient.Builder restClientBuilder) {
        this.signer = signer;
        this.base = URI.create(baseUrl.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getUserInfo() != null)
            throw new IllegalArgumentException("HR 地址配置无效");
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = restClientBuilder.requestFactory(factory).build();
    }

    @Override
    public Employee employee(UUID tenant, String code) {
        URI uri =
                UriComponentsBuilder.fromUri(base)
                        .path("/api/v1/hr/employee-identities/{code}")
                        .buildAndExpand(code)
                        .encode()
                        .toUri();
        try {
            return body(
                    client.get()
                            .uri(uri)
                            .headers(h -> headers(tenant, "GET", uri).forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<ApiResponse<Employee>>() {}));
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    @Override
    public Map<String, Employee> employees(UUID tenant, List<String> codes) {
        if (codes.isEmpty()) return Map.of();
        if (codes.size() > 100) throw new IllegalArgumentException("单次核验最多 100 个员工");
        URI uri =
                UriComponentsBuilder.fromUri(base)
                        .path("/api/v1/hr/employee-identities/resolve")
                        .build()
                        .toUri();
        try {
            List<Employee> result =
                    body(
                            client.post()
                                    .uri(uri)
                                    .headers(h -> headers(tenant, "POST", uri).forEach(h::set))
                                    .body(codes)
                                    .retrieve()
                                    .body(
                                            new ParameterizedTypeReference<
                                                    ApiResponse<List<Employee>>>() {}));
            return result.stream()
                    .collect(
                            Collectors.toUnmodifiableMap(
                                    Employee::employeeCode, Function.identity()));
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    @Override
    public Page search(UUID tenant, String keyword, int begin, int step) {
        URI uri =
                UriComponentsBuilder.fromUri(base)
                        .path("/api/v1/hr/employee-identities")
                        .queryParam("keyword", keyword == null ? "" : keyword)
                        .queryParam("begin", begin)
                        .queryParam("step", step)
                        .build()
                        .encode()
                        .toUri();
        try {
            return body(
                    client.get()
                            .uri(uri)
                            .headers(h -> headers(tenant, "GET", uri).forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<ApiResponse<Page>>() {}));
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private Map<String, String> headers(UUID tenant, String method, URI uri) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        headers.put(RequestHeaders.PRINCIPAL_ID, SERVICE_ID.toString());
        headers.put(RequestHeaders.TENANT_ID, tenant.toString());
        headers.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        headers.put(RequestHeaders.SESSION_VERSION, "0");
        headers.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        headers.put(RequestHeaders.PERMISSIONS, "hr:employee:identity-read");
        var signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private static <T> T body(ApiResponse<T> response) {
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HR 身份核验未返回有效结果");
        return response.data();
    }

    private static ResponseStatusException unavailable(Exception e) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HR 身份暂时无法核验，请稍后重试", e);
    }
}
