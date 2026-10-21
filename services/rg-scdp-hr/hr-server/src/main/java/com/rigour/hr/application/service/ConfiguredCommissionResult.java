package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.PayrollCommissionDetailView;

import java.math.BigDecimal;
import java.util.List;

record ConfiguredCommissionResult(
        BigDecimal released,
        BigDecimal held,
        List<PayrollCommissionDetailView> details,
        List<String> issues) {}
