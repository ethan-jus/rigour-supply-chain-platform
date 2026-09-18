package com.rigour.tenant.iam.api.controller.internal;

import com.rigour.shared.context.AuthorizationContext;
import com.rigour.tenant.iam.api.v1.IamSupplyAuthorizationApi;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.application.service.settings.AppAuthorizationService;

import org.springframework.web.bind.annotation.RestController;

/** HMAC 身份由 RequestContextFilter 校验，服务层再核对当前登录会话及应用资格。 */
@RestController
public final class InternalSupplyAuthorizationController implements IamSupplyAuthorizationApi {
    private final AppAuthorizationService service;

    public InternalSupplyAuthorizationController(AppAuthorizationService service) {
        this.service = service;
    }

    @Override
    public com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView customerAssignmentTarget(
            String employeeCode, java.util.UUID userId) {
        var target =
                service.customerAssignmentTarget(
                        AuthorizationContext.requireCurrent(), employeeCode, userId);
        return new com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView(
                target.tenantId(),
                target.userId(),
                target.employeeCode(),
                target.employeeName(),
                target.memberStatus(),
                target.usable(),
                target.unavailableReason(),
                target.authorizationVersion(),
                limit(target.regionLimit()));
    }

    @Override
    public SupplyAuthorizationView authorization(String action) {
        var p = service.authorization(AuthorizationContext.requireCurrent(), action);
        return view(p);
    }

    private static SupplyAuthorizationView view(
            com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot p) {
        return new SupplyAuthorizationView(
                p.mode(),
                p.tenantId(),
                p.userId(),
                p.employeeCode(),
                p.applicationVersion(),
                p.memberVersion(),
                p.employeeRevision(),
                p.organizationVersion(),
                p.permissions(),
                p.action(),
                p.functionAllowed(),
                p.clauses().stream()
                        .map(
                                c ->
                                        new SupplyAuthorizationView.Clause(
                                                c.roleId(),
                                                c.objectType(),
                                                c.scopeMode(),
                                                limit(c.departments()),
                                                limit(c.regions()),
                                                limit(c.warehouses()),
                                                c.includeDescendants()))
                        .toList(),
                limit(p.regionLimit()),
                limit(p.warehouseLimit()));
    }

    @Override
    public SupplyAuthorizationView candidate(String action) {
        return view(service.candidate(AuthorizationContext.requireCurrent(), action));
    }

    @Override
    public void observeData(com.rigour.tenant.iam.api.v1.model.SupplyDataObservation r) {
        service.observeData(
                AuthorizationContext.requireCurrent(),
                new com.rigour.tenant.iam.application.model.settings.AppDataObservation(
                        r.action(),
                        r.domain(),
                        r.recordKey(),
                        r.applicationVersion(),
                        r.memberVersion(),
                        r.employeeRevision(),
                        r.organizationVersion(),
                        r.legacyAllowed(),
                        r.proposedAllowed()));
    }

    @Override
    public void observe(
            com.rigour.tenant.iam.api.v1.IamSupplyAuthorizationApi.Observation request) {
        service.observe(
                AuthorizationContext.requireCurrent(), request.action(), request.legacyAction());
    }

    private static SupplyAuthorizationView.Limit limit(
            com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot.Limit v) {
        return new SupplyAuthorizationView.Limit(v.mode(), v.references());
    }
}
