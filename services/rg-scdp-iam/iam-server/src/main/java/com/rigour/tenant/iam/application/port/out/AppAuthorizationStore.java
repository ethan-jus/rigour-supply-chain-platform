package com.rigour.tenant.iam.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot;

public interface AppAuthorizationStore {
    com.rigour.tenant.iam.application.model.settings.CustomerAssignmentTarget
            customerAssignmentTarget(
                    CallerIdentity caller, String employeeCode, java.util.UUID userId);

    AppAuthorizationSnapshot authorization(CallerIdentity caller, String action);

    AppAuthorizationSnapshot candidate(CallerIdentity caller, String action);

    void observeData(
            CallerIdentity caller,
            com.rigour.tenant.iam.application.model.settings.AppDataObservation observation);

    void observe(CallerIdentity caller, String action, String legacyAction);
}
