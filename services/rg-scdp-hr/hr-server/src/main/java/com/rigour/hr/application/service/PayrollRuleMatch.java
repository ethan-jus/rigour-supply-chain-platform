package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.PayrollSchemeVersionView;

import java.util.List;

/** An automatically assembled calculation snapshot, never a separately maintained scheme. */
public record PayrollRuleMatch(PayrollSchemeVersionView snapshot, List<String> issues) {}
