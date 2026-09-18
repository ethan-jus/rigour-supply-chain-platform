package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.service.settings.AppAccessModels.*;
import com.rigour.tenant.iam.application.service.settings.AppRoleService;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/management/supply")
public final class IamAppRoleController {
    private final AppRoleService service;

    public IamAppRoleController(AppRoleService service) {
        this.service = service;
    }

    @GetMapping("/member-role-catalog")
    public List<Role> memberRoles() {
        return service.memberRoles(IamAppSettingsController.actor());
    }

    @GetMapping("/roles")
    public List<Role> roles() {
        return service.roles(IamAppSettingsController.actor());
    }

    @GetMapping("/role-menu-catalog")
    public List<Node> menus() {
        return service.menus(IamAppSettingsController.actor());
    }

    @PostMapping("/roles")
    public Role create(@RequestBody RoleCommand c) {
        return service.save(IamAppSettingsController.actor(), null, c);
    }

    @PutMapping("/roles/{id}")
    public Role update(@PathVariable UUID id, @RequestBody RoleCommand c) {
        return service.save(IamAppSettingsController.actor(), id, c);
    }

    @GetMapping("/roles/{id}/impact")
    public RoleImpact impact(@PathVariable UUID id) {
        return service.impact(IamAppSettingsController.actor(), id);
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestParam long version,
            @RequestParam(defaultValue = "false") boolean revokeMembers) {
        service.delete(IamAppSettingsController.actor(), id, version, revokeMembers);
        return ResponseEntity.noContent().build();
    }
}
