package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.service.management.IamStaffManagementService;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 系统管理人员中心HTTP边界；组织、岗位和账号绑定均以IAM主数据为准。 */
@RestController
@RequestMapping("/api/v1/management/tenant")
public final class IamStaffManagementController {
    private final IamStaffManagementService service;

    public IamStaffManagementController(IamStaffManagementService service) {
        this.service = service;
    }

    @GetMapping("/positions")
    public List<PositionView> positions() {
        return service.positions(currentActor());
    }

    @PostMapping("/positions")
    public PositionView createPosition(@RequestBody PositionCommand command) {
        return service.createPosition(currentActor(), command);
    }

    @PutMapping("/positions/{id}")
    public PositionView updatePosition(@PathVariable("id") UUID id,
                                       @RequestBody PositionCommand command) {
        return service.updatePosition(currentActor(), id, command);
    }

    @GetMapping("/staff")
    public List<StaffView> staff(@RequestParam(name = "keyword", required = false) String keyword,
                                 @RequestParam(name = "status", required = false) String status) {
        return service.staff(currentActor(), keyword, status);
    }

    @PostMapping("/staff")
    public StaffView createStaff(@RequestBody StaffCommand command) {
        return service.createStaff(currentActor(), command);
    }

    @PutMapping("/staff/{id}")
    public StaffView updateStaff(@PathVariable("id") UUID id, @RequestBody StaffCommand command) {
        return service.updateStaff(currentActor(), id, command);
    }

    @PostMapping("/staff/dinghuobao-sync")
    public StaffSyncResultView syncDinghuobaoStaff(@RequestBody DhbStaffSyncRequest request) {
        return service.syncDinghuobaoStaff(currentActor(), request);
    }

    private static Actor currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Access token is required");
        }
        var jwt = jwtAuthentication.getToken();
        String scope = jwt.getClaimAsString("principalScope");
        UUID principalId = UUID.fromString(jwt.getClaimAsString("principalId"));
        String tenant = jwt.getClaimAsString("tenantId");
        return new Actor(scope, principalId, tenant == null ? null : UUID.fromString(tenant));
    }
}
