package com.rigour.merchant.infrastructure.integration;

import com.rigour.merchant.application.port.out.IamStaffDirectoryClient;
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

/** CRM 到 IAM 人员中心内部解析接口的 HTTP 客户端。 */
public final class HttpIamStaffDirectoryClient implements IamStaffDirectoryClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpIamStaffDirectoryClient(RestClient.Builder builder,
                                       TrustedContextSigner signer,
                                       String iamBaseUrl) {
        this.client = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(iamBaseUrl);
    }

    @Override
    public List<ResolvedStaff> resolveDinghuobaoStaff(CallerIdentity caller,
                                                      String sourceTenantKey,
                                                      List<String> sourceStaffIds) {
        if (sourceStaffIds == null || sourceStaffIds.isEmpty()) return List.of();
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/iam/dhb/staff/resolve")
                .build()
                .encode()
                .toUri();
        ApiResponse<List<ResolvedStaff>> response = client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedIntegrationRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId())
                .body(new ResolveRequest(sourceTenantKey, sourceStaffIds))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("IAM人员解析返回空响应");
        }
        return response.data();
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("IAM地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("IAM地址必须使用http或https");
        }
        return uri;
    }

    private record ResolveRequest(String sourceTenantKey, List<String> sourceStaffIds) {
        ResolveRequest {
            sourceStaffIds = sourceStaffIds == null ? List.of() : List.copyOf(sourceStaffIds);
        }
    }
}
