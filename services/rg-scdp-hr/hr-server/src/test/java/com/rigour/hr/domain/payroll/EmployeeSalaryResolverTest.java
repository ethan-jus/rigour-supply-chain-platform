package com.rigour.hr.domain.payroll;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;

class EmployeeSalaryResolverTest {
    final YearMonth month = YearMonth.of(2026, 9);
    final LocalDate start = LocalDate.of(2026, 1, 1);
    final List<String> issues = new ArrayList<>();

    @Test void choosesProbationForHistoricalMonthBeforeRegularization() {
        assertThat(EmployeeSalaryResolver.resolve(month, true, LocalDate.of(2026,10,1), start, "5000", "3000", issues))
                .isEqualByComparingTo("3000.00");
        assertThat(issues).isEmpty();
    }
    @Test void firstDayRegularizationUsesRegularAmount() {
        assertThat(EmployeeSalaryResolver.resolve(month, true, month.atDay(1), start, "5000.50", "3000", issues))
                .isEqualByComparingTo("5000.50");
    }
    @Test void midMonthRegularizationRequiresExplicitMonthlyAdjustment() {
        assertThat(EmployeeSalaryResolver.resolve(month, true, month.atDay(15), start, "5000", "3000", issues)).isNull();
        assertThat(issues).anyMatch(x -> x.contains("中途转正"));
    }
    @Test void neverAddsCompositeSalaryTextOrTreatsMissingAsZero() {
        assertThat(EmployeeSalaryResolver.resolve(month, false, null, start, "5000", "3000+1000", issues)).isNull();
        assertThat(issues).anyMatch(x -> x.contains("明确金额"));
        issues.clear();
        assertThat(EmployeeSalaryResolver.resolve(month, null, null, start, "5000", "3000", issues)).isNull();
        assertThat(issues).anyMatch(x -> x.contains("是否转正"));
    }
    @Test void salaryNotEffectiveForWholeMonthIsNotAppliedRetroactively() {
        assertThat(EmployeeSalaryResolver.resolve(month, false, null, month.atDay(2), "5000", "3000", issues)).isNull();
        assertThat(issues).anyMatch(x -> x.contains("生效日期"));
    }
}
