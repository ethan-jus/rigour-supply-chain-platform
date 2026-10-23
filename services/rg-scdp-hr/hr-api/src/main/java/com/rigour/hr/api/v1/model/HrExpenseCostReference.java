package com.rigour.hr.api.v1.model;

/** 只允许关联同租户、同金额、同城市和月份的已确认原费用。 */
public record HrExpenseCostReference(String source, String category, String id, long revision) {}
