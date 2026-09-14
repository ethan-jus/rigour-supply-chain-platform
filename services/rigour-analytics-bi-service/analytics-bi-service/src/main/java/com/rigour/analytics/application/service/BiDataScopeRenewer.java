package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiScopeSyncCommand;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.BiScopeIdentitySource;
import com.rigour.shared.context.CallerIdentity;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 已有范围的定期源重验；只续期完全相同的授权集合，不自动增加城市、角色或更换员工。 */
@Service
public final class BiDataScopeRenewer {
    private final BiDataScopeStore store;
    private final BiScopeIdentitySource source;
    private final Clock clock;
    public BiDataScopeRenewer(BiDataScopeStore store, BiScopeIdentitySource source, Clock analyticsClock) {
        this.store = store; this.source = source; this.clock = analyticsClock;
    }

    public Optional<BiDataScopeStore.Identity> renew(CallerIdentity actor, BiDataScopeStore.Identity identity,
                                                    List<BiDataScopeStore.Grant> grants) {
        String tenant = actor.tenantId().toString();
        String user = actor.userId().toString();
        if (grants.isEmpty()) return Optional.empty();
        try {
            var command = new BiScopeSyncCommand(actor.userId(), grants.stream().map(grant -> UUID.fromString(grant.iamPolicyRef())).distinct().toList(),
                    grants.stream().map(BiDataScopeStore.Grant::regionCode).distinct().toList());
            var verified = source.verify(actor, command);
            if (!identity.employeeCode().equals(verified.employeeCode()) || !identity.ownerStaffCode().equals(verified.ownerStaffCode())
                    || verified.userSecurityVersion() != actor.userSecurityVersion()
                    || !new HashSet<>(grants).equals(new HashSet<>(verified.grants()))) throw new IllegalStateException("Scope changed");
            var now = clock.instant();
            var refreshed = new BiDataScopeStore.Identity(verified.employeeCode(), verified.ownerStaffCode(), verified.iamBindingRef(),
                    verified.hrEmployeeRef(), verified.crmEmployeeRef(), verified.userSecurityVersion(), actor.tenantPolicyVersion(),
                    now, now.plus(Duration.ofHours(8)));
            return store.renewIfUnchanged(tenant, user, identity, refreshed, user) ? Optional.of(refreshed) : Optional.empty();
        } catch (RuntimeException failure) {
            store.revoke(tenant, user, user, "AUTO_REVALIDATION_FAILED");
            return Optional.empty();
        }
    }
}
