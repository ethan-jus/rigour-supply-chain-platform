package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.DhbSupplySyncTargetDiscoveryClient;
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

/** 通过 Integration 内部契约发现 SUPPLY_CHAIN_DATA 同步目标。 */
public final class HttpDhbSupplySyncTargetDiscoveryClient implements DhbSupplySyncTargetDiscoveryClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI baseUri;
    public HttpDhbSupplySyncTargetDiscoveryClient(RestClient.Builder builder, TrustedContextSigner signer,
                                                  String integrationBaseUrl) {
        this.client = Objects.requireNonNull(builder).build();
        this.signer = Objects.requireNonNull(signer);
        this.baseUri = SignedIntegrationRequest.baseUri(integrationBaseUrl);
    }
    @Override public List<SyncTargetView> discover(CallerIdentity caller) {
        URI uri = UriComponentsBuilder.fromUri(baseUri).path(DhbIntegrationInternalApi.SYNC_TARGETS_PATH)
                .queryParam("objectType", "SUPPLY_CHAIN_DATA").build().encode().toUri();
        Map<String, String> headers = SignedIntegrationRequest.signedHeaders(signer, "GET", uri, caller);
        return client.get().uri(uri).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .headers(target -> headers.forEach(target::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId()).retrieve()
                .body(new ParameterizedTypeReference<>() { });
    }
}
