package com.rigour.integration.infrastructure.domain;

import com.rigour.erp.api.v1.ErpProductProjectionApi;
import com.rigour.erp.api.v1.model.ExternalProductResolveCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolveRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.erp.api.v1.model.ExternalProductRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncResult;
import com.rigour.integration.application.port.out.ErpProductProjectionClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;

/** Integration 到 ERP 商品同步接口的 HTTP 客户端。 */
public final class HttpErpProductProjectionClient implements ErpProductProjectionClient {
    private static final TypeReference<ApiResponse<ExternalProductSyncResult>> PRODUCT_SYNC_RESPONSE =
            new TypeReference<>() { };
    private static final TypeReference<ApiResponse<List<ExternalProductResolvedView>>> PRODUCT_RESOLVE_RESPONSE =
            new TypeReference<>() { };

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpErpProductProjectionClient(RestClient.Builder builder,
                                          TrustedContextSigner signer,
                                          String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "ERP");
    }

    @Override
    public ExternalProductSyncResult sync(CallerIdentity caller, String sourceSystem,
                                          List<ExternalProductRowCommand> rows) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(ErpProductProjectionApi.BASE_PATH + "/external-sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<ExternalProductSyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new ExternalProductSyncCommand(sourceSystem, rows))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, PRODUCT_SYNC_RESPONSE, "ERP商品同步"));
        return SignedDomainRequest.required(response, "ERP商品同步");
    }

    @Override
    public List<ExternalProductResolvedView> resolve(CallerIdentity caller, String preferredSourceSystem,
                                                     List<ExternalProductResolveRowCommand> rows) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(ErpProductProjectionApi.BASE_PATH + "/source-resolve")
                .build()
                .encode()
                .toUri();
        ApiResponse<List<ExternalProductResolvedView>> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new ExternalProductResolveCommand(preferredSourceSystem, rows))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, PRODUCT_RESOLVE_RESPONSE, "ERP商品解析"));
        return SignedDomainRequest.required(response, "ERP商品解析");
    }
}
