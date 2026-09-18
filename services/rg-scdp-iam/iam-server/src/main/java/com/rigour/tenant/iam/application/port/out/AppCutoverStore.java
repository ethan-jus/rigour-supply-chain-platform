package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppCutoverModels.*;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.Context;

public interface AppCutoverStore {
    Report inspect(Actor actor);

    DataObservationPage dataObservations(Actor actor, int page, int size);

    ObservationPage observations(Actor actor, int page, int size);

    PermissionPreview preview(Actor actor, java.util.UUID userId, String action);

    Context activate(Actor actor, Command command);
}
