package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.api.v1.model.ErpCostEmployeeView;
import com.rigour.erp.api.v1.model.ErpCostSourceView;
import com.rigour.erp.application.port.out.CostReferenceSource;
import com.rigour.hr.api.v1.model.HrCostEmployeeView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.OutboundContextHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Component
public final class HttpCostReferenceSource implements CostReferenceSource {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String hr, order;

    public HttpCostReferenceSource(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String hr,
            @Value("${rigour.order.base-url:http://rigour-order-center-service}") String order) {
        this.client = builder.build();
        this.signer = signer;
        this.hr = hr;
        this.order = order;
    }

    public List<ErpCostEmployeeView> employees(String action) {
        var uri =
                UriComponentsBuilder.fromUriString(hr)
                        .path("/api/v1/hr/cost-references/employees")
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<List<HrCostEmployeeView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("员工权限目录暂不可用");
        return response.data().stream()
                .map(
                        e ->
                                new ErpCostEmployeeView(
                                        e.employeeCode(),
                                        e.employeeName(),
                                        e.departmentId(),
                                        e.departmentName()))
                .toList();
    }

    public List<ErpCostSourceView> deliveries(String keyword, List<Long> ids) {
        return deliveries(keyword, ids, null, 0, "order:logistics-cost:read");
    }

    public List<ErpCostSourceView> deliveries(
            String keyword, List<Long> ids, String city, int begin, String action) {
        var builder =
                UriComponentsBuilder.fromUriString(order)
                        .path("/api/v1/orders/cost-sources/deliveries")
                        .queryParam("keyword", keyword)
                        .queryParam("action", action);
        builder.queryParam("cityCode", city).queryParam("begin", Math.max(0, begin));
        if (!ids.isEmpty()) builder.queryParam("ids", ids);
        var uri = builder.build().encode().toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<List<OrderDeliveryCostSourceView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("订单运输来源暂不可用");
        return response.data().stream()
                .map(
                        d ->
                                new ErpCostSourceView(
                                        d.id(),
                                        d.documentNo(),
                                        "DELIVERY",
                                        d.cityCode(),
                                        d.warehouseId(),
                                        d.warehouseId(),
                                        String.valueOf(d.warehouseId()),
                                        null,
                                        d.customerCode(),
                                        d.customerName(),
                                        d.statusCode(),
                                        d.sourceSystem(),
                                        d.originalNo(),
                                        d.documentDate(),
                                        null,
                                        d.destinationAddress(),
                                        null,
                                        null))
                .toList();
    }

    private String crm;

    @org.springframework.beans.factory.annotation.Autowired
    void crmAddress(
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String value) {
        crm = value;
    }

    private record City(String code, String name) {}

    public String city(String code, String action) {
        var uri =
                UriComponentsBuilder.fromUriString(crm)
                        .path("/api/v1/crm/cost-city-references")
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(new ParameterizedTypeReference<ApiResponse<List<City>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("授权城市目录暂不可用");
        return response.data().stream()
                .filter(c -> c.code().equals(code))
                .findFirst()
                .orElseThrow(
                        () -> new com.rigour.shared.context.AuthorizationDeniedException(action))
                .name();
    }
}
