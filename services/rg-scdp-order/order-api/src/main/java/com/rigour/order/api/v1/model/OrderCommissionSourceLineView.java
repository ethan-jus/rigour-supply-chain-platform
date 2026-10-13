package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OrderCommissionSourceLineView(
        String lineId,
        String orderNo,
        String employeeCode,
        String cityCode,
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
        LocalDate paymentDate,
        boolean refundExists,
        String qualityStatus) {}
