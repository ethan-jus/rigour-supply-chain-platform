package com.rigour.hr.application.model;

import java.math.BigDecimal;
import java.util.List;

/** Target and its original city revisions are frozen in the payroll evidence fingerprint. */
public record ManagerCityTarget(
        BigDecimal amount,
        String evidence,
        List<String> issues,
        List<com.rigour.hr.api.v1.model.TargetSettingsModels.Target> sources) {
    public ManagerCityTarget(BigDecimal amount, String evidence, List<String> issues) {
        this(amount, evidence, issues, List.of());
    }
}
