package com.rigour.order.infrastructure.integration;

import com.rigour.integration.api.v1.DhbOrderApi;
import com.rigour.integration.api.v1.model.DhbApiModels.CachedOrderFacts;
import com.rigour.order.application.port.out.DhbCachedOrderFactsClient;
import com.rigour.shared.context.OutboundContextHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public final class HttpDhbCachedOrderFactsClient implements DhbCachedOrderFactsClient {
    private static final Logger LOG = LoggerFactory.getLogger(HttpDhbCachedOrderFactsClient.class);
    private final URI base;
    private final RestClient client;
    private final TrustedContextSigner signer;

    public HttpDhbCachedOrderFactsClient(
            RestClient.Builder builder, TrustedContextSigner signer,
            @Value("${rigour.integration.base-url:http://rigour-integration-migration-service}") String baseUrl) {
        this.base = URI.create(baseUrl);
        this.signer = signer;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public List<Facts> read(String tenant, UUID connector, List<String> orderNos) {
        URI uri = base.resolve(DhbOrderApi.CACHED_ORDER_FACTS_PATH.replace("{connectorId}", connector.toString()));
        try {
            var response = client.post().uri(uri)
                    .headers(headers -> OutboundContextHeaders.service(signer, "POST", uri,
                            "rigour-order-dhb-cached-facts-reader", tenant,
                            "integration:dhb:cached-order-facts:read").forEach(headers::set))
                    .body(orderNos)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<List<CachedOrderFacts>>>() {});
            if (response != null && "OK".equals(response.code()) && response.data() != null)
                return response.data().stream()
                        .map(fact -> new Facts(fact.orderNo(), fact.customerName(), fact.employeeCode(),
                                fact.employeeName(), fact.documentDate(), fact.orderAmount()))
                        .toList();
        } catch (RestClientException e) {
            LOG.warn("订货宝缓存订单资料暂不可读取");
        }
        return List.of();
    }
}
