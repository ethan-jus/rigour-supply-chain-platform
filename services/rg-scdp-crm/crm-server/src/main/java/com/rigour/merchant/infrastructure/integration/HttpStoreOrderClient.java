package com.rigour.merchant.infrastructure.integration;

import com.rigour.merchant.application.port.out.StoreOrderClient;
import com.rigour.order.api.v1.model.StoreOrderFacts;
import com.rigour.shared.context.OutboundContextHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpStoreOrderClient implements StoreOrderClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String base;
    public HttpStoreOrderClient(RestClient.Builder builder, TrustedContextSigner signer,
            @Value("${rigour.order.base-url:http://rigour-order-center-service}") String base) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.requestFactory(factory).build(); this.signer = signer; this.base = base.replaceAll("/+$", "");
    }
    @Override public StoreOrderFacts facts(String tenant, String store, String ownership) {
        URI uri = URI.create(base + "/internal/v1/orders/store-facts/" + store + "?ownershipId=" + ownership);
        var response = client.post().uri(uri).headers(h -> OutboundContextHeaders.service(signer,
                "POST", uri, "rigour-merchant-crm-service", tenant, "order:store:source-read").forEach(h::set))
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<StoreOrderFacts>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("订单事实响应不完整");
        var facts = response.data();
        if (!tenant.equals(facts.tenantId()) || !store.equals(facts.storeId()) || !ownership.equals(facts.ownershipId())
                || facts.checkedAt() == null || !"STORE_ORDER_V1".equals(facts.ruleVersion()))
            throw new IllegalStateException("订单事实范围不一致");
        return facts;
    }
}
