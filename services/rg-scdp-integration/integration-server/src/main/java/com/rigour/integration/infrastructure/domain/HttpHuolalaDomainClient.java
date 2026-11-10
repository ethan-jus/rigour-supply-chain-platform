package com.rigour.integration.infrastructure.domain;

import com.rigour.hr.api.v1.model.*;
import com.rigour.integration.api.v1.model.HuolalaModels.EmployeeCandidate;
import com.rigour.integration.application.port.out.HuolalaDomainClient;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.core.type.TypeReference;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Component
public final class HttpHuolalaDomainClient implements HuolalaDomainClient {
    private final RestClient http;
    private final TrustedContextSigner signer;
    private final URI hr, order;

    public HttpHuolalaDomainClient(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String hr,
            @Value("${rigour.order.base-url:http://rigour-order-center-service}") String order) {
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = builder.clone().requestFactory(factory).build();
        this.signer = signer;
        this.hr = SignedDomainRequest.baseUri(hr, "HR");
        this.order = SignedDomainRequest.baseUri(order, "Order");
    }

    @Override
    public List<EmployeeCandidate> employees(CallerIdentity caller, String keyword) {
        URI uri =
                UriComponentsBuilder.fromUri(hr)
                        .path("/api/v1/hr/employees")
                        .queryParam("begin", 0)
                        .queryParam("step", 50)
                        .queryParam("keyword", keyword == null ? "" : keyword)
                        .build()
                        .encode()
                        .toUri();
        ApiResponse<HrPageView<HrEmployeeView>> response =
                http.get()
                        .uri(uri)
                        .headers(
                                h ->
                                        SignedDomainRequest.signedHeaders(
                                                        signer, "GET", uri, caller)
                                                .forEach(h::set))
                        .exchange(
                                (request, r) ->
                                        SignedDomainRequest.readResponse(
                                                r,
                                                new TypeReference<
                                                        ApiResponse<
                                                                HrPageView<HrEmployeeView>>>() {},
                                                "员工查询"));
        return SignedDomainRequest.required(response, "员工查询").items().stream()
                .map(HttpHuolalaDomainClient::candidate)
                .toList();
    }

    @Override
    public EmployeeCandidate employee(CallerIdentity caller, Long id) {
        URI uri = hr.resolve("/api/v1/hr/employees/" + id);
        ApiResponse<HrEmployeeView> response =
                http.get()
                        .uri(uri)
                        .headers(
                                h ->
                                        SignedDomainRequest.signedHeaders(
                                                        signer, "GET", uri, caller)
                                                .forEach(h::set))
                        .exchange(
                                (request, r) ->
                                        SignedDomainRequest.readResponse(
                                                r,
                                                new TypeReference<ApiResponse<HrEmployeeView>>() {},
                                                "员工查询"));
        return candidate(SignedDomainRequest.required(response, "员工查询"));
    }

    @Override
    public Row receive(CallerIdentity caller, ApiSource source) {
        URI uri = order.resolve("/api/v1/orders/logistics-costs/imports/api-sources");
        ApiResponse<Row> response =
                http.post()
                        .uri(uri)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .headers(
                                h ->
                                        SignedDomainRequest.signedHeaders(
                                                        signer, "POST", uri, caller)
                                                .forEach(h::set))
                        .body(source)
                        .exchange(
                                (request, r) ->
                                        SignedDomainRequest.readResponse(
                                                r,
                                                new TypeReference<ApiResponse<Row>>() {},
                                                "物流来源写入"));
        return SignedDomainRequest.required(response, "物流来源写入");
    }

    @Override
    public void employeeMapping(CallerIdentity caller, EmployeeMappingUpdate update) {
        URI uri = order.resolve("/api/v1/orders/logistics-costs/imports/api-employee-mappings");
        ApiResponse<Void> response =
                http.post()
                        .uri(uri)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .headers(
                                h ->
                                        SignedDomainRequest.signedHeaders(
                                                        signer, "POST", uri, caller)
                                                .forEach(h::set))
                        .body(update)
                        .exchange(
                                (request, r) ->
                                        SignedDomainRequest.readResponse(
                                                r,
                                                new TypeReference<ApiResponse<Void>>() {},
                                                "运输记录员工关联更新"));
        if (response == null || !"OK".equals(response.code()))
            throw new IllegalStateException("运输记录员工关联更新未完成");
    }

    @Override
    public void voucher(CallerIdentity caller, VoucherUpdate update) {
        URI uri = order.resolve("/api/v1/orders/logistics-costs/imports/api-vouchers");
        ApiResponse<Void> response =
                http.post()
                        .uri(uri)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .headers(
                                h ->
                                        SignedDomainRequest.signedHeaders(
                                                        signer, "POST", uri, caller)
                                                .forEach(h::set))
                        .body(update)
                        .exchange(
                                (request, r) ->
                                        SignedDomainRequest.readResponse(
                                                r,
                                                new TypeReference<ApiResponse<Void>>() {},
                                                "平台凭证保存"));
        if (response == null || !"OK".equals(response.code()))
            throw new IllegalStateException("平台凭证保存未完成");
    }

    private static EmployeeCandidate candidate(HrEmployeeView e) {
        return new EmployeeCandidate(
                e.id(),
                e.employeeCode(),
                e.employeeName(),
                e.mobile(),
                e.departmentName(),
                e.employmentStatus());
    }
}
