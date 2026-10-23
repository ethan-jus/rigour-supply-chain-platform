package com.rigour.hr.api.v1.model;

import java.util.List;

/** Read-only current calculation and historical snapshot; never confirms or overwrites a salary. */
public record PayrollPreviewView(
        String employeeCode,
        String employeeName,
        List<String> issues,
        boolean sourceChanged,
        String previousFingerprint,
        String currentFingerprint,
        String savedStatus,
        PayrollCalculationView calculation,
        PayrollCalculationView previousCalculation) {
    public PayrollPreviewView(String employeeCode, String employeeName, List<String> issues) {
        this(employeeCode, employeeName, issues, false, null, null, null, null, null);
    }
}
