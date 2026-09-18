package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.*;
import com.rigour.tenant.iam.application.service.settings.AppSettingsService;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 应用边界固定为供应链；租户和用户只来自已校验登录主体。 */
@RestController
@RequestMapping("/api/v1/management/supply")
public final class IamAppSettingsController {
    private final AppSettingsService service;

    public IamAppSettingsController(AppSettingsService service) {
        this.service = service;
    }

    @GetMapping("/context")
    public Context context() {
        return service.context(actor());
    }

    @PostMapping("/initialize")
    public Context initialize() {
        return service.initialize(actor());
    }

    @GetMapping("/menus")
    public List<Node> menus() {
        return service.menus(actor());
    }

    @GetMapping("/menu-catalog")
    public List<Node> catalog() {
        return service.catalog(actor());
    }

    @PostMapping("/menus")
    public Node create(@RequestBody MenuCommand command) {
        return service.saveMenu(actor(), null, command);
    }

    @PutMapping("/menus/{id}")
    public Node update(@PathVariable("id") UUID id, @RequestBody MenuCommand command) {
        return service.saveMenu(actor(), id, command);
    }

    @GetMapping("/menus/{id}/impact")
    public Impact impact(@PathVariable("id") UUID id) {
        return service.menuImpact(actor(), id);
    }

    @DeleteMapping("/menus/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") UUID id,
            @RequestParam("version") long version,
            @RequestParam(name = "revokeGrants", defaultValue = "false") boolean revoke) {
        service.deleteMenu(actor(), id, version, revoke);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audits")
    public AuditPage audits(
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int size) {
        return service.audits(actor(), action, keyword, page, size);
    }

    static Actor actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !authentication.isAuthenticated()) throw new AccessDeniedException("需要有效登录");
        String tenantId = jwt.getToken().getClaimAsString("tenantId");
        return new Actor(
                jwt.getToken().getClaimAsString("principalScope"),
                UUID.fromString(jwt.getToken().getClaimAsString("principalId")),
                tenantId == null ? null : UUID.fromString(tenantId));
    }
}
