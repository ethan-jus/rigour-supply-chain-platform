package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsCityProductReportApi;
import com.rigour.analytics.api.v1.model.CityProductReportView;
import com.rigour.analytics.api.v1.model.SupplyDashboardTargetCompletionItemView;
import com.rigour.analytics.application.service.CityProductReportService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证实际 Spring MVC 响应的十进制字符串及 Jackson 3 回读，不启动服务。 */
class CityProductReportSerializationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "123456789012345.001", "123456789012345.001000", "999999999999999999.123456",
            "-123456789012345.001000", "0.000001", "0.000000"
    })
    void mvcResponsePreservesEveryDecimalAndRoundTripsWithoutRounding(String source) throws Exception {
        var report = report(source == null ? null : new BigDecimal(source));
        var service = mock(CityProductReportService.class);
        when(service.report(null, null, null, null, null, null, null, "EXACT_ONLY", null, null, null)).thenReturn(report);
        var scopes = mock(com.rigour.analytics.application.service.BiDataScopeService.class);
        when(scopes.resolve(null, null)).thenReturn(new com.rigour.analytics.application.service.BiDataScopeService.ScopedSelection(
                "test-tenant", null, null, true));
        var mvc = MockMvcBuilders.standaloneSetup(new AnalyticsCityProductReportController(service, scopes)).build();

        String body = mvc.perform(get(AnalyticsCityProductReportApi.PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.orderCount").isNumber())
                .andExpect(jsonPath("$.data.orderTrace[0].lineCount").isNumber())
                .andReturn().getResponse().getContentAsString();

        var data = JSON.readTree(body).get("data");
        assertWireValues(report, JSON.readValue(data.toString(), Map.class));
        // Record/BigDecimal equality also verifies scale, including trailing zeroes.
        assertThat(JSON.readValue(data.toString(), CityProductReportView.class)).isEqualTo(report);
    }

    @Test
    void existingOverviewDecimalsRemainJsonNumbers() {
        var decimal = new BigDecimal("123.123456");
        var overview = new SupplyDashboardTargetCompletionItemView(
                "CITY", "BJ", "Beijing", "SALES", "Sales", decimal, decimal, decimal);
        var wire = JSON.readValue(JSON.writeValueAsString(overview), Map.class);

        assertThat(wire.get("targetValue")).isInstanceOf(Number.class);
        assertThat(wire.get("actualValue")).isInstanceOf(Number.class);
        assertThat(wire.get("achievementRate")).isInstanceOf(Number.class);
    }

    private static CityProductReportView report(BigDecimal decimal) {
        var now = Instant.parse("2026-09-12T00:00:00Z");
        String id = "9007199254740993";
        var quantities = List.of(new CityProductReportView.UnitQuantity("BOX", decimal));
        var row = new CityProductReportView.Row("BJ", "Beijing", id, "C1", "Category",
                id, "P1", "Product", id, "SKU1", "BOX", decimal, quantities,
                decimal, decimal, decimal, decimal, decimal, decimal, "EXACT", 0, 1, 1, "12 units", id, "Brand");
        var trace = new CityProductReportView.OrderTrace(id, "O1", "SOURCE1", "DINGHUOBAO",
                "BJ", "Beijing", "S1", id, now, 1, 1,
                decimal, decimal, decimal, decimal, decimal, decimal,
                decimal, decimal, decimal, decimal, decimal, "EXACT", "EXACT", "Customer", "Sales");
        var summary = new CityProductReportView.Summary(1, 1, 0, decimal, 0, quantities,
                decimal, decimal, decimal, decimal, decimal, decimal,
                decimal, decimal, decimal, decimal, decimal);
        return new CityProductReportView(now, now, now, now, "EXACT_ONLY",
                List.of(row), List.of(row), List.of(trace), summary, false, false, List.of(),
                List.of(new CityProductReportView.MonthlyRow("2026-09", row)),
                List.of(new CityProductReportView.CustomerArchive("BJ", "Beijing", 1)));
    }

    /** 遍历完整嵌套合同，防止新增金额/数量字段漏标；ID 字符串和 null 同样核对。 */
    private static void assertWireValues(Object expected, Object actual) throws ReflectiveOperationException {
        if (expected == null) {
            assertThat(actual).isNull();
        } else if (expected instanceof BigDecimal decimal) {
            assertThat(actual).isInstanceOf(String.class).isEqualTo(decimal.toPlainString());
        } else if (expected instanceof String) {
            assertThat(actual).isEqualTo(expected);
        } else if (expected.getClass().isRecord()) {
            assertThat(actual).isInstanceOf(Map.class);
            var values = (Map<?, ?>) actual;
            for (var component : expected.getClass().getRecordComponents()) {
                assertThat(values.containsKey(component.getName())).as(component.getName()).isTrue();
                assertWireValues(component.getAccessor().invoke(expected), values.get(component.getName()));
            }
        } else if (expected instanceof List<?> list) {
            assertThat(actual).isInstanceOf(List.class);
            var values = (List<?>) actual;
            assertThat(values).hasSize(list.size());
            for (int i = 0; i < list.size(); i++) {
                assertWireValues(list.get(i), values.get(i));
            }
        }
    }
}
