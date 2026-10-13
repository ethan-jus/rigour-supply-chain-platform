package com.rigour.hr.domain.payroll;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

class PayrollCalculatorTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 9);

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private PayrollCalculator.Rule rule(String id, String role, String method, String rate) {
        return new PayrollCalculator.Rule(
                id,
                1,
                id,
                "COMMISSION",
                null,
                List.of(10L),
                1L,
                2L,
                "SET",
                role,
                method,
                n(rate),
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                true);
    }

    private PayrollCalculator.Fact fact(
            String role, String paid, LocalDate payment, boolean refund) {
        return new PayrollCalculator.Fact(
                "LINE1",
                role,
                "ORD1",
                "泡面",
                1L,
                2L,
                "SET",
                LocalDate.of(2026, 8, 1),
                10L,
                n("2"),
                n("600"),
                n("1000"),
                n("900"),
                n(paid),
                payment,
                refund,
                "COMPLETE");
    }

    private PayrollCalculator.Result calculate(
            List<PayrollCalculator.Rule> rules,
            List<PayrollCalculator.Fact> facts,
            Set<String> claimed) {
        return PayrollCalculator.calculate(
                "E1",
                10L,
                MONTH,
                LocalDate.of(2025, 1, 1),
                null,
                rules,
                new PayrollCalculator.Input(n("3000"), n("1000"), n("0")),
                facts,
                claimed);
    }

    @Test
    void partialPaymentHoldsEveryProductAndEveryRole() {
        var result =
                calculate(
                        List.of(
                                rule("SALES", "SALES", "PERCENT", "4"),
                                rule("MANAGER", "CITY_MANAGER", "PERCENT", "1")),
                        List.of(
                                fact("SALES", "899.99", LocalDate.of(2026, 9, 1), false),
                                fact("CITY_MANAGER", "899.99", LocalDate.of(2026, 9, 1), false)),
                        Set.of());
        assertThat(result.commission()).isEqualByComparingTo("0");
        assertThat(result.held()).isEqualByComparingTo("27.00");
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void fullPaymentAllocatesOrderDiscountAndKeepsBothRecipients() {
        var result =
                calculate(
                        List.of(
                                rule("SALES", "SALES", "PERCENT", "4"),
                                rule("MANAGER", "CITY_MANAGER", "PERCENT", "1")),
                        List.of(
                                fact("SALES", "900", LocalDate.of(2026, 9, 1), false),
                                fact("CITY_MANAGER", "900", LocalDate.of(2026, 9, 1), false)),
                        Set.of());
        assertThat(result.commission()).isEqualByComparingTo("27.00");
        assertThat(result.details())
                .extracting(d -> d.released().toPlainString())
                .containsExactly("21.60", "5.40");
    }

    @Test
    void billiardAccessoriesUseTenAndTwoPercent() {
        var result =
                calculate(
                        List.of(
                                rule("SALES", "SALES", "PERCENT", "10"),
                                rule("MANAGER", "CITY_MANAGER", "PERCENT", "2")),
                        List.of(
                                fact("SALES", "900", LocalDate.of(2026, 9, 1), false),
                                fact("CITY_MANAGER", "900", LocalDate.of(2026, 9, 1), false)),
                        Set.of());
        assertThat(result.commission()).isEqualByComparingTo("64.80");
    }

    @Test
    void percentageCommissionUsesMoneyWhenHistoricalOrderUnitDiffersFromCatalog() {
        var original = fact("SALES", "900", LocalDate.of(2026, 9, 1), false);
        var historical = new PayrollCalculator.Fact(
                original.id(), original.recipientRole(), original.orderNo(), original.productName(),
                original.productId(), original.variantId(), "BOX", original.date(),
                original.departmentId(), original.quantity(), original.lineAmount(),
                original.orderLineTotal(), original.orderAmount(), original.paidAmount(),
                original.paymentDate(), original.refund(), original.quality());
        var result = calculate(
                List.of(rule("S", "SALES", "PERCENT", "4")), List.of(historical), Set.of());

        assertThat(result.commission()).isEqualByComparingTo("21.60");
        assertThat(result.issues()).isEmpty();

        var quantityResult = calculate(
                List.of(rule("S", "SALES", "QUANTITY", "15")), List.of(historical), Set.of());
        assertThat(quantityResult.commission()).isEqualByComparingTo("0");
        assertThat(quantityResult.issues()).anyMatch(issue -> issue.contains("商品计奖单位不一致"));
    }

    @Test
    void clothUsesPerSetAmountsRatherThanSalePrice() {
        for (String[] rates :
                List.of(
                        new String[] {"15", "5", "40"},
                        new String[] {"25", "10", "70"},
                        new String[] {"35", "10", "90"})) {
            var result =
                    calculate(
                            List.of(
                                    rule("SALES", "SALES", "QUANTITY", rates[0]),
                                    rule("MANAGER", "CITY_MANAGER", "QUANTITY", rates[1])),
                            List.of(
                                    fact("SALES", "900", LocalDate.of(2026, 9, 1), false),
                                    fact("CITY_MANAGER", "900", LocalDate.of(2026, 9, 1), false)),
                            Set.of());
            assertThat(result.commission()).isEqualByComparingTo(rates[2]);
        }
    }

    @Test
    void claimForSalesDoesNotSuppressCityManagerButPreventsNextMonthDuplicate() {
        var rules =
                List.of(
                        rule("S", "SALES", "QUANTITY", "15"),
                        rule("M", "CITY_MANAGER", "QUANTITY", "5"));
        var facts =
                List.of(
                        fact("SALES", "900", LocalDate.of(2026, 9, 1), false),
                        fact("CITY_MANAGER", "900", LocalDate.of(2026, 9, 1), false));
        assertThat(calculate(rules, facts, Set.of("LINE1|SALES")).commission())
                .isEqualByComparingTo("10");
        assertThat(
                        calculate(rules, facts, Set.of("LINE1|SALES", "LINE1|CITY_MANAGER"))
                                .commission())
                .isEqualByComparingTo("0");
    }

    @Test
    void futurePaymentIsHeldAndHistoricalOrderCanReleaseNow() {
        assertThat(
                        calculate(
                                        List.of(rule("S", "SALES", "PERCENT", "4")),
                                        List.of(
                                                fact(
                                                        "SALES",
                                                        "900",
                                                        LocalDate.of(2026, 10, 1),
                                                        false)),
                                        Set.of())
                                .held())
                .isEqualByComparingTo("21.60");
        assertThat(
                        calculate(
                                        List.of(rule("S", "SALES", "PERCENT", "4")),
                                        List.of(
                                                fact(
                                                        "SALES",
                                                        "900",
                                                        LocalDate.of(2026, 9, 1),
                                                        false)),
                                        Set.of())
                                .commission())
                .isEqualByComparingTo("21.60");
    }

    @Test
    void missingPaymentDateCannotBecomePaid() {
        assertThat(
                        calculate(
                                        List.of(rule("S", "SALES", "PERCENT", "4")),
                                        List.of(fact("SALES", "900", null, false)),
                                        Set.of())
                                .commission())
                .isEqualByComparingTo("0");
    }

    @Test
    void refundAndRuleConflictBlockInsteadOfCalculatingZeroSilently() {
        var refund =
                calculate(
                        List.of(rule("S", "SALES", "PERCENT", "4")),
                        List.of(fact("SALES", "900", LocalDate.of(2026, 9, 1), true)),
                        Set.of());
        assertThat(refund.issues()).anyMatch(x -> x.contains("退款"));
        assertThat(refund.commission()).isEqualByComparingTo("0");
        var conflict =
                calculate(
                        List.of(
                                rule("A", "SALES", "PERCENT", "4"),
                                rule("B", "SALES", "PERCENT", "4")),
                        List.of(fact("SALES", "900", LocalDate.of(2026, 9, 1), false)),
                        Set.of());
        assertThat(conflict.issues()).anyMatch(x -> x.contains("冲突"));
    }

    @Test
    void unknownSalaryIsNotTreatedAsZero() {
        var r =
                PayrollCalculator.calculate(
                        "E1",
                        10L,
                        MONTH,
                        null,
                        null,
                        List.of(),
                        new PayrollCalculator.Input(null, null, n("0")),
                        List.of(),
                        Set.of());
        assertThat(r.base()).isNull();
        assertThat(r.net()).isNull();
        assertThat(r.issues()).isNotEmpty();
    }
}
