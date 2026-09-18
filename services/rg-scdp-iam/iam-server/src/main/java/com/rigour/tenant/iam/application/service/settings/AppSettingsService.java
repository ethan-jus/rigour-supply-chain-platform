package com.rigour.tenant.iam.application.service.settings;

import com.rigour.tenant.iam.application.port.out.AppSettingsStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.*;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 供应链设置用例入口；独立于SCDP全租户管理用例。 */
@Service
public final class AppSettingsService {
    private final AppSettingsStore store;

    public AppSettingsService(AppSettingsStore store) {
        this.store = store;
    }

    public Context context(Actor actor) {
        return store.context(actor);
    }

    public Context initialize(Actor actor) {
        return store.initialize(actor);
    }

    public List<Node> menus(Actor actor) {
        return store.menus(actor);
    }

    public List<Node> catalog(Actor actor) {
        return store.catalog(actor);
    }

    public Node saveMenu(Actor actor, UUID id, MenuCommand command) {
        return store.saveMenu(actor, id, command);
    }

    public Impact menuImpact(Actor actor, UUID id) {
        return store.menuImpact(actor, id);
    }

    public void deleteMenu(Actor actor, UUID id, long version, boolean revoke) {
        store.deleteMenu(actor, id, version, revoke);
    }

    public AuditPage audits(Actor actor, String action, String keyword, int page, int size) {
        return store.audits(actor, action, keyword, page, size);
    }
}
