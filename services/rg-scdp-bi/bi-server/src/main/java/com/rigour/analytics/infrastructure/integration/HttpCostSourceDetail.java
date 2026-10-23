package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.application.port.out.CostSourceDetail;
import com.rigour.analytics.application.service.CostDashboardFailure;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.*;

@Component
public class HttpCostSourceDetail implements CostSourceDetail {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final Map<String, String> bases;

    public HttpCostSourceDetail(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.analytics.scope.erp-base-url:http://rigour-erp-core-service}")
                    String erp,
            @Value("${rigour.analytics.scope.order-base-url:http://rigour-order-center-service}")
                    String order,
            @Value("${rigour.analytics.scope.hr-base-url:http://rigour-hr-payroll-service}")
                    String hr) {
        this.signer = signer;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        client = builder.requestFactory(factory).build();
        bases = Map.of("ERP", erp, "ORDER", order, "HR", hr);
    }

    public State verify(String source, String category, String id) {
        UUID.fromString(id);
        String path =
                switch (source) {
                    case "HR" ->
                            "PAYROLL".equals(category)
                                    ? "/api/v1/hr/payroll/"
                                    : "/api/v1/hr/expenses/";
                    case "ORDER" -> "/api/v1/orders/logistics-costs/";
                    case "ERP" ->
                            "/api/v1/erp/costs/"
                                    + switch (category) {
                                        case "PURCHASE" -> "purchases";
                                        case "LOSS" -> "losses";
                                        case "MARKETING" -> "marketing";
                                        case "RENTAL" -> "warehouse-rentals";
                                        default -> throw new IllegalArgumentException("未知成本类别");
                                    }
                                    + "/";
                    default -> throw new IllegalArgumentException("未知成本来源");
                };
        var uri = URI.create(bases.get(source).replaceAll("/+$", "") + path + id);
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        JsonNode result;
        try {
            result =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers.forEach(h::set))
                            .retrieve()
                            .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 403 || status == 404)
                throw new BusinessException(ErrorCode.NOT_FOUND, "原单不存在或当前无访问权限", List.of());
            if (status == 401) throw new AuthorizationDeniedException("原单访问凭证已失效，请重新登录");
            throw new CostDashboardFailure("COST_SOURCE_UNAVAILABLE", 503, "原单服务暂不可用，请稍后重试", e);
        }
        if (result == null || !"OK".equals(result.path("code").asText()))
            throw new CostDashboardFailure("COST_SOURCE_UNAVAILABLE", 503, "源单据状态暂不可核验");
        var data = result.path("data");
        if (!data.has("revision") || !data.has("statusCode"))
            throw new CostDashboardFailure("COST_SOURCE_UNAVAILABLE", 503, "源单据状态暂不可核验");
        String status = data.path("statusCode").asText();
        if ("HR".equals(source) && "PAYROLL".equals(category) && "PAID".equals(status))
            status = "CONFIRMED";
        return new State(status, data.path("revision").asLong());
    }
}
