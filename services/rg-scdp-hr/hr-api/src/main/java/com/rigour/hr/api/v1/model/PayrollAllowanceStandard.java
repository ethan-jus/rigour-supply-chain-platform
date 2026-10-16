package com.rigour.hr.api.v1.model;

import java.util.List;

public record PayrollAllowanceStandard(List<PayrollSchemeAllowance> items, String proration) {}
