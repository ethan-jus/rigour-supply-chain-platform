package com.rigour.integration.infrastructure.domain;

import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.integration.application.port.out.HrDhbStaffSyncClient;
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

/** Integration 到 HR 员工主档内部订货宝员工同步接口的 HTTP 客户端。 */
public final class HttpHrDhbStaffSyncClient implements HrDhbStaffSyncClient {
    private static final TypeReference<ApiResponse<ExternalEmployeeSyncResult>> STAFF_SYNC_RESPONSE =
            new TypeReference<>() { };
    private static final TypeReference<ApiResponse<List<ExternalEmployeeResolvedView>>> STAFF_RESOLVE_RESPONSE =
            new TypeReference<>() { };

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpHrDhbStaffSyncClient(RestClient.Builder builder,
                                    TrustedContextSigner signer,
                                    String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "HR");
    }

    @Override
    public StaffSyncResult sync(CallerIdentity caller, List<DhbStaffRow> rows) {
        if (rows == null) rows = List.of();
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/hr/employees/external-sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<ExternalEmployeeSyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new ExternalEmployeeSyncCommand("DINGHUOBAO", rows.stream()
                        .map(HttpHrDhbStaffSyncClient::employeeRow)
                        .toList()))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, STAFF_SYNC_RESPONSE, "HR员工同步"));
        ExternalEmployeeSyncResult result = SignedDomainRequest.required(response, "HR员工同步");
        return new StaffSyncResult(result.received(), result.created(), result.updated(),
                result.unchanged(), result.failed(), result.failureMessages());
    }

    @Override
    public List<ResolvedEmployee> resolve(CallerIdentity caller, String sourceTenantKey,
                                          List<String> sourceStaffIds, List<String> sourceStaffNames) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/hr/employees/source-resolve")
                .build()
                .encode()
                .toUri();
        ApiResponse<List<ExternalEmployeeResolvedView>> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new ExternalEmployeeResolveCommand("DINGHUOBAO", sourceTenantKey,
                        sourceStaffIds, sourceStaffNames))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, STAFF_RESOLVE_RESPONSE, "HR员工解析"));
        return SignedDomainRequest.required(response, "HR员工解析").stream()
                .map(item -> new ResolvedEmployee(item.sourceTenantKey(), item.sourceEmployeeId(),
                        item.employeeCode(), item.employeeName(), item.employmentStatus()))
                .toList();
    }

    private static ExternalEmployeeRowCommand employeeRow(DhbStaffRow row) {
        String employeeName = first(row.staffName(), row.accountsName(), row.sourceStaffId(), "未命名员工");
        return new ExternalEmployeeRowCommand(row.connectorId(), row.sourceTenantKey(), row.sourceStaffId(),
                row.accountsName(), employeeName, row.staffType(), row.title(), row.branchName(),
                null, null, null, first(row.mobile(), row.accountsMobile()), row.email(), row.status(),
                null, null, row.createDate(), row.updateDate(), row.sourcePayloadHash(), row.sourcePayloadJson());
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return null;
    }
}
