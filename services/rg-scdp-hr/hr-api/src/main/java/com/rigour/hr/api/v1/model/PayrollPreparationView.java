package com.rigour.hr.api.v1.model;

import java.util.List;

public record PayrollPreparationView(
        String month,
        List<PayrollPreparationEmployeeView> employees,
        List<PayrollPreparationEmployeeView> formerEmployees) {
    public PayrollPreparationView(String month, List<PayrollPreparationEmployeeView> employees) {
        this(month, employees, List.of());
    }
}
