package com.rigour.sales.api.v1.model;

import java.util.List;

public record SalesPayrollEvidenceView(
        boolean employeeLinked, List<SalesPayrollVisitView> visits) {}
