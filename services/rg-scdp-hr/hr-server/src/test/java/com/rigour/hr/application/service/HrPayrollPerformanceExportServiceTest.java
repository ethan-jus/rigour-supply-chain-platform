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

    OrderPayrollLineView quantityLine(String id, long variant, String ordered, String paid, String unit, String quantity) {
        return new OrderPayrollLineView(id, id, id, "E1", "C1", 1L, variant, variant, "台呢", unit,
                LocalDate.parse(ordered), quantity == null ? null : n(quantity), n("100"), n("100"), n("100"),
                n(paid == null ? "50" : "100"), paid == null ? null : LocalDate.parse(paid), n("0"), null, "COMPLETE");
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
                .satisfies(c -> {
                    assertThat(c.salesAmount()).isEqualByComparingTo("75");
                    assertThat(c.quantities()).singleElement().satisfies(q -> {
                        assertThat(q.unitCode()).isEqualTo("SET");
                        assertThat(q.quantity()).isEqualByComparingTo("2");
                    });
                });
        assertThat(result.commission()).isNull();
        assertThat(result.salesTarget()).isEqualByComparingTo("350");
        assertThat(result.issues()).contains("提成待核对，详见核对事项");
    }

    @Test void clothQuantitiesFollowFullyPaidMonthAcrossAllGroupsAndKeepOriginalUnits() {
        var products = new HashMap<>(products());
        for (var spec : Map.of(11L, "A500", 12L, "A500B", 13L, "A600", 14L, "A700", 15L, "A800").entrySet())
            products.put(spec.getKey(), new ErpPayrollProductView(spec.getKey(), spec.getKey(), "P", "V", "澳洋台呢", spec.getValue(), "SET", 2L, "台呢"));
        products.put(16L, new ErpPayrollProductView(16L, 16L, "P", "V", "澳洋斯诺克台呢", "Apex", "ROLL", 2L, "台呢"));
        var lines = List.of(
                quantityLine("old-a300", 10, "2026-09-30", "2026-10-01", "BED", "3"),
                quantityLine("old-a500", 11, "2026-05-10", "2026-10-31", "SET", "2"),
                quantityLine("current-a500b", 12, "2026-10-01", "2026-10-31", "ROLL", "1.5"),
                quantityLine("old-a600", 13, "2026-09-20", "2026-10-05", "BED", "2"),
                quantityLine("current-a700", 14, "2026-10-15", "2026-10-15", "SET", "4"),
                quantityLine("old-a800", 15, "2026-08-01", "2026-10-20", "BED", "1"),
                quantityLine("snooker", 16, "2026-10-01", "2026-10-01", "ROLL", "0.5"),
                quantityLine("unpaid", 10, "2026-10-15", null, "SET", "77"),
                quantityLine("next-month-paid", 10, "2026-10-31", "2026-11-01", "BED", "88"),
                quantityLine("previous-month-paid", 10, "2026-09-25", "2026-09-30", "BED", "99"));
        var result = HrPayrollPerformanceExportService.summarize(row(), lines, products, categories(), n("7000"), null);
        assertThat(result.salesAmount()).isEqualByComparingTo("500");
        assertThat(result.collectionAmount()).isEqualByComparingTo("700");
        var groups = result.clothSales().stream().collect(java.util.stream.Collectors.toMap(PayrollPerformanceExportView.ClothSales::code, java.util.function.Function.identity()));
        assertThat(groups.get("A300_A500").quantities().stream().collect(java.util.stream.Collectors.toMap(PayrollPerformanceExportView.UnitQuantity::unitCode, PayrollPerformanceExportView.UnitQuantity::quantity)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("BED", n("3"), "SET", n("2"), "ROLL", n("1.5")));
        assertThat(groups.get("A600_A800").quantities().stream().collect(java.util.stream.Collectors.toMap(PayrollPerformanceExportView.UnitQuantity::unitCode, PayrollPerformanceExportView.UnitQuantity::quantity)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("BED", n("3"), "SET", n("4")));
        assertThat(groups.get("SNOOKER").quantities()).containsExactly(new PayrollPerformanceExportView.UnitQuantity("ROLL", n("0.5")));
    }

    @Test void missingPaidClothQuantityOrUnitStopsExportButUnpaidQuantityIsNotRequired() {
        for (var line : List.of(
                quantityLine("missing-count", 10, "2026-09-30", "2026-10-01", "SET", null),
                quantityLine("negative-count", 10, "2026-09-30", "2026-10-01", "SET", "-1"),
                quantityLine("missing-unit", 10, "2026-09-30", "2026-10-01", null, "1"),
                quantityLine("blank-unit", 10, "2026-09-30", "2026-10-01", " ", "1")))
            assertThatThrownBy(() -> HrPayrollPerformanceExportService.summarize(row(), List.of(line), products(), categories(), null, null))
                    .hasMessageContaining("整单付清台呢的数量或单位缺失");
        var unpaid = quantityLine("unpaid-missing-count", 10, "2026-10-01", null, null, null);
        var result = HrPayrollPerformanceExportService.summarize(row(), List.of(unpaid), products(), categories(), null, null);
        assertThat(result.clothSales()).allSatisfy(group -> assertThat(group.quantities()).isEmpty());
        assertThat(result.salesAmount()).isEqualByComparingTo("100");
        assertThat(result.collectionAmount()).isZero();
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
