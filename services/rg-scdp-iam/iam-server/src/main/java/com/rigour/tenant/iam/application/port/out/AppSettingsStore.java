package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.ManagementModels.NavigationNode;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.*;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;

import java.util.List;
import java.util.UUID;

/** 供应链配置的单一持久化端口；写入包含权限、并发版本和审计事务。 */
public interface AppSettingsStore {
    Context context(Actor actor);

    Context initialize(Actor actor);

    List<Node> menus(Actor actor);

    List<Node> catalog(Actor actor);

    Node saveMenu(Actor actor, UUID id, MenuCommand command);

    Impact menuImpact(Actor actor, UUID id);

    void deleteMenu(Actor actor, UUID id, long version, boolean revokeGrants);

    List<NavigationNode> navigation(Actor actor);

    boolean initialized(Actor actor);

    AuditPage audits(Actor actor, String action, String keyword, int page, int pageSize);
}
