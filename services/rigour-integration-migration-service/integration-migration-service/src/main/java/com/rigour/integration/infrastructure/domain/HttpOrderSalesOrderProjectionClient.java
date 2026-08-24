package com.rigour.integration.infrastructure.domain;

import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.order.api.v1.OrderFundDocumentApi;
import com.rigour.order.api.v1.OrderSalesOrderApi;
import com.rigour.order.api.v1.OrderSalesPaymentRecordApi;
import com.rigour.order.api.v1.OrderSalesRefundRecordApi;
import com.rigour.order.api.v1.OrderSalesShipmentApi;
import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordCommand;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesShipmentCommand;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
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

/** Integration到Order销售订单的HTTP客户端；只投影到自研业务接口。 */
public final class HttpOrderSalesOrderProjectionClient implements OrderSalesOrderProjectionClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpOrderSalesOrderProjectionClient(RestClient.Builder builder,
                                               TrustedContextSigner signer,
                                               String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(baseUrl);
    }

    @Override
    public SalesOrderDetailView salesOrder(CallerIdentity caller, Long id) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesOrderId无效");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesOrderApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesOrderDetailView> response = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("GET", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredResponse(response);
    }

    @Override
    public SalesOrderDetailView createSalesOrder(CallerIdentity caller, SalesOrderCommand command) {
        requireCaller(caller);
        if (command == null) throw new IllegalArgumentException("salesOrder command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesOrderApi.BASE_PATH)
                .build()
                .encode()
                .toUri();
        ApiResponse<SalesOrderDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredResponse(response);
    }

    @Override
    public SalesOrderDetailView updateSalesOrder(CallerIdentity caller, Long id,
                                                 SalesOrderCommand command) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesOrderId无效");
        if (command == null) throw new IllegalArgumentException("salesOrder command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesOrderApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesOrderDetailView> response = restClient.put().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("PUT", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredResponse(response);
    }

    @Override
    public SalesOrderDetailView cancelSalesOrder(CallerIdentity caller, Long id, int revision) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesOrderId无效");
        if (revision < 1) throw new IllegalArgumentException("revision必须大于0");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesOrderApi.BASE_PATH)
                .path("/{id}/cancellations")
                .queryParam("revision", revision)
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesOrderDetailView> response = restClient.post().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredResponse(response);
    }

    @Override
    public SalesPaymentRecordDetailView salesPayment(CallerIdentity caller, Long id) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesPaymentId无效");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesPaymentRecordApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesPaymentRecordDetailView> response = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("GET", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredPaymentResponse(response);
    }

    @Override
    public SalesPaymentRecordDetailView createSalesPayment(
            CallerIdentity caller, SalesPaymentRecordCommand command) {
        requireCaller(caller);
        if (command == null) throw new IllegalArgumentException("salesPayment command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesPaymentRecordApi.BASE_PATH)
                .build()
                .encode()
                .toUri();
        ApiResponse<SalesPaymentRecordDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredPaymentResponse(response);
    }

    @Override
    public SalesPaymentRecordDetailView updateSalesPayment(
            CallerIdentity caller, Long id, SalesPaymentRecordCommand command) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesPaymentId无效");
        if (command == null) throw new IllegalArgumentException("salesPayment command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesPaymentRecordApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesPaymentRecordDetailView> response = restClient.put().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("PUT", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredPaymentResponse(response);
    }

    @Override
    public FundDocumentDetailView fundDocument(CallerIdentity caller, Long id) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("fundDocumentId无效");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderFundDocumentApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<FundDocumentDetailView> response = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("GET", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredFundDocumentResponse(response);
    }

    @Override
    public FundDocumentDetailView createFundDocument(CallerIdentity caller, FundDocumentCommand command) {
        requireCaller(caller);
        if (command == null) throw new IllegalArgumentException("fundDocument command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderFundDocumentApi.BASE_PATH)
                .build()
                .encode()
                .toUri();
        ApiResponse<FundDocumentDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredFundDocumentResponse(response);
    }

    @Override
    public FundDocumentDetailView updateFundDocument(
            CallerIdentity caller, Long id, FundDocumentCommand command) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("fundDocumentId无效");
        if (command == null) throw new IllegalArgumentException("fundDocument command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderFundDocumentApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<FundDocumentDetailView> response = restClient.put().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("PUT", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredFundDocumentResponse(response);
    }

    @Override
    public SalesRefundRecordDetailView salesRefund(CallerIdentity caller, Long id) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesRefundId无效");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesRefundRecordApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesRefundRecordDetailView> response = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("GET", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredRefundResponse(response);
    }

    @Override
    public SalesRefundRecordDetailView createSalesRefund(
            CallerIdentity caller, SalesRefundRecordCommand command) {
        requireCaller(caller);
        if (command == null) throw new IllegalArgumentException("salesRefund command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesRefundRecordApi.BASE_PATH)
                .build()
                .encode()
                .toUri();
        ApiResponse<SalesRefundRecordDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredRefundResponse(response);
    }

    @Override
    public SalesRefundRecordDetailView updateSalesRefund(
            CallerIdentity caller, Long id, SalesRefundRecordCommand command) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesRefundId无效");
        if (command == null) throw new IllegalArgumentException("salesRefund command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesRefundRecordApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesRefundRecordDetailView> response = restClient.put().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("PUT", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredRefundResponse(response);
    }

    @Override
    public SalesShipmentDetailView salesShipment(CallerIdentity caller, Long id) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesShipmentId无效");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesShipmentApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesShipmentDetailView> response = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("GET", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredShipmentResponse(response);
    }

    @Override
    public SalesShipmentDetailView createSalesShipment(CallerIdentity caller, SalesShipmentCommand command) {
        requireCaller(caller);
        if (command == null) throw new IllegalArgumentException("salesShipment command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesShipmentApi.BASE_PATH)
                .build()
                .encode()
                .toUri();
        ApiResponse<SalesShipmentDetailView> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredShipmentResponse(response);
    }

    @Override
    public SalesShipmentDetailView updateSalesShipment(
            CallerIdentity caller, Long id, SalesShipmentCommand command) {
        requireCaller(caller);
        if (id == null || id < 1) throw new IllegalArgumentException("salesShipmentId无效");
        if (command == null) throw new IllegalArgumentException("salesShipment command不能为空");
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(OrderSalesShipmentApi.BASE_PATH)
                .path("/{id}")
                .buildAndExpand(id)
                .encode()
                .toUri();
        ApiResponse<SalesShipmentDetailView> response = restClient.put().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("PUT", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return requiredShipmentResponse(response);
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
        TrustedContextSigner.SignedContext signed = signer.sign(
                method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private static SalesOrderDetailView requiredResponse(ApiResponse<SalesOrderDetailView> response) {
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("Order销售订单返回空响应");
        }
        return response.data();
    }

    private static SalesPaymentRecordDetailView requiredPaymentResponse(
            ApiResponse<SalesPaymentRecordDetailView> response) {
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("Order销售回款记录返回空响应");
        }
        return response.data();
    }

    private static SalesRefundRecordDetailView requiredRefundResponse(
            ApiResponse<SalesRefundRecordDetailView> response) {
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("Order销售退款记录返回空响应");
        }
        return response.data();
    }

    private static FundDocumentDetailView requiredFundDocumentResponse(
            ApiResponse<FundDocumentDetailView> response) {
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("Order资金单据返回空响应");
        }
        return response.data();
    }

    private static SalesShipmentDetailView requiredShipmentResponse(
            ApiResponse<SalesShipmentDetailView> response) {
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("Order销售发货单返回空响应");
        }
        return response.data();
    }

    private static void requireCaller(CallerIdentity caller) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("Order销售订单投影必须携带租户上下文");
        }
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Order服务地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Order服务地址必须使用http或https");
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
