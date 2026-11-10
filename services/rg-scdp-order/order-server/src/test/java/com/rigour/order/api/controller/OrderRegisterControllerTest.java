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
    private final com.rigour.order.application.port.out.OrderStatusDictionary dictionaries = mock(com.rigour.order.application.port.out.OrderStatusDictionary.class);
    @org.junit.jupiter.api.BeforeEach void caller() {
        var user = java.util.UUID.randomUUID();
        com.rigour.shared.context.TestAuthorizationContext.set(new com.rigour.shared.context.CallerIdentity(
                "TENANT", user, java.util.UUID.randomUUID(), user, null, java.util.UUID.randomUUID(), 0, 0, 0, java.util.Set.of(), java.util.Set.of("order:read")));
        when(dictionaries.labels(any(), eq("SALES_ORDER_STATUS"))).thenReturn(java.util.Map.of("SUBMITTED", "租户履约中"));
        when(dictionaries.labels(any(), eq("ORDER_COLLECTION_STATUS"))).thenReturn(java.util.Map.of("PARTIAL_PAID", "部分收款", "NOT_REQUIRED", "无需收款"));
        when(dictionaries.labels(any(), eq("PAYMENT_RECEIPT_STATUS"))).thenReturn(java.util.Map.of("RECEIVED", "租户已到账"));
        when(dictionaries.labels(any(), eq("PAYMENT_CONFIRMATION_STATUS"))).thenReturn(java.util.Map.of("CONFIRMED", "财务已确认"));
    }
    @org.junit.jupiter.api.AfterEach void clearCaller() { com.rigour.shared.context.TestAuthorizationContext.clear(); }
    private final OrderRegisterController controller = new OrderRegisterController(service, dictionaries);

    @Test
    void orderExportUsesTenantLifecycleDictionary() {
        var row = mock(com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView.class);
        when(row.orderStatusCode()).thenReturn("SUBMITTED");
        when(row.paymentOwnerNames()).thenReturn(List.of("李四", "王五"));
        when(service.orders(eq(0),eq(200),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(), any()))
                .thenReturn(new com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage<>(1,0,200,List.of(row),java.util.Map.of(),null));
        var response = controller.exportOrders(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null, null);
        assertThat(new String(response.getBody(),StandardCharsets.UTF_8)).contains("租户履约中").contains("回款人").contains("李四、王五").doesNotContain("SUBMITTED");
    }

    @Test
    void paymentExportPreservesProductFilterAndActualPaymentAmount() {
        var row = mock(com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView.class);
        when(row.paymentNo()).thenReturn("PAY-1");
        when(row.receiptStatusCode()).thenReturn("RECEIVED");
        when(row.confirmationStatusCode()).thenReturn("CONFIRMED");
        when(row.paidAmount()).thenReturn(new BigDecimal("50.00"));
        when(row.allocatedPaymentAmount()).thenReturn(new BigDecimal("10.00"));
        when(service.payments(eq(0), eq(200), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), eq("paymentTime"), eq("asc"), any(), eq(List.of(101L)), any(), any()))
                .thenReturn(new com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage<>(1,0,200,List.of(row),java.util.Map.of(),null));
        var response = controller.exportPayments(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,
                null,null,null,List.of(101L),"paymentTime","asc",null, null);
        String csv = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(csv).contains("财务确认人,财务确认时间,筛选商品分摊金额");
        assertThat(csv).contains("50.00").contains("租户已到账,财务已确认").doesNotContain("审核");
        assertThat(csv).contains("10.00");
    }

    @Test
    void lineExportIncludesLatestReceiptDateAndForwardsTheSameFilters() {
        var row = mock(com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterLineView.class);
        when(row.paymentStatusCode()).thenReturn("PARTIAL_PAID");
        when(row.paymentOwnerNames()).thenReturn(List.of("李四", "王五"));
        when(row.paymentTime()).thenReturn(java.time.Instant.parse("2026-09-01T16:00:00Z"));
        var from = java.time.Instant.parse("2026-09-01T16:00:00Z");
        var to = java.time.Instant.parse("2026-09-02T16:00:00Z");
        when(service.lines(eq(0),eq(200),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),
                eq(List.of(101L,102L)),eq("PARTIAL_PAID"),any(),any(),any(),any(),eq(List.of(1001L,1003L)),eq(from),eq(to), eq("LI")))
                .thenReturn(new com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage<>(1,0,200,List.of(row),java.util.Map.of(),null));
        var response = controller.exportLines(null,null,null,null,null,null,null,null,null,null,null,null,null,
                List.of(101L,102L),"PARTIAL_PAID",null,null,null,null,List.of(1001L,1003L),from,to, "LI");
        String csv = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(csv).contains("收款状态,收款日期,下单时间").contains("回款人").contains("李四、王五");
        assertThat(csv).contains("部分收款,2026-09-02,");
    }

    @Test
    void exportPeriodWritesBomHeaderAndEscapesLabels() {
        when(service.periodStatistics(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
                false,
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
                        eq(false),
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
