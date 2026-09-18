package com.rigour.merchant.api.v1.model;

/** 客户当前有效的人员归属；sourceStaffId 仅保留第三方来源追溯。 */
public record SalesAssignmentView(String assignmentType, String sourceStaffId,
                                  String employeeCode, String employeeName) {
    public SalesAssignmentView(String assignmentType, String sourceStaffId, String employeeName) {
        this(assignmentType, sourceStaffId, null, employeeName);
    }
}
