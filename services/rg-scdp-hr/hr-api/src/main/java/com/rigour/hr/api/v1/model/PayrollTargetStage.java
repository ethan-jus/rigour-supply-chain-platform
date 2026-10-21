package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollTargetStage(int fromMonth, Integer toMonth, BigDecimal target) {}
