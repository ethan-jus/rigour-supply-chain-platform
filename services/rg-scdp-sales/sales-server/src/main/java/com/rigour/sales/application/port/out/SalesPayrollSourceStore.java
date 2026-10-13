package com.rigour.sales.application.port.out;

import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;

import java.time.LocalDate;

public interface SalesPayrollSourceStore {
    SalesPayrollEvidenceView evidence(String tenant, String employee, LocalDate from, LocalDate to);
}
