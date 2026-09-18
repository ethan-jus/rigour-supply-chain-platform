package com.rigour.tenant.iam.api.controller.internal;

import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.tenant.iam.application.service.management.IamStaffManagementService;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.StaffDisplayRequest;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.StaffDisplayView;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** IAM人员中心内部展示查询；供领域服务按员工编码批量补齐页面展示名。 */
@RestController
@RequestMapping("/internal/v1/iam/staff")
public final class InternalIamStaffController {
    private final IamStaffManagementService service;

    public InternalIamStaffController(IamStaffManagementService service) {
        this.service = service;
    }

    @PostMapping("/display")
    public ApiResponse<List<StaffDisplayView>> display(@RequestBody StaffDisplayRequest request) {
        CallerIdentity caller = serviceCaller("iam:staff:read");
        return ApiResponse.success(service.resolveStaffDisplay(actor(caller), request));
    }

    private static CallerIdentity serviceCaller(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static Actor actor(CallerIdentity caller) {
        return new Actor(caller.principalScope(), caller.principalId(), caller.tenantId());
    }
}
