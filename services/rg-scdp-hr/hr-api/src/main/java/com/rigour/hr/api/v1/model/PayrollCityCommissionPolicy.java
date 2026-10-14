package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** 城市总本人配置；products 为 null 时沿用部门商品标准，空列表表示不计独立商品提成。 */
public record PayrollCityCommissionPolicy(
        BigDecimal teamPercent, List<PayrollCityProductRule> products) {}
