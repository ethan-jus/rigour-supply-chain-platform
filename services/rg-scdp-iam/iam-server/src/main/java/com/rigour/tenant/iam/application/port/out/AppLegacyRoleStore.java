package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.Role;
import com.rigour.tenant.iam.application.service.settings.AppLegacyRoleModels.*;

import java.util.*;

public interface AppLegacyRoleStore {
    List<Source> sources(Actor actor);

    Role importRole(Actor actor, UUID id, Command command);
}
