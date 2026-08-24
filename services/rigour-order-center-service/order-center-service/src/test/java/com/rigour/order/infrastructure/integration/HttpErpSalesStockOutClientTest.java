package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.erp.api.v1.ErpStockOutOrderApi;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutLine;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutRequest;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpErpSalesStockOutClientTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-0000-7000-8000-000000000002");

    @Test
    void postsSignedSalesStockOutCommandToErpContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://erp.test";
        server.expect(requestTo(base + ErpStockOutOrderApi.BASE_PATH + "/sales-confirmations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.salesOrderId").value(1))
                .andExpect(jsonPath("$.salesOrderNo").value("DD202608201234"))
                .andExpect(jsonPath("$.warehouseId").value(9))
                .andExpect(jsonPath("$.customerNameSnapshot").value("上海静安店"))
                .andExpect(jsonPath("$.stockOutTime").value("2026-08-20T05:00:00Z"))
                .andExpect(jsonPath("$.lines[0].salesOrderLineId").value(11))
                .andExpect(jsonPath("$.lines[0].variantCodeSnapshot").value("SKU-1"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "TENANT"))
                .andExpect(header(RequestHeaders.PRINCIPAL_ID, USER_ID.toString()))
                .andExpect(header(RequestHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(header(RequestHeaders.USER_ID, USER_ID.toString()))
                .andExpect(header(RequestHeaders.PERMISSIONS, "erp:supply:write,order:write"))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "v1"))
                .andRespond(withSuccess("""
                        {
                          "code": "OK",
                          "message": "success",
                          "data": {
                            "id": 99,
                            "stockOutNo": "CK202608201234",
                            "stockOutTime": "2026-08-20T05:00:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        HttpErpSalesStockOutClient client = new HttpErpSalesStockOutClient(builder, signer(), base);

        var result = client.confirmSalesStockOut(caller(), request());

        assertThat(result.stockOutOrderId()).isEqualTo(99L);
        assertThat(result.stockOutNo()).isEqualTo("CK202608201234");
        assertThat(result.stockOutTime()).isEqualTo(Instant.parse("2026-08-20T05:00:00Z"));
        server.verify();
    }

    private static SalesStockOutRequest request() {
        return new SalesStockOutRequest(1L, "DD202608201234", 9L, 2L,
                "上海静安店", Instant.parse("2026-08-20T05:00:00Z"),
                List.of(new SalesStockOutLine(11L, 10L, 12L, "P-1", "SKU-1",
                        "酸麻粉面菜蛋", "BOX", new BigDecimal("2"), "明细备注")),
                "手动出库");
    }

    private static CallerIdentity caller() {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.fromString("019fb700-0000-7000-8000-000000000003"),
                0, 0, 0, Set.of("order"), Set.of("order:write", "erp:supply:write"));
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
