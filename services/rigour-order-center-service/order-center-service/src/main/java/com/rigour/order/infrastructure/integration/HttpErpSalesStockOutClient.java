package com.rigour.order.infrastructure.integration;

import com.rigour.erp.api.v1.ErpStockOutOrderApi;
import com.rigour.erp.api.v1.model.InternalSalesStockOutCommand;
import com.rigour.erp.api.v1.model.InternalSalesStockOutLineCommand;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.order.application.port.out.ErpSalesStockOutClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Order 到 ERP 销售出库 HTTP 客户端；只调用 ERP 公开版本化契约，不访问 ERP 数据库。 */
public final class HttpErpSalesStockOutClient implements ErpSalesStockOutClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpErpSalesStockOutClient(RestClient.Builder builder,
                                      TrustedContextSigner signer,
                                      String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(baseUrl);
    }

    @Override
    public SalesStockOutResult confirmSalesStockOut(CallerIdentity caller, SalesStockOutRequest request) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("ERP销售出库调用必须携带租户上下文");
        }
        if (request == null) throw new IllegalArgumentException("ERP销售出库请求不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(ErpStockOutOrderApi.BASE_PATH)
                .path("/sales-confirmations")
                .build().encode().toUri();
        ApiResponse<InternalStockOutOrderDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command(request))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("ERP销售出库返回空响应");
        }
        InternalStockOutOrderDetailView data = response.data();
        return new SalesStockOutResult(data.id(), data.stockOutNo(), data.stockOutTime());
    }

    private static InternalSalesStockOutCommand command(SalesStockOutRequest request) {
        return new InternalSalesStockOutCommand(
                request.salesOrderId(),
                request.salesOrderNo(),
                request.warehouseId(),
                request.customerId(),
                request.customerNameSnapshot(),
                request.stockOutTime(),
                request.lines().stream()
                        .map(line -> new InternalSalesStockOutLineCommand(
                                line.salesOrderLineId(),
                                line.productId(),
                                line.productVariantId(),
                                line.productCodeSnapshot(),
                                line.variantCodeSnapshot(),
                                line.productNameSnapshot(),
                                line.unitCode(),
                                line.quantity(),
                                line.remark()))
                        .toList(),
                request.remark());
    }

    private Map<String, String> signedHeaders(String method, URI uri, CallerIdentity caller) {
        Map<String, String> headers = new LinkedHashMap<>();
        put(headers, RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        put(headers, RequestHeaders.PRINCIPAL_ID, caller.principalId());
        put(headers, RequestHeaders.TENANT_ID, caller.tenantId());
        put(headers, RequestHeaders.USER_ID, caller.userId());
        put(headers, RequestHeaders.PLATFORM_USER_ID, caller.platformUserId());
        put(headers, RequestHeaders.SESSION_ID, caller.sessionId());
        put(headers, RequestHeaders.SESSION_VERSION, caller.sessionVersion());
        put(headers, RequestHeaders.USER_SECURITY_VERSION, caller.userSecurityVersion());
        put(headers, RequestHeaders.TENANT_POLICY_VERSION, caller.tenantPolicyVersion());
        put(headers, RequestHeaders.ROLES, joined(caller.roles()));
        put(headers, RequestHeaders.PERMISSIONS, joined(caller.permissions()));
        TrustedContextSigner.SignedContext signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("ERP服务地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("ERP服务地址必须使用http或https");
        }
        return uri;
    }

    private static void put(Map<String, String> target, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(name, String.valueOf(value));
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }

    private static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
