package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.*;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.order.api.v1.model.OrderPayrollLineView;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class ConfiguredCommissionServiceTest {
    HrPayrollStore store;
    HrPayrollSource source;
    ConfiguredCommissionService service;
    PayrollSchemeVersionView scheme;
    PayrollEmployeeView employee;

    static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @BeforeEach
    void setup() {
        store = mock(HrPayrollStore.class);
        source = mock(HrPayrollSource.class);
        service = new ConfiguredCommissionService(store, source);
        scheme = PayrollSchemeTestFixture.version(PayrollSchemeTestFixture.definition());
        employee =
                new PayrollEmployeeView(
                        "A", "测试", 1L, "销售", null, LocalDate.of(2026, 9, 1), null, "SALES");
        when(store.departmentAt(anyString(), anyString(), any())).thenReturn(1L);
        when(store.released("t")).thenReturn(Set.of());
        when(source.productsByVariants(eq("t"), anyList()))
                .thenReturn(List.of(product(1, 10), product(2, 20), product(3, 21)));
        when(source.productCategories("t"))
                .thenReturn(
                        List.of(
                                new ErpPayrollCategoryView(10L, null, "方便面", 0),
                                new ErpPayrollCategoryView(20L, null, "周边", 0),
                                new ErpPayrollCategoryView(21L, 20L, "台呢", 0)));
    }

    ErpPayrollProductView product(long id, long category) {
        return new ErpPayrollProductView(
                id, id, "P" + id, "SKU" + id, "商品" + id, "规格", "BOX", category, "分类");
    }

    OrderPayrollLineView line(String id, long sku, String city, boolean paid) {
        return new OrderPayrollLineView(
                id,
                "O" + id,
                "NO" + id,
                "A",
                city,
                1L,
                sku,
                sku,
                "商品" + sku,
                "BOX",
                LocalDate.of(2026, 9, 2),
                n("2"),
                n("1000"),
                n("1000"),
                n("1000"),
                n(paid ? "1000" : "500"),
                paid ? LocalDate.of(2026, 10, 2) : null,
                BigDecimal.ZERO,
                null,
                "COMPLETE");
    }

    PayrollRuleView assignment(String city, String person, LocalDate from, LocalDate to) {
        var c =
                new PayrollRuleCommand(
                        "a" + city,
                        0,
                        "任职",
                        "CITY_MANAGER",
                        person,
                        List.of(),
                        city,
                        city,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        from,
                        to,
                        true,
                        "",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        return new PayrollRuleView("r" + city + person, 0, c, Instant.EPOCH);
    }

    ConfiguredCommissionResult calculate(
            List<OrderPayrollLineView> lines, List<PayrollRuleView> assignments) {
        return service.calculate(
                "t", employee, YearMonth.of(2026, 10), scheme, List.of(scheme), lines, assignments);
    }

    @Test
    void directRulesKeepOriginalManagerAndPayBothRolesWithoutPublishedScheme() {
        var catalog = mock(HrPayrollComponentStore.class);
        service.setRules(new PayrollRuleResolver(catalog));
        var original = scheme;
        var d = original.definition();
        scheme =
                new PayrollSchemeVersionView(
                        original.id(),
                        original.schemeId(),
                        original.version(),
                        original.revision(),
                        "RESOLVED",
                        d,
                        original.reason(),
                        original.createdBy(),
                        original.publishedBy(),
                        original.updatedTime(),
                        original.publishedTime(),
                        original.stopFrom());
        var rule =
                new PayrollComponentVersionView(
                        "commission-v1",
                        "commission-family",
                        1,
                        new PayrollComponentDefinition(
                                "提成",
                                "COMMISSION",
                                "ALL",
                                List.of(1L),
                                d.effectiveFrom(),
                                d.effectiveTo(),
                                null,
                                null,
                                d.commission(),
                                null,
                                null,
                                List.of()),
                        "规则",
                        "actor",
                        Instant.EPOCH,
                        "PCR20261015000001");
        when(catalog.versions("t")).thenReturn(List.of(rule));
        var result =
                calculate(
                        List.of(line("1", 1, "C", true)),
                        List.of(
                                assignment(
                                        "C",
                                        "A",
                                        LocalDate.of(2026, 9, 1),
                                        LocalDate.of(2026, 9, 30)),
                                assignment("C", "B", LocalDate.of(2026, 10, 1), LocalDate.MAX)));
        assertThat(result.issues()).isEmpty();
        assertThat(result.released()).isEqualByComparingTo("50");
        assertThat(result.details()).hasSize(2);
        assertThat(result.details())
                .allSatisfy(
                        detail -> {
                            assertThat(detail.trace().schemeVersionId()).isEqualTo(rule.id());
                            assertThat(detail.trace().appliedRule().ruleCode())
                                    .isEqualTo("PCR20261015000001");
                            assertThat(detail.trace().product().variantCode()).isEqualTo("SKU1");
                            assertThat(detail.trace().product().categoryPath()).isEqualTo("方便面");
                            assertThat(detail.trace().orderDate())
                                    .isEqualTo(LocalDate.of(2026, 9, 2));
                            assertThat(detail.trace().settlement().orderAmount())
                                    .isEqualByComparingTo("1000");
                            assertThat(detail.trace().settlement().paidAmount())
                                    .isEqualByComparingTo("1000");
                            assertThat(detail.trace().settlement().through())
                                    .isEqualTo(LocalDate.of(2026, 10, 31));
                        });
    }

    @Test
    void managerUsesOwnRuleDepartmentForTeamOrders() {
        var catalog = mock(HrPayrollComponentStore.class);
        service.setRules(new PayrollRuleResolver(catalog));
        var d = scheme.definition();
        scheme =
                new PayrollSchemeVersionView(
                        scheme.id(),
                        scheme.schemeId(),
                        scheme.version(),
                        scheme.revision(),
                        "RESOLVED",
                        d,
                        scheme.reason(),
                        scheme.createdBy(),
                        scheme.publishedBy(),
                        scheme.updatedTime(),
                        scheme.publishedTime(),
                        scheme.stopFrom());

        when(catalog.versions("t"))
                .thenReturn(
                        List.of(
                                new PayrollComponentVersionView(
                                        "commission-v1",
                                        "family",
                                        1,
                                        new PayrollComponentDefinition(
                                                "提成",
                                                "COMMISSION",
                                                "ALL",
                                                List.of(1L),
                                                d.effectiveFrom(),
                                                d.effectiveTo(),
                                                null,
                                                null,
                                                d.commission(),
                                                null),
                                        "规则",
                                        "actor",
                                        Instant.EPOCH,
                                        "PCR20261015000001")));
        employee =
                new PayrollEmployeeView(
                        "M", "城市总", 1L, "北京", null, LocalDate.of(2026, 9, 1), null, "CITY_MANAGER");
        when(store.departmentAt("t", "A", LocalDate.of(2026, 9, 2))).thenReturn(2L);
        var f = line("team", 1, "C", true);
        var teamOrder =
                new OrderPayrollLineView(
                        f.lineId(),
                        f.orderId(),
                        f.orderNo(),
                        "A",
                        "C",
                        2L,
                        f.productId(),
                        f.productVariantId(),
                        f.productName(),
                        f.unitCode(),
                        f.orderDate(),
                        f.quantity(),
                        f.lineAmount(),
                        f.orderLineTotal(),
                        f.orderAmount(),
                        f.paidAmount(),
                        f.fullyPaidDate(),
                        f.refundedAmount(),
                        f.lastRefundDate(),
                        f.qualityStatus());
        var result =
                calculate(
                        List.of(teamOrder),
                        List.of(assignment("C", "M", LocalDate.of(2026, 9, 1), LocalDate.MAX)));
        assertThat(result.issues()).isEmpty();
        assertThat(result.released()).isEqualByComparingTo("10");
        assertThat(result.details())
                .singleElement()
                .satisfies(
                        detail -> {
                            assertThat(detail.recipientRoleCode()).isEqualTo("CITY_MANAGER");
                            assertThat(detail.sourceEmployeeCode()).isEqualTo("A");
                            assertThat(detail.trace().appliedRule().ruleCode())
                                    .isEqualTo("PCR20261015000001");
                        });
    }

    @Test
    void missingProductRetainsPendingEvidenceAlongsideCalculatedCommission() {
        var result =
                calculate(
                        List.of(line("1", 1, "C", true), line("missing", 99, "C", true)),
                        List.of());
        assertThat(result.issues()).isNotEmpty();
        assertThat(result.released()).isEqualByComparingTo("40");
        assertThat(result.details()).hasSize(2);
        var missing =
                result.details().stream()
                        .filter(d -> d.sourceLineId().equals("missing"))
                        .findFirst()
                        .orElseThrow();
        assertThat(missing.accrued()).isNull();
        assertThat(missing.released()).isNull();
        assertThat(missing.orderNo()).isEqualTo("NOmissing");
        assertThat(missing.trace().variantId()).isEqualTo(99L);
    }

    @Test
    void allProductsWaitForFullOrderAndAllowSeparateClothRule() {
        var unpaid =
                calculate(
                        List.of(
                                line("1", 1, "C", false),
                                line("2", 2, "C", false),
                                line("3", 3, "C", false)),
                        List.of());
        assertThat(unpaid.released()).isEqualByComparingTo("0");
        assertThat(unpaid.held()).isZero();
        assertThat(unpaid.details()).isEmpty();
        var paid =
                calculate(
                        List.of(
                                line("1", 1, "C", true),
                                line("2", 2, "C", true),
                                line("3", 3, "C", true)),
                        List.of());
        assertThat(paid.released()).isEqualByComparingTo("150");
        assertThat(paid.issues()).isEmpty();
    }

    @Test
    void twoCitiesAndOwnSalesArePaidOncePerRole() {
        var assignments =
                List.of(
                        assignment("C1", "A", LocalDate.of(2026, 9, 1), LocalDate.MAX),
                        assignment("C2", "A", LocalDate.of(2026, 9, 1), LocalDate.MAX));
        var result =
                calculate(List.of(line("1", 1, "C1", true), line("2", 2, "C2", true)), assignments);
        assertThat(result.released()).isEqualByComparingTo("170");
        assertThat(result.details()).hasSize(4);
        when(store.released("t"))
                .thenReturn(Set.of("1|SALES", "1|CITY_MANAGER", "2|SALES", "2|CITY_MANAGER"));
        assertThat(
                        calculate(
                                        List.of(line("1", 1, "C1", true), line("2", 2, "C2", true)),
                                        assignments)
                                .released())
                .isZero();
    }

    @Test
    void orderDateAttributionKeepsFormerManagerAfterHandover() {
        var result =
                calculate(
                        List.of(line("1", 1, "C", true)),
                        List.of(
                                assignment(
                                        "C",
                                        "A",
                                        LocalDate.of(2026, 9, 1),
                                        LocalDate.of(2026, 9, 30)),
                                assignment("C", "B", LocalDate.of(2026, 10, 1), LocalDate.MAX)));
        assertThat(result.released()).isEqualByComparingTo("50");
    }

    @Test
    void fullyPaidAchievementIncludesWholeOrderOnlyOnce() {
        var line = line("1", 1, "C", true);
        var issues = new ArrayList<String>();
        assertThat(
                        ConfiguredPayrollService.achievement(
                                List.of(line, line),
                                LocalDate.of(2026, 10, 1),
                                LocalDate.of(2026, 10, 31),
                                issues))
                .isEqualByComparingTo("1000");
        assertThat(
                        ConfiguredPayrollService.achievement(
                                List.of(line),
                                LocalDate.of(2026, 9, 1),
                                LocalDate.of(2026, 9, 30),
                                issues))
                .isZero();
    }

    @Test
    void fixtureIsPublishableAndEmptyDraftIsRejected() {
        assertThat(PayrollSchemeValidator.check(scheme.definition()).errors()).isEmpty();
        assertThat(PayrollSchemeValidator.check(null).errors()).isNotEmpty();
    }

    @Test
    void pastFullyPaidOrdersAreNotSilentlyMovedToCurrentPayroll() {
        var result =
                service.calculate(
                        "t",
                        employee,
                        YearMonth.of(2026, 11),
                        scheme,
                        List.of(scheme),
                        List.of(line("past", 1, "C", true)),
                        List.of());
        assertThat(result.details()).isEmpty();
        assertThat(result.released()).isZero();
        verify(source, never()).productsByVariants(anyString(), anyList());
    }
}
