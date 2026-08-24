package com.rigour.integration.infrastructure.domain;

import com.rigour.erp.api.v1.ErpStockOutOrderApi;
import com.rigour.erp.api.v1.ErpTransferOrderApi;
import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.integration.application.port.out.ErpStockOutProjectionClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Integration 到 ERP 外部出库投影 HTTP 客户端。 */
public final class HttpErpStockOutProjectionClient implements ErpStockOutProjectionClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpErpStockOutProjectionClient(RestClient.Builder builder,
                                           TrustedContextSigner signer,
                                           String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "ERP");
    }

    @Override
    public InternalStockOutOrderDetailView confirmExternalStockOut(
            CallerIdentity caller, ExternalStockOutProjectionCommand command) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("ERP外部出库投影必须携带租户上下文");
        }
        if (command == null) throw new IllegalArgumentException("ERP外部出库投影请求不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(ErpStockOutOrderApi.BASE_PATH)
                .path("/external-confirmations")
                .build()
                .encode()
                .toUri();
        ApiResponse<InternalStockOutOrderDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return SignedDomainRequest.required(response, "ERP外部出库投影");
    }

    @Override
    public InternalTransferOrderDetailView confirmExternalTransferStockOut(
            CallerIdentity caller, ExternalTransferStockOutProjectionCommand command) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("ERP外部调拨出库投影必须携带租户上下文");
        }
        if (command == null) throw new IllegalArgumentException("ERP外部调拨出库投影请求不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(ErpTransferOrderApi.BASE_PATH)
                .path("/external-stock-out-confirmations")
                .build()
                .encode()
                .toUri();
        ApiResponse<InternalTransferOrderDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return SignedDomainRequest.required(response, "ERP外部调拨出库投影");
    }

    @Override
    public InternalStockOutOrderDetailView confirmExternalGenericStockOut(
            CallerIdentity caller, ExternalGenericStockOutProjectionCommand command) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("ERP外部通用出库投影必须携带租户上下文");
        }
        if (command == null) throw new IllegalArgumentException("ERP外部通用出库投影请求不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(ErpStockOutOrderApi.BASE_PATH)
                .path("/external-generic-confirmations")
                .build()
                .encode()
                .toUri();
        ApiResponse<InternalStockOutOrderDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return SignedDomainRequest.required(response, "ERP外部通用出库投影");
    }
}
