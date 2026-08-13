package com.rigour.merchant.api.v1.model;

/** 客户当前有效的订货宝业务员归属；PRIMARY 为主业务员，SECONDARY 为辅业务员。 */
public record SalesAssignmentView(String assignmentType, String sourceStaffId, String staffName) {
}
