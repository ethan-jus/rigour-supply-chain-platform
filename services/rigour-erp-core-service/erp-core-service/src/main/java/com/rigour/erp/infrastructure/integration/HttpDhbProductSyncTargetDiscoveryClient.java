package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.DhbProductSyncTargetDiscoveryClient;
import com.rigour.integration.api.v1.DhbIntegrationInternalApi;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 通过可信服务签名发现 Integration 商品主数据同步目标。 */
public final class HttpDhbProductSyncTargetDiscoveryClient
        implements DhbProductSyncTargetDiscoveryClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI integrationBaseUri;

    public HttpDhbProductSyncTargetDiscoveryClient(RestClient.Builder builder,
                                                   TrustedContextSigner signer,
                                                   String integrationBaseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.integrationBaseUri = SignedIntegrationRequest.baseUri(integrationBaseUrl);
    }

    @Override
    public List<SyncTargetView> discover(CallerIdentity serviceCaller) {
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbIntegrationInternalApi.SYNC_TARGETS_PATH)
                .queryParam("objectType", "PRODUCT_MASTER_DATA")
                .build().encode().toUri();
        Map<String, String> context = SignedIntegrationRequest.signedHeaders(
                signer, "GET", uri, serviceCaller);
        return restClient.get().uri(uri)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .headers(headers -> context.forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId())
                .retrieve().body(new ParameterizedTypeReference<>() { });
    }
}
