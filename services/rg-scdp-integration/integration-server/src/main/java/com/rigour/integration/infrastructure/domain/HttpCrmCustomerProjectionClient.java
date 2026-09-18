package com.rigour.integration.infrastructure.domain;

import com.rigour.integration.application.port.out.CrmCustomerProjectionClient;
import com.rigour.merchant.api.v1.CrmCustomerProjectionApi;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
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

/** Integration 到 CRM 客户/门店同步接口的 HTTP 客户端。 */
public final class HttpCrmCustomerProjectionClient implements CrmCustomerProjectionClient {
    private static final TypeReference<ApiResponse<ExternalCrmAreaSyncResult>> AREA_SYNC_RESPONSE =
            new TypeReference<>() { };
    private static final TypeReference<ApiResponse<ExternalCrmCustomerSyncResult>> CUSTOMER_SYNC_RESPONSE =
            new TypeReference<>() { };

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpCrmCustomerProjectionClient(RestClient.Builder builder,
                                           TrustedContextSigner signer,
                                           String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "CRM");
    }

    @Override
    public ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                               List<ExternalCrmAreaRowCommand> rows) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(CrmCustomerProjectionApi.BASE_PATH + "/areas/external-sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<ExternalCrmAreaSyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new ExternalCrmAreaSyncCommand(sourceSystem, rows))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, AREA_SYNC_RESPONSE, "CRM地区同步"));
        return SignedDomainRequest.required(response, "CRM地区同步");
    }

    @Override
    public ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                              List<ExternalCrmCustomerRowCommand> rows) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(CrmCustomerProjectionApi.BASE_PATH + "/external-sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<ExternalCrmCustomerSyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new ExternalCrmCustomerSyncCommand(sourceSystem, rows))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, CUSTOMER_SYNC_RESPONSE, "CRM客户同步"));
        return SignedDomainRequest.required(response, "CRM客户同步");
    }
}
