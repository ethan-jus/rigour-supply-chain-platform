package com.rigour.integration.infrastructure.domain;

import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;

/** Integration 到 CRM 内部订货宝同步接口的 HTTP 客户端。 */
public final class HttpCrmDhbDomainSyncClient implements CrmDhbDomainSyncClient {
    private static final TypeReference<ApiResponse<SyncResult>> SYNC_RESULT_RESPONSE =
            new TypeReference<>() { };

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpCrmDhbDomainSyncClient(RestClient.Builder builder,
                                      TrustedContextSigner signer,
                                      String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "CRM");
    }

    @Override
    public SyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId, int maxPages) {
        if (connectorId == null || sourceTaskId == null) {
            throw new IllegalArgumentException("CRM同步connectorId和sourceTaskId不能为空");
        }
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/crm/dhb/sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<SyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new SyncCommand(connectorId, sourceTaskId, maxPages))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, SYNC_RESULT_RESPONSE, "CRM订货宝同步"));
        return SignedDomainRequest.required(response, "CRM");
    }

    private record SyncCommand(UUID connectorId, UUID sourceTaskId, Integer maxPages) { }
}
