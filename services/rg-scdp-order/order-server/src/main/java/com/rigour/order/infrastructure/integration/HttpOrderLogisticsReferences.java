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

    public Set<String> validCodes(String tenant, String code) {
        return dictionary.activeCodes(tenant, code);
    }

    public List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action) {
        var builder =
                UriComponentsBuilder.fromUriString(erp)
                        .path("/api/v1/erp/logistics-cost-sources")
                        .queryParam("legCode", leg)
                        .queryParam("keyword", keyword)
                        .queryParam("action", action);
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
}
