package com.rigour.tenant.iam.application.service.management;

import com.rigour.tenant.iam.application.port.out.IamRolePermissionStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.RolePermissionModels.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 角色权限管理用例入口。 */
@Service
public final class IamRolePermissionService {
    private final IamRolePermissionStore store;

    public IamRolePermissionService(IamRolePermissionStore store) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
    }

    public List<RolePermissionView> roles(Actor actor) {
        return store.roles(actor);
    }

    public List<GrantableResourceView> grantableResources(Actor actor) {
        return store.grantableResources(actor);
    }

    public RolePermissionView createRole(Actor actor, RolePermissionCommand command) {
        return store.createRole(actor, command);
    }

    public RolePermissionView updateRole(Actor actor, UUID id, RolePermissionCommand command) {
        return store.updateRole(actor, id, command);
    }
}
