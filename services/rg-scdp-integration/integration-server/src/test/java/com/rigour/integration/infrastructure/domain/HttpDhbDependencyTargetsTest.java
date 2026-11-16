package com.rigour.integration.infrastructure.domain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.shared.context.*;
import com.rigour.shared.core.api.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class HttpDhbDependencyTargetsTest {
    final UUID user = UUID.randomUUID();
    final CallerIdentity actor =
            new CallerIdentity(
                    "TENANT",
                    user,
                    UUID.randomUUID(),
                    user,
                    null,
                    user,
                    1,
                    1,
                    1,
                    Set.of(),
                    Set.of("integration:dhb:write", "crm:customer:read"));
    final JsonMapper json = JsonMapper.builder().build();

    HttpDhbDependencyTargets client(RestClient.Builder builder) {
        var signer = mock(TrustedContextSigner.class);
        when(signer.sign(anyString(), anyString(), any(), anyMap()))
                .thenReturn(new TrustedContextSigner.SignedContext("test", "1", "signature"));
        return new HttpDhbDependencyTargets(
                builder, signer, "http://order.test", "http://erp.test", "http://crm.test");
    }

    @Test
    void readsNativeCustomerWithCurrentUserScopeAndKeepsDomainPermissionChecks() {
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (request, body, execution) -> {
                                            assertThat(request.getURI().toString())
                                                    .isEqualTo(
                                                            "http://crm.test/api/v1/crm/internal-customers/3");
                                            assertThat(
                                                            request.getHeaders()
                                                                    .getFirst(
                                                                            RequestHeaders
                                                                                    .PRINCIPAL_SCOPE))
                                                    .isEqualTo("TENANT");
                                            assertThat(
                                                            request.getHeaders()
                                                                    .getFirst(
                                                                            RequestHeaders.USER_ID))
                                                    .isEqualTo(user.toString());
                                            return new MockClientHttpResponse(
                                                    json.writeValueAsBytes(
                                                            ApiResponse.success(
                                                                    Map.of(
                                                                            "id",
                                                                            3,
                                                                            "customerCode",
                                                                            "C001",
                                                                            "statusCode",
                                                                            "ACTIVE"))),
                                                    HttpStatus.OK);
                                        }));
        assertThat(client.require(actor, "CUSTOMER", 3L, null).code()).isEqualTo("C001");
    }

    @Test
    void nativePermissionFailureCannotBeConvertedIntoVisibleTarget() {
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (r, b, e) ->
                                                new MockClientHttpResponse(
                                                        json.writeValueAsBytes(
                                                                ApiResponse.error(
                                                                        ErrorCode.FORBIDDEN)),
                                                        HttpStatus.FORBIDDEN)));
        assertThatThrownBy(() -> client.require(actor, "CUSTOMER", 3L, null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void variantMustBelongToSelectedProductAndDraftOrderCannotBeLinked() {
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (request, body, execution) -> {
                                            Object data =
                                                    request.getURI().getPath().contains("products")
                                                            ? Map.of(
                                                                    "id",
                                                                    4,
                                                                    "productCode",
                                                                    "P001",
                                                                    "variants",
                                                                    List.of(
                                                                            Map.of(
                                                                                    "id",
                                                                                    5,
                                                                                    "variantCode",
                                                                                    "SKU001")))
                                                            : Map.of(
                                                                    "id",
                                                                    3,
                                                                    "orderNo",
                                                                    "SO001",
                                                                    "orderStatusCode",
                                                                    "DRAFT");
                                            return new MockClientHttpResponse(
                                                    json.writeValueAsBytes(
                                                            ApiResponse.success(data)),
                                                    HttpStatus.OK);
                                        }));
        assertThat(client.require(actor, "PRODUCT_SKU", 5L, 4L).code()).isEqualTo("SKU001");
        assertThatThrownBy(() -> client.require(actor, "PRODUCT_SKU", 6L, 4L))
                .hasMessageContaining("不属于");
        assertThatThrownBy(() -> client.require(actor, "SALES_ORDER", 3L, null))
                .hasMessageContaining("尚未提交或已取消");
    }

    @Test
    void salesRecoveryUsesPublishedOrderDetailRoute() {
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (request, body, execution) -> {
                                            assertThat(request.getURI().getPath())
                                                    .isEqualTo("/api/v1/orders/sales/3");
                                            return new MockClientHttpResponse(
                                                    json.writeValueAsBytes(
                                                            ApiResponse.success(
                                                                    Map.of(
                                                                            "id",
                                                                            3,
                                                                            "orderNo",
                                                                            "SO001",
                                                                            "orderStatusCode",
                                                                            "SUBMITTED"))),
                                                    HttpStatus.OK);
                                        }));
        assertThat(client.require(actor, "SALES_ORDER", 3L, null).objectType())
                .isEqualTo("SALES_ORDER");
    }

    @Test
    void disabledParentProductCannotBeLinkedThroughItsVariant() {
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (request, body, execution) ->
                                                new MockClientHttpResponse(
                                                        json.writeValueAsBytes(
                                                                ApiResponse.success(
                                                                        Map.of(
                                                                                "id",
                                                                                4,
                                                                                "productCode",
                                                                                "P001",
                                                                                "statusCode",
                                                                                "DISABLED",
                                                                                "variants",
                                                                                List.of(
                                                                                        Map.of(
                                                                                                "id",
                                                                                                5,
                                                                                                "variantCode",
                                                                                                "SKU001"))))),
                                                        HttpStatus.OK)));
        assertThatThrownBy(() -> client.require(actor, "PRODUCT_SKU", 5L, 4L))
                .hasMessageContaining("停用");
    }
}
