package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 整单付清核算事实；金额构成缺证据时保留null，不能当作零。 */
public record OrderPayrollLineView(
        String lineId,
        String orderId,
        String orderNo,
        String employeeCode,
        String cityCode,
        Long departmentId,
        Long productId,
        Long productVariantId,
        String productName,
        String unitCode,
        LocalDate orderDate,
        BigDecimal quantity,
        BigDecimal lineAmount,
        BigDecimal orderLineTotal,
        BigDecimal orderAmount,
        BigDecimal paidAmount,
        LocalDate fullyPaidDate,
        BigDecimal refundedAmount,
        LocalDate lastRefundDate,
        String qualityStatus) {}
