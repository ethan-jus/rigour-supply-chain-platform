package com.rigour.hr.application.service;

import java.math.BigDecimal;

record PayrollSchemeInterval(
        BigDecimal lower, BigDecimal upper, boolean lowerInclusive, boolean upperInclusive) {}
