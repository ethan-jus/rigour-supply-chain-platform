package com.rigour.tenant.iam.api.controller.internal;

import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.tenant.iam.application.service.management.IamStaffManagementService;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.DhbStaffResolveRequest;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.DhbStaffResolvedView;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.DhbStaffSyncRequest;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.StaffSyncResultView;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration 调用 IAM 人员中心的内部入口；只接受可信服务上下文。 */
@RestController
@RequestMapping("/internal/v1/iam/dhb/staff")
public final class InternalIamDhbStaffController {
    private final IamStaffManagementService service;

    public InternalIamDhbStaffController(IamStaffManagementService service) {
        this.service = service;
    }

    @PostMapping("/sync")
    public ApiResponse<StaffSyncResultView> sync(@RequestBody DhbStaffSyncRequest request) {
        CallerIdentity caller = serviceCaller("iam:staff:sync");
        return ApiResponse.success(service.syncDinghuobaoStaff(actor(caller), request));
    }

    @PostMapping("/resolve")
    public ApiResponse<List<DhbStaffResolvedView>> resolve(@RequestBody DhbStaffResolveRequest request) {
        CallerIdentity caller = serviceCaller("iam:staff:read");
        return ApiResponse.success(service.resolveDinghuobaoStaff(actor(caller), request));
    }

    private static CallerIdentity serviceCaller(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(caller.principalScope())) {
            AuthorizationContext.requirePermission(permission);
            return caller;
        }
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static Actor actor(CallerIdentity caller) {
        return new Actor(caller.principalScope(), caller.principalId(), caller.tenantId());
    }
}
