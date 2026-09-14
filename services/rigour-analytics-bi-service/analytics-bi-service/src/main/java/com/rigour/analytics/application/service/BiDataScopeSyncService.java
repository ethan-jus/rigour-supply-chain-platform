package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiScopeSyncCommand;
import com.rigour.analytics.api.v1.model.BiScopeSyncResultView;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.BiScopeIdentitySource;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 主动校验并替换范围；同步失败不能继续使用旧授权，无任何自动授予角色/权限行为。 */
@Service
public final class BiDataScopeSyncService {
    private final BiDataScopeService scopes;
    private final BiDataScopeStore store;
    private final BiScopeIdentitySource source;
    private final Clock clock;
    public BiDataScopeSyncService(BiDataScopeService scopes, BiDataScopeStore store,
                                  BiScopeIdentitySource source, Clock analyticsClock) {
        this.scopes = scopes; this.store = store; this.source = source; this.clock = analyticsClock;
    }

    public BiScopeSyncResultView synchronize(BiScopeSyncCommand command) {
        requireManager();
        if (command == null || command.userId() == null || command.iamPolicyIds().isEmpty()
                || command.regionCodes().isEmpty() || command.iamPolicyIds().size() > 32 || command.regionCodes().size() > 100
                || command.regionCodes().stream().anyMatch(region -> !region.matches("[A-Z][A-Z0-9_]{0,63}"))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择账号、已有 IAM 范围策略和有效城市", List.of());
        }
        var actor = AuthorizationContext.requireCurrent();
        String tenant = actor.tenantId().toString();
        String user = command.userId().toString();
        // 失效与源读取不在同一回滚事务中，远端失败也必须让旧范围失效。
        store.revoke(tenant, user, actor.userId().toString(), "SOURCE_REVALIDATION");
        final BiScopeIdentitySource.VerifiedIdentity sourceIdentity;
        try { sourceIdentity = source.verify(actor, command); }
        catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "身份或范围源校验未通过，旧范围已撤销；请检查 IAM 绑定、HR 员工及 CRM 归属后重试", List.of());
        }
        if (sourceIdentity.grants().isEmpty()) throw new AuthorizationDeniedException("bi-source-policy-missing");
        var now = clock.instant();
        var expires = now.plus(Duration.ofHours(8));
        var identity = new BiDataScopeStore.Identity(sourceIdentity.employeeCode(), sourceIdentity.ownerStaffCode(),
                sourceIdentity.iamBindingRef(), sourceIdentity.hrEmployeeRef(), sourceIdentity.crmEmployeeRef(),
                sourceIdentity.userSecurityVersion(), actor.tenantPolicyVersion(), now, expires);
        store.replace(tenant, user, identity, sourceIdentity.grants(), actor.userId().toString());
        return new BiScopeSyncResultView(command.userId(), identity.employeeCode(), command.regionCodes(), now, expires, "VERIFIED");
    }

    public void revoke(UUID userId) {
        requireManager();
        var actor = AuthorizationContext.requireCurrent();
        store.revoke(actor.tenantId().toString(), userId.toString(), actor.userId().toString(), "MANUAL_REVOKE");
    }

    private void requireManager() {
        scopes.requireGlobalGovernance();
        AuthorizationContext.requirePermission("iam:data-scope:write");
    }
}
