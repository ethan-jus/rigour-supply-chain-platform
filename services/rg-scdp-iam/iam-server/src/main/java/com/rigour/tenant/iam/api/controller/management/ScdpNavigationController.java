package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.port.out.AppSettingsStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.NavigationNode;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单产品导航；调用方不能选择其他应用或其他租户。 */
@RestController
public final class ScdpNavigationController {
    private final AppSettingsStore settings;

    public ScdpNavigationController(AppSettingsStore settings) { this.settings = settings; }

    @GetMapping("/api/v1/scdp/navigation")
    public List<NavigationNode> navigation() {
        var actor = IamAppSettingsController.actor();
        var context = settings.context(actor);
        if (context.initialized()) {
            if ("ACTIVE".equals(context.mode()) && context.permissions().isEmpty())
                throw new AccessDeniedException("供应链账号未获授权或已停用");
            return settings.navigation(actor);
        }
        if (!context.canInitialize()) throw new AccessDeniedException("请联系企业管理员开通供应链账号");
        // 新租户仅向受保护的首任管理员开放初始化入口，不授予业务权限。
        return List.of(new NavigationNode(
                UUID.fromString("019facf3-0000-7000-8000-000000000090"), null,
                "SUPPLY_CHAIN.SETTINGS", "PAGE", "系统设置", null,
                "supply.setting.index", "/supply-chain/settings", null, "Setting",
                0, true, false, List.of()));
    }
}
