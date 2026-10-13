package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

/** 末尾三项仅兼容已确认工资的历史快照；固定补贴由 PayrollFixedAllowance 维护。 */
public record PayrollPerformancePolicy(
        String mode,
        BigDecimal bronzeBase,
        String firstMonthMode,
        Integer processPercent,
        Integer dailyCheckins,
        BigDecimal checkinPenalty,
        Integer dailyVisits,
        Integer minimumSeconds,
        BigDecimal visitPenalty,
        BigDecimal secondMonthTarget,
        BigDecimal laterMonthTarget,
        BigDecimal vehicleAllowance,
        BigDecimal phoneAllowance,
        BigDecimal computerAllowance) {}
