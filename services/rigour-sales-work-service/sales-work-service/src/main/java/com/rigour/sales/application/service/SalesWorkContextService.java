package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.FieldPolicyView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.SalesContextView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetPageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetView;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.FieldPolicy;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.IdentityProjection;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.SalesProfile;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 阶段 1 销售上下文和客户目标查询；不创建跨域主数据。 */
@Service
public final class SalesWorkContextService {

    private final SalesWorkQueryRepository repository;
    private final Clock clock;

    public SalesWorkContextService(SalesWorkQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public SalesContextView context() {
        CallerIdentity caller = requireTenantCaller();
        AuthorizationContext.requirePermission("sales:context:read");
        Instant now = clock.instant();
        SalesIdentity identity = resolveIdentity(caller, now);
        FieldPolicy policy = resolvePolicy(caller.tenantId(), identity.profile, now);
        return new SalesContextView(caller.userId(), identity.projection.employeeId(), identity.profile.id(),
                identity.profile.salesNo(), identity.profile.cityOrgId(), identity.profile.status(),
                Set.copyOf(caller.permissions()), policyView(policy));
    }

    public VisitTargetPageView visitTargets(String query, int page, int pageSize) {
        CallerIdentity caller = requireTenantCaller();
        AuthorizationContext.requirePermission("sales:visit-target:read");
        validatePage(page, pageSize);
        SalesIdentity identity = resolveIdentity(caller, clock.instant());
        int offset = Math.multiplyExact(page - 1, pageSize);
        long total = repository.countAssignedStoreTargets(caller.tenantId(), identity.profile.id(), query);
        var targets = repository.findAssignedStoreTargets(caller.tenantId(), identity.profile.id(), query,
                pageSize, offset).stream().map(SalesWorkContextService::targetView).toList();
        return new VisitTargetPageView(targets, page, pageSize, total);
    }

    SalesIdentity resolveIdentity(CallerIdentity caller, Instant now) {
        IdentityProjection projection = repository.findIdentityProjection(caller.tenantId(), caller.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_IDENTITY_UNBOUND));
        SalesProfile profile = repository.findActiveSalesProfile(caller.tenantId(), projection.employeeId(), now)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_PROFILE_INACTIVE));
        return new SalesIdentity(projection, profile);
    }

    FieldPolicy resolvePolicy(UUID tenantId, SalesProfile profile, Instant now) {
        return repository.findActiveFieldPolicy(tenantId, profile.id(), profile.cityOrgId(), now)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_POLICY_NOT_FOUND));
    }

    FieldPolicy resolvePolicyVersion(UUID tenantId, UUID fieldPolicyVersionId) {
        return repository.findFieldPolicy(tenantId, fieldPolicyVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_POLICY_NOT_FOUND));
    }

    private static FieldPolicyView policyView(FieldPolicy policy) {
        return new FieldPolicyView(policy.id(), policy.policyCode(), policy.policyName(), policy.versionNo(),
                policy.publishStatus(), policy.timezoneId(), policy.businessDayCutoff(),
                policy.checkInWindowStart(), policy.checkInWindowEnd(), policy.checkOutWindowStart(),
                policy.checkOutWindowEnd(), policy.standardWorkMinutes(), policy.minimumWorkMinutes(),
                policy.requireCheckOut(), policy.allowAdjustment(), policy.adjustmentDeadlineHours(),
                policy.locationEnabled(), policy.locationIntervalMinutes(),
                policy.minimumLocationAccuracyMeters(), policy.offlineUploadDeadlineMinutes());
    }

    private static VisitTargetView targetView(SalesWorkQueryRepository.VisitTarget target) {
        return new VisitTargetView(target.projectionId(), target.targetType(), target.customerId(), target.storeId(),
                target.customerName(), target.storeName(), target.storeAddress(), target.longitude(),
                target.latitude(), target.storeStatus(), target.sourceVersion(), target.sourceUpdatedAt());
    }

    static CallerIdentity requireTenantCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(caller.principalScope()) || caller.tenantId() == null || caller.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前调用人不是租户销售用户", java.util.List.of());
        }
        return caller;
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效", java.util.List.of());
        }
    }

    record SalesIdentity(IdentityProjection projection, SalesProfile profile) {
    }
}
