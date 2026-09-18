package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.*;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;

import java.util.List;
import java.util.UUID;

public interface AppRoleStore {
    List<Role> roles(Actor actor);

    List<Role> memberRoleCatalog(Actor actor);

    List<Node> grantableMenus(Actor actor);

    Role saveRole(Actor actor, UUID id, RoleCommand command);

    RoleImpact impact(Actor actor, UUID id);

    void deleteRole(Actor actor, UUID id, long version, boolean revokeMembers);
}
