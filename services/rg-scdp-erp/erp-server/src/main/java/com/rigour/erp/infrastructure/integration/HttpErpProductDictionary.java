package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.ErpProductDictionary;
import com.rigour.settings.client.EffectiveDictionaryClient;
import com.rigour.shared.context.TrustedContextSigner;

import org.springframework.web.client.RestClient;

import java.util.Set;

/** 只读租户业务字典适配器；停用结果和服务不可用都不能自动放行新输入。 */
public final class HttpErpProductDictionary implements ErpProductDictionary {
    private final EffectiveDictionaryClient client;

    public HttpErpProductDictionary(
            RestClient.Builder builder, TrustedContextSigner signer, String baseUrl) {
        client = new EffectiveDictionaryClient(builder, signer, baseUrl, "rigour-erp-core-service");
    }

    @Override
    public Set<String> validCodes(String tenant, String dictionaryCode) {
        return client.activeCodes(tenant, dictionaryCode);
    }
}
