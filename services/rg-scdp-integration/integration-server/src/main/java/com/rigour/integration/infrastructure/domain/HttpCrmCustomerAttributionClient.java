package com.rigour.integration.infrastructure.domain;

import com.rigour.integration.application.port.out.CrmCustomerAttributionClient;
import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;

/** Integration 到 CRM 客户归属内部接口的 HTTP 客户端。 */
public final class HttpCrmCustomerAttributionClient implements CrmCustomerAttributionClient {
    private static final UUID SERVICE_PRINCIPAL_ID =
            UUID.nameUUIDFromBytes(
                    "rigour-integration-customer-attribution-reader"
                            .getBytes(StandardCharsets.UTF_8));
    private static final Set<String> PERMISSIONS = Set.of("crm:customer:attribution-read");
    private static final TypeReference<ApiResponse<CustomerOrderAttributionView>> RESPONSE =
            new TypeReference<>() { };

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpCrmCustomerAttributionClient(RestClient.Builder builder,
                                            TrustedContextSigner signer,
                                            String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "CRM");
    }

    @Override
    public String regionCode(CallerIdentity caller, long customerId) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("CRM客户归属查询必须携带租户上下文");
        }
        if (customerId < 1) throw new IllegalArgumentException("客户ID无效");
        CallerIdentity service =
                new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, caller.tenantId(),
                        null, null, UUID.randomUUID(), 0, 0, 0, Set.of(), PERMISSIONS);
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/crm/customers/" + customerId + "/order-attribution")
                .build()
                .encode()
                .toUri();
        ApiResponse<CustomerOrderAttributionView> response = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest
                        .signedHeaders(signer, "GET", uri, service).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, RESPONSE, "CRM客户归属"));
        CustomerOrderAttributionView data = SignedDomainRequest.required(response, "CRM客户归属");
        if (data.customerId() != customerId
                || !caller.tenantId().toString().equals(data.tenantId())) {
            throw new IllegalStateException("CRM客户归属返回了不匹配的租户或客户");
        }
        String regionCode = data.regionCode();
        return regionCode == null || regionCode.isBlank() ? null : regionCode.strip();
    }
}
