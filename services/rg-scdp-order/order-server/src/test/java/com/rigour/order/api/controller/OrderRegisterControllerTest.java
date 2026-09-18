package com.rigour.order.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodRow;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodTotals;
import com.rigour.order.application.service.sales.OrderRegisterService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 订单登记 HTTP 边界：CSV 导出必须带 BOM、中文表头与转义，创建人端点返回字符串数组。 */
class OrderRegisterControllerTest {
    private final OrderRegisterService service = mock(OrderRegisterService.class);
    private final OrderRegisterController controller = new OrderRegisterController(service);

    @Test
    void exportPeriodWritesBomHeaderAndEscapesLabels() {
        when(service.periodStatistics(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new PeriodStatisticsView(
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-17"),
                                "region",
                                new PeriodTotals(
                                        new BigDecimal("100.50"),
                                        new BigDecimal("80.00"),
                                        new BigDecimal("5.00"),
                                        new BigDecimal("75.00"),
                                        new BigDecimal("20.50")),
                                List.of(
                                        new PeriodRow(
                                                "HZ",
                                                "杭州,地区",
                                                new BigDecimal("60.00"),
                                                new BigDecimal("50.00"),
                                                new BigDecimal("0.00"),
                                                new BigDecimal("50.00"),
                                                new BigDecimal("10.00")),
                                        new PeriodRow(
                                                "BJ",
                                                "北京\"地区\"\n二部",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)),
                                HistoryCoverage.covered()));

        ResponseEntity<byte[]> response =
                controller.exportPeriod(
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-17"),
                        "region",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body[0] & 0xFF).isEqualTo(0xEF);
        assertThat(body[1] & 0xFF).isEqualTo(0xBB);
        assertThat(body[2] & 0xFF).isEqualTo(0xBF);

        String text = new String(body, StandardCharsets.UTF_8);
        assertThat(text)
                .startsWith(
                        "\uFEFF分组,期间订单额,期间实收,期间退款冲销,期间净回款,期末未回款\n");
        assertThat(text).contains("\"杭州,地区\",60.00,50.00,0.00,50.00,10.00\n");
        assertThat(text).contains("\"北京\"\"地区\"\"\n二部\",0,0,0,0,0\n");

        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("period.csv");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
    }

    @Test
    void exportPeriodForwardsEveryFilterToStatistics() {
        when(service.periodStatistics(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new PeriodStatisticsView(
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-17"),
                                "customer",
                                null,
                                List.of(),
                                HistoryCoverage.covered()));

        controller.exportPeriod(
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-17"),
                "customer",
                "HZ",
                "EMP-1",
                7L,
                9L,
                "乔氏台球",
                "D-9");

        ArgumentCaptor<String> group = ArgumentCaptor.forClass(String.class);
        verify(service)
                .periodStatistics(
                        eq(LocalDate.parse("2026-09-01")),
                        eq(LocalDate.parse("2026-09-17")),
                        group.capture(),
                        eq("HZ"),
                        eq("EMP-1"),
                        eq(7L),
                        eq(9L),
                        eq("乔氏台球"),
                        eq("D-9"));
        assertThat(group.getValue()).isEqualTo("customer");
    }

    @Test
    void creatorsReturnsStringListPayload() {
        when(service.creators()).thenReturn(List.of("张三", "李四"));

        var response = controller.creators();

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.data()).containsExactly("张三", "李四");
    }
}
