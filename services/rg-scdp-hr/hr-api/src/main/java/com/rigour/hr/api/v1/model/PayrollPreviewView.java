package com.rigour.hr.api.v1.model;

import java.util.List;

public record PayrollPreviewView(
        String employeeCode,
        String employeeName,
        List<String> issues,
        boolean sourceChanged,
        String previousFingerprint,
        String currentFingerprint) {
    public PayrollPreviewView(String employeeCode, String employeeName, List<String> issues) {
        this(employeeCode, employeeName, issues, false, null, null);
    }
}
