package com.rigour.tenant.iam.application.service.settings;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot;
import com.rigour.tenant.iam.application.port.out.AppAuthorizationStore;

import org.springframework.stereotype.Service;

/** 授权读取与业务数据执行分工；这里只解析当前 IAM 和 HR 身份事实。 */
@Service
public final class AppAuthorizationService {
    private final AppAuthorizationStore store;

    public AppAuthorizationService(AppAuthorizationStore store) {
        this.store = store;
    }

    public com.rigour.tenant.iam.application.model.settings.CustomerAssignmentTarget
            customerAssignmentTarget(
                    CallerIdentity caller, String employeeCode, java.util.UUID userId) {
        return store.customerAssignmentTarget(caller, employeeCode, userId);
    }

    public AppAuthorizationSnapshot candidate(CallerIdentity caller, String action) {
        return store.candidate(caller, action);
    }

    public void observeData(
            CallerIdentity caller,
            com.rigour.tenant.iam.application.model.settings.AppDataObservation request) {
        store.observeData(caller, request);
    }

    public void observe(CallerIdentity caller, String action, String legacyAction) {
        store.observe(caller, action, legacyAction);
    }

    public AppAuthorizationSnapshot authorization(CallerIdentity caller, String action) {
        return store.authorization(caller, action);
    }
}
