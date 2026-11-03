package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.rigour.erp.api.v1.model.*;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.order.api.v1.model.*;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class HrPayrollPerformanceExportServiceTest {
    static BigDecimal n(String value) { return new BigDecimal(value); }
    PayrollDetailView row() {
        var calculation = new PayrollCalculationView(n("3000"), n("1000"), null, n("0"), n("750"), n("0"), null, null,
                List.of("历史政策待核定"), List.of(), List.of(), null, "saved", null, null);
        return new PayrollDetailView("P1", "2026-10", "E1", "销售甲", 1L, "北京", "北京", "REVIEW", 2, calculation, Instant.EPOCH);
    }
    OrderPayrollLineView line(String id, String order, String date, long variant, String amount, String orderAmount) {
        return new OrderPayrollLineView(id, order, order, "E1", "C1", 1L, variant, variant, "商品", "SET", LocalDate.parse(date),
                n("1"), n(amount), n(orderAmount), n(orderAmount), n("0"), order.equals("UNPAID") ? null : LocalDate.of(2026, 10, 2), n(order.equals("OLD") ? "10" : "0"), null, "COMPLETE");
    }
    Map<Long, ErpPayrollCategoryView> categories() {
        return Map.of(1L, new ErpPayrollCategoryView(1L, null, "台球周边", 0),
                2L, new ErpPayrollCategoryView(2L, 1L, "台呢", 0),
                3L, new ErpPayrollCategoryView(3L, null, "方便面", 0));
    }
    Map<Long, ErpPayrollProductView> products() {
        return Map.of(10L, new ErpPayrollProductView(10L, 10L, "P10", "V10", "澳洋台呢", "A300", "SET", 2L, "台呢"),
                20L, new ErpPayrollProductView(20L, 20L, "P20", "V20", "方便面", "", "BUCKET", 3L, "方便面"));
    }

    @AfterEach void clear() { TestAuthorizationContext.clear(); }

    @Test void monthlySalesAndFullyPaidAchievementRemainIndependentAndExcludeUnpaidOrders() {
        var lines = List.of(line("L1", "OLD", "2026-09-30", 10, "200", "200"),
                line("L2", "NEW", "2026-10-01", 10, "100", "150"),
                line("L3", "NEW", "2026-10-01", 20, "100", "150"),
                line("L4", "UNPAID", "2026-10-31", 20, "25", "25"));
        var result = HrPayrollPerformanceExportService.summarize(row(), lines, products(), categories(), n("200"), n("350"));
        assertThat(result.salesAmount()).isEqualByComparingTo("175");
        assertThat(result.collectionAmount()).isEqualByComparingTo("350");
        assertThat(result.collectionAmount()).isEqualByComparingTo(ConfiguredPayrollService.achievement(lines, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new ArrayList<>()));
        assertThat(result.refundAmount()).isEqualByComparingTo("10");
        assertThat(result.categories()).filteredOn(c -> c.code().equals("CLOTH")).singleElement().satisfies(c -> {
            assertThat(c.salesAmount()).isEqualByComparingTo("75");
            assertThat(c.collectionAmount()).isEqualByComparingTo("275");
        });
        assertThat(result.clothSales()).filteredOn(c -> c.code().equals("A300_A500")).singleElement()
                .satisfies(c -> assertThat(c.salesAmount()).isEqualByComparingTo("75"));
        assertThat(result.commission()).isNull();
        assertThat(result.salesTarget()).isEqualByComparingTo("350");
        assertThat(result.issues()).contains("提成待核对，详见核对事项");
    }

    @Test void zeroInputsMissingTargetsAndUnclassifiedGoodsAreNotFabricated() {
        var result = HrPayrollPerformanceExportService.summarize(row(), List.of(line("L1", "UNPAID", "2026-10-01", 99, "12.34", "12.34")),
                Map.of(), categories(), null, null);
        assertThat(result.salesAmount()).isEqualByComparingTo("12.34");
        assertThat(result.collectionAmount()).isZero();
        assertThat(result.collectionTarget()).isNull();
        assertThat(result.salesTarget()).isNull();
        assertThat(result.issues()).contains("回款指标未配置", "部分商品未关联ERP分类，计入其他品类");
        assertThat(result.issues()).doesNotContain("销售指标未配置");
        assertThat(result.categories()).filteredOn(c -> c.code().equals("OTHER")).singleElement()
                .satisfies(c -> assertThat(c.salesAmount()).isEqualByComparingTo("12.34"));
    }

    @Test void centAllocationPreservesTotalsWithoutNegativeTailOrDuplicateOrderAmounts() {
        assertThat(HrPayrollPerformanceExportService.allocate(n("0.01"), List.of(n("1"), n("1"), n("1"))))
                .containsExactly(n("0.00"), n("0.01"), n("0.00"));
        assertThat(HrPayrollPerformanceExportService.allocate(n("100"), List.of(n("1"), n("2"))))
                .containsExactly(n("33.33"), n("66.67"));
        assertThatThrownBy(() -> HrPayrollPerformanceExportService.allocate(n("1"), List.of(n("0"))))
                .hasMessageContaining("商品金额为零");
    }

    @Test void duplicateOrIncompleteOrderEvidenceStopsExportInsteadOfInflatingSales() {
        var line = line("L1", "O1", "2026-10-01", 10, "10", "10");
        assertThatThrownBy(() -> HrPayrollPerformanceExportService.summarize(row(), List.of(line, line), products(), categories(), null, null))
                .hasMessageContaining("销售订单行重复");
        var bad = new OrderPayrollLineView(line.lineId(), line.orderId(), line.orderNo(), line.employeeCode(), line.cityCode(), line.departmentId(), line.productId(), line.productVariantId(), line.productName(), line.unitCode(), line.orderDate(), line.quantity(), line.lineAmount(), line.orderLineTotal(), line.orderAmount(), line.paidAmount(), line.fullyPaidDate(), line.refundedAmount(), null, "ATTRIBUTION_PENDING");
        assertThatThrownBy(() -> HrPayrollPerformanceExportService.summarize(row(), List.of(bad), products(), categories(), null, null))
                .hasMessageContaining("订单资料待核定");
    }

    @Test void authorizationAndSelectionMustPassBeforeReadingOtherDomains() {
        var workflow = mock(HrPayrollWorkflowService.class);
        var source = mock(HrPayrollSource.class);
        var targets = mock(TargetSettingsStore.class);
        var service = new HrPayrollPerformanceExportService(workflow, source, targets,
                mock(HrPayrollStore.class), mock(HrAttendanceStore.class));
        var query = new PayrollQuery(0, 200, "2026-10", null, null, null, null, null, null, List.of("P1"));
        assertThatThrownBy(() -> service.export(query)).isInstanceOf(AuthorizationDeniedException.class);
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("hr:payroll:read")));
        when(workflow.export(query)).thenThrow(new IllegalArgumentException("无权导出所选工资单"));
        assertThatThrownBy(() -> service.export(query)).hasMessageContaining("无权导出");
        verifyNoInteractions(source, targets);
    }
}
