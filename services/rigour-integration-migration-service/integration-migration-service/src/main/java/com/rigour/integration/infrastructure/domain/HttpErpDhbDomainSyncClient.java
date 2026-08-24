package com.rigour.integration.infrastructure.domain;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.integration.application.port.out.ErpDhbDomainSyncClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Integration 到 ERP 内部订货宝同步接口的 HTTP 客户端。 */
public final class HttpErpDhbDomainSyncClient implements ErpDhbDomainSyncClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpErpDhbDomainSyncClient(RestClient.Builder builder,
                                      TrustedContextSigner signer,
                                      String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "ERP");
    }

    @Override
    public ErpDataSyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                                  String objectType, int maxPages) {
        if (connectorId == null || sourceTaskId == null || objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("ERP同步connectorId、sourceTaskId和objectType不能为空");
        }
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/erp/dhb/sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<ErpDataSyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new SyncCommand(connectorId, sourceTaskId, objectType, maxPages))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return SignedDomainRequest.required(response, "ERP");
    }

    private record SyncCommand(UUID connectorId, UUID sourceTaskId, String objectType, Integer maxPages) { }
}
