package com.rigour.tenant.iam.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot;

public interface AppAuthorizationStore {
    com.rigour.tenant.iam.application.model.settings.CustomerAssignmentTarget
            customerAssignmentTarget(
                    CallerIdentity caller, String employeeCode, java.util.UUID userId);

    AppAuthorizationSnapshot authorization(CallerIdentity caller, String action);
}
