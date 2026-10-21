package com.rigour.hr.api.v1.model;

import java.util.List;

public record PayrollSchemeCheckView(List<String> errors, List<String> warnings) {}
