package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

/** 销售固定月补贴，统一在其他薪资设置中维护。 */
public record PayrollFixedAllowance(BigDecimal vehicleAllowance, BigDecimal phoneAllowance, BigDecimal computerAllowance) {}
