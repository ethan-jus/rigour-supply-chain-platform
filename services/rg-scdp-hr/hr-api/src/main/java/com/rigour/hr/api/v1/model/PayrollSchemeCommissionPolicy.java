package com.rigour.hr.api.v1.model;

import java.util.List;

/** 本期按商品整单付清基数计提，不启用税费、余额拆分及商品退款分配。 */
public record PayrollSchemeCommissionPolicy(
        String managerAttribution, String standardDate, List<PayrollSchemeCommissionRule> rules) {}
