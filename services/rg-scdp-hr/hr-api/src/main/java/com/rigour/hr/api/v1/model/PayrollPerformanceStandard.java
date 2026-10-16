package com.rigour.hr.api.v1.model;

import java.util.List;

public record PayrollPerformanceStandard(
        String mode,
        PayrollSalesPolicy sales,
        PayrollManagerPolicy manager,
        List<PayrollGradeBand> grades) {
    public PayrollPerformanceStandard(
            String mode, PayrollSalesPolicy sales, PayrollManagerPolicy manager) {
        this(mode, sales, manager, List.of());
    }
}
