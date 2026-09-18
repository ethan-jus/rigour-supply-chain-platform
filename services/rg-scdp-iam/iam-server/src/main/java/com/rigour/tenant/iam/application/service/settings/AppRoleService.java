package com.rigour.tenant.iam.application.service.settings;

import com.rigour.tenant.iam.application.port.out.AppRoleStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.*;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public final class AppRoleService {
    private final AppRoleStore store;

    public AppRoleService(AppRoleStore store) {
        this.store = store;
    }

    public List<Role> roles(Actor a) {
        return store.roles(a);
    }

    public List<Role> memberRoles(Actor a) {
        return store.memberRoleCatalog(a);
    }

    public List<Node> menus(Actor a) {
        return store.grantableMenus(a);
    }

    public Role save(Actor a, UUID id, RoleCommand c) {
        return store.saveRole(a, id, c);
    }

    public RoleImpact impact(Actor a, UUID id) {
        return store.impact(a, id);
    }

    public void delete(Actor a, UUID id, long v, boolean revoke) {
        store.deleteRole(a, id, v, revoke);
    }
}
