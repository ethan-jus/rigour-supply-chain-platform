package com.rigour.tenant.iam.application.service.identity;

import com.rigour.tenant.iam.application.port.out.IdentityAccessReader;
import com.rigour.tenant.iam.application.port.out.AppSettingsStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import java.util.HashSet;
import java.util.Objects;

/** SCDP访问用例；授权计算留在IAM，前端只消费结果。 */
public final class IdentityAccessService {

    private final IdentityAccessReader reader;
    private final AppSettingsStore settings;

    public IdentityAccessService(IdentityAccessReader reader, AppSettingsStore settings) {
        this.reader = Objects.requireNonNull(reader, "reader cannot be null");
        this.settings = Objects.requireNonNull(settings, "settings cannot be null");
    }

    public CurrentUser currentUser(IdentityAccessQuery query) {
        var identity = reader.readCurrentUser(query);
        var actor = new Actor("TENANT", query.principalId(), query.tenantId());
        if (!settings.initialized(actor)) return identity;
        var context = settings.context(actor);
        var permissions = new HashSet<>(context.permissions());
        // 旧数据升级准备期保留供应链旧授权，启用新模型后仅使用应用角色授权。
        if (!"ACTIVE".equals(context.mode())) permissions.addAll(identity.permissions());
        return new CurrentUser(identity.id(), identity.tenantId(), identity.tenantName(),
                identity.principalScope(), identity.username(), identity.displayName(),
                "ACTIVE".equals(context.mode()) ? reader.readSupplyRoles(query) : identity.roles(),
                permissions);
    }

}
