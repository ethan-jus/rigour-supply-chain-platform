package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.tenant.iam.application.port.out.AppPermissionPreviewStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppPermissionPreviewModels.PermissionPreview;
import com.rigour.tenant.iam.domain.model.settings.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 预览当前已保存且生效的权限，不参与授权切换。 */
@Repository
public class JdbcAppPermissionPreviewStore implements AppPermissionPreviewStore {
    private final JdbcTemplate jdbc;
    private final JdbcAppSettingsStore settings;
    private final JdbcAppAuthorizationStore authorization;

    public JdbcAppPermissionPreviewStore(
            JdbcTemplate jdbc,
            JdbcAppSettingsStore settings,
            JdbcAppAuthorizationStore authorization) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.authorization = authorization;
    }

    private void require(Actor actor) {
        settings.requirePermission(actor, "supply:role:grant");
        if (settings.protectedAdministratorRole(actor, null) == null)
            throw new org.springframework.security.access.AccessDeniedException("仅受保护管理员可预览其他账号权限");
    }

    @Transactional(readOnly = true)
    public PermissionPreview preview(Actor reviewer, UUID user, String action) {
        require(reviewer);
        var target = new Actor("TENANT", user, reviewer.tenantId());
        settings.requireIdentity(target);
        var current = settings.context(target);
        var catalog =
                settings.catalog(reviewer).stream()
                        .map(AppMenuTree.Node::permissionCode)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        String username =
                jdbc.queryForObject(
                        "SELECT username FROM iam_user WHERE tenant_id=? AND id=?",
                        String.class,
                        bin(reviewer.tenantId()),
                        bin(user));
        boolean eligible = settings.eligible(target);
        var next = eligible ? current.permissions().stream().sorted().toList() : List.<String>of();
        String selected =
                action == null || action.isBlank()
                        ? next.stream()
                                .filter(code -> AppScopeRules.objectType(code) != null)
                                .findFirst()
                                .orElse(null)
                        : action;
        if (selected != null && !catalog.contains(selected))
            throw new RequestValidationException("仅可预览供应链已注册操作");
        var policy = eligible && selected != null ? authorization.proposed(target, selected) : null;
        return new PermissionPreview(
                user,
                username,
                current.version(),
                next,
                selected,
                policy,
                eligible ? null : "供应链成员或员工当前不可用，无业务访问权");
    }
}
