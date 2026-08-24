package com.rigour.merchant.api.v1.model;

/** 客户当前有效的人员归属；staffCode 来自 IAM 员工中心，sourceStaffId 仅保留来源追溯。 */
public record SalesAssignmentView(String assignmentType, String sourceStaffId,
                                  String staffCode, String staffName) {
    public SalesAssignmentView(String assignmentType, String sourceStaffId, String staffName) {
        this(assignmentType, sourceStaffId, null, staffName);
    }
}
