package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 本次核算的整单收款快照；回款原单仍由订单服务维护。 */
public record PayrollOrderSettlementView(
        BigDecimal orderAmount,
        BigDecimal paidAmount,
        BigDecimal refundedAmount,
        LocalDate through) {}
