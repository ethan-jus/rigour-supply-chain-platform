package com.rigour.integration.infrastructure.domain;

import com.rigour.hr.api.v1.HrEmployeeProjectionApi;
import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.integration.application.port.out.HrEmployeeProjectionClient;
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

/** Integration 到 HR 员工同步接口的 HTTP 客户端。 */
public final class HttpHrEmployeeProjectionClient implements HrEmployeeProjectionClient {
    private static final TypeReference<ApiResponse<ExternalEmployeeSyncResult>> EMPLOYEE_SYNC_RESPONSE =
            new TypeReference<>() { };
    private static final TypeReference<ApiResponse<List<ExternalEmployeeResolvedView>>> EMPLOYEE_RESOLVE_RESPONSE =
            new TypeReference<>() { };

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpHrEmployeeProjectionClient(RestClient.Builder builder,
                                          TrustedContextSigner signer,
                                          String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "HR");
    }

    @Override
    public ExternalEmployeeSyncResult sync(CallerIdentity caller, String sourceSystem,
                                           List<ExternalEmployeeRowCommand> rows) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(HrEmployeeProjectionApi.BASE_PATH + "/external-sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<ExternalEmployeeSyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new ExternalEmployeeSyncCommand(sourceSystem, rows))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, EMPLOYEE_SYNC_RESPONSE, "HR员工同步"));
        return SignedDomainRequest.required(response, "HR员工同步");
    }

    @Override
    public List<ExternalEmployeeResolvedView> resolve(CallerIdentity caller,
                                                      ExternalEmployeeResolveCommand command) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(HrEmployeeProjectionApi.BASE_PATH + "/source-resolve")
                .build()
                .encode()
                .toUri();
        ApiResponse<List<ExternalEmployeeResolvedView>> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(command)
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, EMPLOYEE_RESOLVE_RESPONSE, "HR员工解析"));
        List<ExternalEmployeeResolvedView> resolved = SignedDomainRequest.required(response, "HR员工解析");
        return resolved == null ? List.of() : resolved;
    }
}
