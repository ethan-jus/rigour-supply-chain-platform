package com.rigour.integration.infrastructure.domain;

import com.rigour.integration.application.port.out.DhbDependencyTargets;
import com.rigour.order.api.v1.OrderSalesOrderApi;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.core.type.TypeReference;

import java.net.URI;
import java.util.*;

@Component
public class HttpDhbDependencyTargets implements DhbDependencyTargets {
    private static final TypeReference<ApiResponse<Map<String, Object>>> RESPONSE =
            new TypeReference<>() {};
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final Map<String, URI> bases;

    public HttpDhbDependencyTargets(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.order.base-url:http://rigour-order-center-service}") String order,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erp,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String crm) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.client = builder.clone().requestFactory(factory).build();
        this.signer = signer;
        this.bases =
                Map.of(
                        "ORDER",
                        SignedDomainRequest.baseUri(order, "Order"),
                        "ERP",
                        SignedDomainRequest.baseUri(erp, "ERP"),
                        "CRM",
                        SignedDomainRequest.baseUri(crm, "CRM"));
    }

    @Override
    public Target require(CallerIdentity caller, String type, Long id, Long productId) {
        String domain =
                "SALES_ORDER".equals(type) ? "ORDER" : "CUSTOMER".equals(type) ? "CRM" : "ERP";
        String path =
                switch (type) {
                    case "SALES_ORDER" -> OrderSalesOrderApi.BASE_PATH + "/" + id;
                    case "CUSTOMER" -> "/api/v1/crm/internal-customers/" + id;
                    case "WAREHOUSE" -> "/api/v1/erp/inventory-warehouses/" + id;
                    case "PRODUCT_SPU" -> "/api/v1/erp/product-management/products/" + id;
                    case "PRODUCT_SKU" ->
                            "/api/v1/erp/product-management/products/"
                                    + Objects.requireNonNull(productId, "请选择商品");
                    default -> throw new IllegalArgumentException("不支持的资料类型");
                };
        var uri = UriComponentsBuilder.fromUri(bases.get(domain)).path(path).build().toUri();
        var response =
                client.get()
                        .uri(uri)
                        .headers(
                                h ->
                                        SignedDomainRequest.signedHeaders(
                                                        signer, "GET", uri, caller)
                                                .forEach(h::set))
                        .exchange(
                                (q, r) -> SignedDomainRequest.readResponse(r, RESPONSE, "补录资料读取"));
        var data = SignedDomainRequest.required(response, domain);
        requireUsable(data, type);
        if ("PRODUCT_SKU".equals(type)) {
            if (!(data.get("variants") instanceof List<?> rows))
                throw new IllegalArgumentException("商品规格不可读取");
            data =
                    rows.stream()
                            .filter(Map.class::isInstance)
                            .map(v -> (Map<String, Object>) v)
                            .filter(v -> id.toString().equals(Objects.toString(v.get("id"), "")))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("规格不属于所选商品"));
        }
        if (!id.toString().equals(Objects.toString(data.get("id"), "")))
            throw new IllegalArgumentException("资料身份不一致");
        requireUsable(data, type);
        String key =
                switch (type) {
                    case "SALES_ORDER" -> "orderNo";
                    case "CUSTOMER" -> "customerCode";
                    case "WAREHOUSE" -> "warehouseCode";
                    case "PRODUCT_SKU" -> "variantCode";
                    default -> "productCode";
                };
        String code = Objects.toString(data.get(key), "");
        if (code.isBlank()) throw new IllegalArgumentException("资料缺少有效编号");
        return new Target(
                domain,
                "PRODUCT_SPU".equals(type)
                        ? "PRODUCT"
                        : "PRODUCT_SKU".equals(type) ? "PRODUCT_VARIANT" : type,
                id,
                code);
    }

    private static void requireUsable(Map<String, Object> data, String type) {
        String status =
                Objects.toString(
                        data.get("statusCode"), Objects.toString(data.get("orderStatusCode"), ""));
        if (Set.of("DISABLED", "DELETED", "CANCELLED", "DRAFT").contains(status))
            throw new IllegalArgumentException(
                    "SALES_ORDER".equals(type) ? "销售订单尚未提交或已取消，请先核对原单" : "资料已停用或删除，请选择有效资料");
    }
}
