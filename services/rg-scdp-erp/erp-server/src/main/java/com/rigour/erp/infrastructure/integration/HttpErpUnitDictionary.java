package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.ErpUnitDictionary;
import com.rigour.settings.client.EffectiveDictionaryClient;
import com.rigour.shared.context.TrustedContextSigner;

import org.springframework.web.client.RestClient;

import java.util.Set;

/** 只读租户单位字典适配器，停用结果和服务不可用都不能自动放行新输入。 */
public final class HttpErpUnitDictionary implements ErpUnitDictionary {
    private final EffectiveDictionaryClient client;

    public HttpErpUnitDictionary(
            RestClient.Builder builder, TrustedContextSigner signer, String baseUrl) {
        client = new EffectiveDictionaryClient(builder, signer, baseUrl, "rigour-erp-core-service");
    }

    public Set<String> validUnits(String tenant) {
        return client.activeCodes(tenant, "PRODUCT_UNIT");
    }
}
