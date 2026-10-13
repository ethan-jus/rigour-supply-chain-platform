package com.rigour.hr.api.v1.model;

import java.util.List;

public record PayrollPreparationView(
        String month, List<PayrollPreparationEmployeeView> employees) {}
