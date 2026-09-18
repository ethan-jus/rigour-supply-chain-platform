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

    @GetMapping("/internal/v1/iam/supply/candidate")
    SupplyAuthorizationView candidate(@RequestParam String action);

    @org.springframework.web.bind.annotation.PostMapping(
            "/internal/v1/iam/supply/data-observations")
    void observeData(
            @org.springframework.web.bind.annotation.RequestBody
                    com.rigour.tenant.iam.api.v1.model.SupplyDataObservation request);

    record Observation(String action, String legacyAction) {}

    @org.springframework.web.bind.annotation.PostMapping("/internal/v1/iam/supply/observations")
    void observe(@org.springframework.web.bind.annotation.RequestBody Observation request);
}
