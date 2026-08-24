package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.service.management.IamRolePermissionService;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.RolePermissionModels.*;
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
import org.springframework.web.bind.annotation.RestController;

/** 系统管理角色权限HTTP边界；订货宝角色只可作为业务参考，不作为权限事实。 */
@RestController
@RequestMapping("/api/v1/management/tenant/role-permissions")
public final class IamRolePermissionController {
    private final IamRolePermissionService service;

    public IamRolePermissionController(IamRolePermissionService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public List<RolePermissionView> roles() {
        return service.roles(currentActor());
    }

    @GetMapping("/grantable-resources")
    public List<GrantableResourceView> grantableResources() {
        return service.grantableResources(currentActor());
    }

    @PostMapping("/roles")
    public RolePermissionView createRole(@RequestBody RolePermissionCommand command) {
        return service.createRole(currentActor(), command);
    }

    @PutMapping("/roles/{id}")
    public RolePermissionView updateRole(@PathVariable("id") UUID id,
                                         @RequestBody RolePermissionCommand command) {
        return service.updateRole(currentActor(), id, command);
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
