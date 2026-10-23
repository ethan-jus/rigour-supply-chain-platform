package com.rigour.hr.api.v1.model;

/** One employee and month: monetary adjustments and monthly performance facts. */
public record PayrollDeterminationCommand(
        PayrollInputCommand input, PayrollSchemeFactsCommand facts) {}
