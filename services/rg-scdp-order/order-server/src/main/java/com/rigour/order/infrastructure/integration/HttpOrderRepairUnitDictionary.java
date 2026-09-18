package com.rigour.order.infrastructure.integration;

import com.rigour.order.application.port.out.OrderRepairUnitDictionary;
import com.rigour.settings.client.EffectiveDictionaryClient;
import com.rigour.shared.context.TrustedContextSigner;

import org.springframework.web.client.RestClient;

import java.util.Set;

/** 订单普通录入与历史商品修复共用租户单位校验，不自动补建字典条目。 */
public final class HttpOrderRepairUnitDictionary implements OrderRepairUnitDictionary {
    private final EffectiveDictionaryClient client;

    public HttpOrderRepairUnitDictionary(
            RestClient.Builder builder, TrustedContextSigner signer, String baseUrl) {
        client =
                new EffectiveDictionaryClient(
                        builder, signer, baseUrl, "rigour-order-center-service");
    }

    public Set<String> validUnits(String tenantId) {
        return client.activeCodes(tenantId, "PRODUCT_UNIT");
    }
}
