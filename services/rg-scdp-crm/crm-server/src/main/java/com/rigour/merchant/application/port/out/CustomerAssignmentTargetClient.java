package com.rigour.merchant.application.port.out;

import com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView;

import java.util.UUID;

/** 仅在线读取 IAM 成员与地区上限，不在 CRM 复制一份用户授权。 */
public interface CustomerAssignmentTargetClient {
    CustomerAssignmentTargetView byUser(String tenant, UUID userId);

    CustomerAssignmentTargetView byEmployee(String tenant, String employeeCode);
}
