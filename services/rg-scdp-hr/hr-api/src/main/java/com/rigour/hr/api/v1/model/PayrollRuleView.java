package com.rigour.hr.api.v1.model;

import java.time.Instant;

public record PayrollRuleView(
        String id, int revision, PayrollRuleCommand data, Instant updatedTime) {}
