package com.rigour.merchant.infrastructure.integration;

import com.rigour.integration.api.v1.DhbIntegrationInternalApi;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.merchant.application.port.out.DhbCrmSyncTargetDiscoveryClient;
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

/** 通过内部可信契约发现 CRM_MASTER_DATA 同步目标。 */
public final class HttpDhbCrmSyncTargetDiscoveryClient implements DhbCrmSyncTargetDiscoveryClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpDhbCrmSyncTargetDiscoveryClient(RestClient.Builder builder,
                                               TrustedContextSigner signer,
                                               String integrationBaseUrl) {
        this.client = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedIntegrationRequest.baseUri(integrationBaseUrl);
    }

    @Override
    public List<SyncTargetView> discover(CallerIdentity caller) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(DhbIntegrationInternalApi.SYNC_TARGETS_PATH)
                .queryParam("objectType", "CRM_MASTER_DATA").build().encode().toUri();
        Map<String, String> context = SignedIntegrationRequest.signedHeaders(signer, "GET", uri, caller);
        List<SyncTargetView> response = client.get().uri(uri)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .headers(headers -> context.forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId())
                .retrieve().body(new ParameterizedTypeReference<>() { });
        return response == null ? List.of() : List.copyOf(response);
    }
}
