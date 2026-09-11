package com.rigour.merchant.infrastructure.integration;

import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.merchant.application.port.out.HrEmployeeDirectoryClient;
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

/** CRM 到 HR 员工主档内部解析接口的 HTTP 客户端。 */
public final class HttpHrEmployeeDirectoryClient implements HrEmployeeDirectoryClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpHrEmployeeDirectoryClient(RestClient.Builder builder,
                                         TrustedContextSigner signer,
                                         String hrBaseUrl) {
        this.client = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(hrBaseUrl);
    }

    @Override
    public List<ResolvedEmployee> resolveDinghuobaoEmployees(CallerIdentity caller,
                                                             String sourceTenantKey,
                                                             List<String> sourceStaffIds) {
        if (sourceStaffIds == null || sourceStaffIds.isEmpty()) return List.of();
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/hr/employees/source-resolve")
                .build()
                .encode()
                .toUri();
        ApiResponse<List<ExternalEmployeeResolvedView>> response = client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedIntegrationRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId())
                .body(new ExternalEmployeeResolveCommand("DINGHUOBAO", sourceTenantKey,
                        sourceStaffIds, List.of()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("HR员工解析返回空响应");
        }
        return response.data().stream()
                .map(item -> new ResolvedEmployee(item.sourceTenantKey(), item.sourceEmployeeId(),
                        item.employeeCode(), item.employeeName(), item.employmentStatus()))
                .toList();
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("HR地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("HR地址必须使用http或https");
        }
        return uri;
    }
}
