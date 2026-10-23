package com.rigour.hr.infrastructure.integration;

import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.application.port.out.HrExpenseCostSource;
import com.rigour.hr.application.service.HrExpenseRules;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 使用当前调用者身份核验原单，关联不会扩大其原有成本权限。 */
@Component
public final class HttpHrExpenseCostSource implements HrExpenseCostSource {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final Map<String, String> bases;

    public HttpHrExpenseCostSource(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erp,
            @Value("${rigour.order.base-url:http://rigour-order-center-service}") String order) {
        this.signer = signer;
        bases = Map.of("ERP", erp, "ORDER", order);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        client = builder.requestFactory(factory).build();
    }

    public void verify(HrExpenseCommand c) {
        var ref = c.expense() == null ? null : c.expense().relatedCost();
        if (ref == null) return;
        String path =
                "ORDER".equals(ref.source())
                        ? "/api/v1/orders/logistics-costs/"
                        : "/api/v1/erp/costs/"
                                + switch (ref.category()) {
                                    case "PURCHASE" -> "purchases/";
                                    case "RENTAL" -> "warehouse-rentals/";
                                    case "MARKETING" -> "marketing/";
                                    default -> throw bad("原费用类别无效");
                                };
        var uri = URI.create(bases.get(ref.source()).replaceAll("/+$", "") + path + ref.id());
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        JsonNode response;
        try {
            response =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers.forEach(h::set))
                            .retrieve()
                            .body(JsonNode.class);
        } catch (RestClientException e) {
            throw bad("原费用暂不可核验或无访问权限，请核对后重试");
        }
        if (response == null || !"OK".equals(response.path("code").asText())) throw bad("原费用核验失败");
        validateOriginal(c, response.path("data"));
    }

    static void validateOriginal(HrExpenseCommand c, JsonNode row) {
        var ref = c.expense().relatedCost();
        var data = row.path("data");
        if (!"CONFIRMED".equals(row.path("statusCode").asText())
                || ref.revision() != row.path("revision").asLong(-1))
            throw bad("原费用状态或版本已变化，请重新选择");
        String month = data.path("costMonth").asText("");
        if (month.isBlank()) month = data.path("costDate").asText("");
        if (month.length() >= 7) month = month.substring(0, 7);
        if (!c.cityCode().equals(data.path("cityCode").asText())
                || !HrExpenseRules.month(c).equals(month)) throw bad("本期仅支持同城市、同月份原费用的全额报销关联");
        try {
            if (!data.hasNonNull("amount")
                    || new BigDecimal(data.path("amount").asText()).compareTo(c.amount()) != 0)
                throw bad("报销金额须等于原费用金额，部分报销请先核对");
        } catch (NumberFormatException e) {
            throw bad("原费用金额无效，请先核对原单");
        }
        String category =
                "ORDER".equals(ref.source())
                        ? "freight"
                        : switch (ref.category()) {
                            case "PURCHASE" -> "purchase";
                            case "MARKETING" -> "marketing";
                            case "RENTAL" ->
                                    switch (data.path("rental").path("premisesType").asText()) {
                                        case "WAREHOUSE" -> "warehouse";
                                        case "OFFICE" -> "office";
                                        default -> throw bad("原场地用途未明确，请先核对原单");
                                    };
                            default -> throw bad("原费用类别无效");
                        };
        if (!category.equals(c.expense().costCategory())) throw bad("成本归集类别须与原费用用途一致");
        if ("ORDER".equals(ref.source())
                && !"COMPANY".equals(data.path("logistics").path("payerCode").asText()))
            throw bad("只能关联公司承担的运费");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
