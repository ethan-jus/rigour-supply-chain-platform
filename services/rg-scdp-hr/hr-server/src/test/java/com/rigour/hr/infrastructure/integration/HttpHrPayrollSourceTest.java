package com.rigour.hr.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

class HttpHrPayrollSourceTest {
    private static final String ORDER = "https://order.test";
    private static final String ROUTE = "/api/v1/orders/cost-sources/payroll-settlements";
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = FROM.withDayOfMonth(30);

    @Test
    void signsTheExactTenantAndPeriodAndReadsWholeOrderEvidence() {
        var tenant = UUID.randomUUID().toString();
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var signer = new TrustedContextSigner(trust);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var source = new HttpHrPayrollSource(builder, signer, "https://sales.test", ORDER,
                "https://erp.test", "https://settings.test");
        var url = ORDER + ROUTE + "?employeeCode=EMP-B&from=" + FROM + "&to=" + TO;
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestHeaders.TENANT_ID, tenant))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE"))
                .andExpect(header(RequestHeaders.PERMISSIONS, "order:payroll:source-read"))
                .andExpect(request -> {
                    var incoming = new MockHttpServletRequest("GET", ROUTE);
                    incoming.setQueryString(request.getURI().getRawQuery());
                    request.getHeaders().forEach((name, values) ->
                            values.forEach(value -> incoming.addHeader(name, value)));
                    assertThat(signer.verify(incoming)).isTrue();
                })
                .andRespond(withSuccess("""
                        {"code":"OK","data":[{"orderId":"123","orderNo":"SO-1",
                          "originalEmployeeCode":"EMP-A","paymentId":"456","paymentNo":"RC-2",
                          "paymentOwnerCode":"EMP-B","paymentOwnerName":"回款人乙",
                          "paymentOwnerEvidence":"人工确认","fullyPaidDate":"2026-11-04",
                          "actualCashDate":"2026-11-03","orderAmount":10000,"refundedAmount":0,
                          "qualityStatus":"COMPLETE","lines":[]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(source.payrollSettlements(tenant, "EMP-B", FROM, TO)).singleElement()
                .satisfies(settlement -> {
                    assertThat(settlement.paymentOwnerCode()).isEqualTo("EMP-B");
                    assertThat(settlement.paymentNo()).isEqualTo("RC-2");
                    assertThat(settlement.orderAmount()).isEqualByComparingTo("10000");
                    assertThat(settlement.fullyPaidDate()).isEqualTo(FROM.withDayOfMonth(4));
                });
        server.verify();
    }

    @Test
    void missingDataIsUnavailableInsteadOfZeroAchievement() {
        var tenant = UUID.randomUUID().toString();
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var source = new HttpHrPayrollSource(builder, new TrustedContextSigner(trust),
                "https://sales.test", ORDER, "https://erp.test", "https://settings.test");
        server.expect(requestTo(ORDER + ROUTE + "?employeeCode=EMP-B&from=" + FROM + "&to=" + TO))
                .andRespond(withSuccess("{\"code\":\"OK\",\"data\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.payrollSettlements(tenant, "EMP-B", FROM, TO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("暂不可用");
        server.verify();
    }
}
