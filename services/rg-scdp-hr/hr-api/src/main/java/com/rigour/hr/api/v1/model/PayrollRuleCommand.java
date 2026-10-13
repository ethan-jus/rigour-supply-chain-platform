package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollRuleCommand(
        String requestId,
        Integer revision,
        String name,
        String kindCode,
        String employeeCode,
        List<Long> departmentIds,
        String cityCode,
        String cityName,
        Boolean includeOwnSalesCommission,
        Long productId,
        Long productVariantId,
        String productName,
        String unitCode,
        String groupCode,
        String recipientRoleCode,
        String methodCode,
        BigDecimal rate,
        BigDecimal amount,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean enabled,
        String remark,
        PayrollPerformancePolicy performancePolicy, String allowanceTypeCode, PayrollFixedAllowance fixedAllowance) {}
