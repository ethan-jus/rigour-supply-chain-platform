package com.rigour.hr.infrastructure.integration;

import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;
import com.rigour.settings.client.EffectiveDictionaryClient;
import com.rigour.shared.context.OutboundContextHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public final class HttpHrPayrollSource implements HrPayrollSource {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final EffectiveDictionaryClient dictionaries;
    private final String order, erp, sales;

    public HttpHrPayrollSource(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.sales.base-url:http://rigour-sales-work-service}") String sales,
            @Value("${rigour.order.base-url:http://rigour-order-center-service}") String order,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erp,
            @Value("${rigour.settings.base-url:http://rigour-business-settings-service}")
                    String settings) {
        this.client = builder.build();
        this.signer = signer;
        this.order = order;
        this.erp = erp;
        this.sales = sales;
        this.dictionaries =
                new EffectiveDictionaryClient(
                        builder, signer, settings, "rigour-hr-payroll-service");
    }

    public SalesPayrollEvidenceView performanceEvidence(
            String tenant, String employee, LocalDate from, LocalDate to) {
        var uri =
                UriComponentsBuilder.fromUriString(sales)
                        .path("/api/v1/sales/payroll-evidence")
                        .queryParam("employeeCode", employee)
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.service(
                        signer,
                        "GET",
                        uri,
                        "rigour-hr-payroll-service",
                        tenant,
                        "sales:payroll:source-read");
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<SalesPayrollEvidenceView>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("过程考核依据暂不可用");
        return response.data();
    }

    public List<OrderCollectionSourceView> collections(
            String tenant, String employee, LocalDate from, LocalDate to) {
        var uri =
                UriComponentsBuilder.fromUriString(order)
                        .path("/api/v1/orders/cost-sources/collections")
                        .queryParam("employeeCode", employee)
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.service(
                        signer,
                        "GET",
                        uri,
                        "rigour-hr-payroll-service",
                        tenant,
                        "order:payroll:source-read");
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<List<OrderCollectionSourceView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("回款考核依据暂不可用");
        return response.data();
    }

    public List<OrderCommissionSourceLineView> commissions(
            String tenant, String employee, String city, LocalDate through) {
        var uri =
                UriComponentsBuilder.fromUriString(order)
                        .path("/api/v1/orders/cost-sources/commissions")
                        .queryParamIfPresent("employeeCode", Optional.ofNullable(employee))
                        .queryParamIfPresent("cityCode", Optional.ofNullable(city))
                        .queryParam("through", through)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.service(
                        signer,
                        "GET",
                        uri,
                        "rigour-hr-payroll-service",
                        tenant,
                        "order:payroll:source-read");
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<List<OrderCommissionSourceLineView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("提成来源暂不可用");
        return response.data();
    }

    public Set<String> dictionary(String tenant, String code) {
        return dictionaries.activeCodes(tenant, code);
    }

    public List<ErpPayrollProductView> products(String tenant, String keyword, Long variant) {
        var builder =
                UriComponentsBuilder.fromUriString(erp)
                        .path("/api/v1/erp/payroll-products")
                        .queryParam("keyword", keyword);
        if (variant != null) builder.queryParam("productVariantId", variant);
        var uri = builder.build().encode().toUri();
        var headers =
                OutboundContextHeaders.service(
                        signer,
                        "GET",
                        uri,
                        "rigour-hr-payroll-service",
                        tenant,
                        "erp:payroll:product-read");
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<List<ErpPayrollProductView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("商品目录暂不可用");
        return response.data();
    }

    public void requireProduct(String tenant, Long product, Long variant, String unit) {
        if (products(tenant, "", variant).stream()
                .noneMatch(
                        p ->
                                Objects.equals(p.productId(), product)
                                        && Objects.equals(p.productVariantId(), variant)
                                        && Objects.equals(p.unitCode(), unit)))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品规格或计奖单位已变化，请重新选择", List.of());
    }
}
