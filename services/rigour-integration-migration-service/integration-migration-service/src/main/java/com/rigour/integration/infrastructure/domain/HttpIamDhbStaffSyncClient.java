package com.rigour.integration.infrastructure.domain;

import com.rigour.integration.application.port.out.IamDhbStaffSyncClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Integration 到 IAM 人员中心内部订货宝员工同步接口的 HTTP 客户端。 */
public final class HttpIamDhbStaffSyncClient implements IamDhbStaffSyncClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpIamDhbStaffSyncClient(RestClient.Builder builder,
                                     TrustedContextSigner signer,
                                     String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "IAM");
    }

    @Override
    public StaffSyncResult sync(CallerIdentity caller, List<DhbStaffRow> rows) {
        if (rows == null) rows = List.of();
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/iam/dhb/staff/sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<StaffSyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new StaffSyncRequest(rows))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return SignedDomainRequest.required(response, "IAM人员同步");
    }

    @Override
    public List<ResolvedStaff> resolve(CallerIdentity caller, String sourceTenantKey,
                                       List<String> sourceStaffIds, List<String> sourceStaffNames) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/iam/dhb/staff/resolve")
                .build()
                .encode()
                .toUri();
        ApiResponse<List<ResolvedStaff>> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new StaffResolveRequest(sourceTenantKey, sourceStaffIds, sourceStaffNames))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return SignedDomainRequest.required(response, "IAM人员解析");
    }

    private record StaffSyncRequest(List<DhbStaffRow> rows) {
        StaffSyncRequest {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    private record StaffResolveRequest(String sourceTenantKey, List<String> sourceStaffIds,
                                       List<String> sourceStaffNames) {
        StaffResolveRequest {
            sourceStaffIds = sourceStaffIds == null ? List.of() : List.copyOf(sourceStaffIds);
            sourceStaffNames = sourceStaffNames == null ? List.of() : List.copyOf(sourceStaffNames);
        }
    }
}
