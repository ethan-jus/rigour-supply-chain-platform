package com.rigour.order.infrastructure.integration;

import com.rigour.order.api.v1.model.LogisticsCostSourceView;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.settings.client.EffectiveDictionaryClient;
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
import java.util.Set;

@Component
public final class HttpOrderLogisticsReferences implements OrderLogisticsReferences {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final EffectiveDictionaryClient dictionary;
    private final String erp;
    private String crm;

    public HttpOrderLogisticsReferences(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erp,
            @Value("${rigour.settings.base-url:http://rigour-business-settings-service}")
                    String settings) {
        this.client = builder.build();
        this.signer = signer;
        this.erp = erp;
        this.dictionary =
                new EffectiveDictionaryClient(
                        builder, signer, settings, "rigour-order-center-service");
    }

    @org.springframework.beans.factory.annotation.Autowired
    void crmAddress(
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String value) {
        this.crm = value;
    }

    private String hr;

    @org.springframework.beans.factory.annotation.Autowired
    void hrAddress(@Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String value) {
        this.hr = value;
    }

    @Override
    public List<com.rigour.order.api.v1.model.LogisticsCostEmployeeView> employees(String action) {
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
                                        ApiResponse<
                                                List<
                                                        com.rigour.order.api.v1.model
                                                                .LogisticsCostEmployeeView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("员工权限目录暂不可用");
        return response.data();
    }

    public String party(String kind, Long id, String city, String action) {
        var builder =
                UriComponentsBuilder.fromUriString("suppliers".equals(kind) ? erp : crm)
                        .path(
                                "suppliers".equals(kind)
                                        ? "/api/v1/erp/logistics-cost-supplier"
                                        : "/api/v1/crm/cost-customer-references")
                        .queryParam("id", id)
                        .queryParam("action", action);
        if (!"suppliers".equals(kind)) builder.queryParam("cityCode", city);
        var uri = builder.build().encode().toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        if ("suppliers".equals(kind)) {
            var response =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers.forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<ApiResponse<String>>() {});
            if (response == null || !"OK".equals(response.code()) || response.data() == null)
                throw new IllegalStateException("供应商引用暂不可用");
            return response.data();
        }
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<
                                                List<
                                                        com.rigour.order.api.v1.model
                                                                .LogisticsCostPartyView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("客户引用暂不可用");
        return response.data().stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new com.rigour.shared.core.exception.BusinessException(
                                        com.rigour.shared.core.api.ErrorCode.BAD_REQUEST,
                                        "客户不存在、归属城市不符或无权限",
                                        List.of()))
                .name();
    }

    public String warehouse(Long id, String action) {
        return warehouseDetail(id, action).name();
    }

    public com.rigour.order.api.v1.model.LogisticsCostWarehouseView warehouseDetail(
            Long id, String action) {
        var uri =
                UriComponentsBuilder.fromUriString(erp)
                        .path("/api/v1/erp/logistics-cost-warehouse-detail")
                        .queryParam("id", id)
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
                                        ApiResponse<
                                                com.rigour.order.api.v1.model
                                                        .LogisticsCostWarehouseView>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("仓库引用暂不可用");
        return response.data();
    }

    public Set<String> validCodes(String tenant, String code) {
        return dictionary.activeCodes(tenant, code);
    }

    public List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action) {
        return sources(leg, keyword, ids, action, null, 0);
    }

    public List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action, String city, int begin) {
        var builder =
                UriComponentsBuilder.fromUriString(erp)
                        .path("/api/v1/erp/logistics-cost-sources")
                        .queryParam("legCode", leg)
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
                                        ApiResponse<List<LogisticsCostSourceView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("物流来源暂不可用");
        return response.data();
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
