package com.rigour.tenant.iam.api.v1;

import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 只接收已签名原操作人；调用方不能在正文指定其他用户或租户。 */
public interface IamSupplyAuthorizationApi {
    @GetMapping("/internal/v1/iam/supply/authorization")
    SupplyAuthorizationView authorization(@RequestParam String action);

    @GetMapping("/internal/v1/iam/supply/customer-assignment-target")
    com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView customerAssignmentTarget(
            @RequestParam(required = false) String employeeCode,
            @RequestParam(required = false) java.util.UUID userId);

}
