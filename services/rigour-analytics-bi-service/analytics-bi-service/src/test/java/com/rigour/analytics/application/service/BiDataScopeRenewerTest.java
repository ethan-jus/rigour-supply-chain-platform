package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.BiDataScopeStore.*;
import com.rigour.analytics.application.port.out.BiScopeIdentitySource;
import com.rigour.analytics.application.port.out.BiScopeIdentitySource.VerifiedIdentity;
import com.rigour.shared.context.CallerIdentity;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 续期只能复验原有范围，撤销、改人或扩大权限均不能被旧请求覆盖。 */
class BiDataScopeRenewerTest {
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID(), POLICY = UUID.randomUUID();
    private final BiDataScopeStore store = mock(BiDataScopeStore.class);
    private final BiScopeIdentitySource source = mock(BiScopeIdentitySource.class);
    private final BiDataScopeRenewer renewer = new BiDataScopeRenewer(store, source, Clock.fixed(NOW, ZoneOffset.UTC));
    private final CallerIdentity actor = new CallerIdentity("TENANT", USER, TENANT, USER, null, UUID.randomUUID(), 1, 1, 1, Set.of("assigned-role"), Set.of("analytics:dashboard:read"));
    private final Identity old = new Identity("E1", "E1", "iam:b", "hr:e", "crm:v1:ownerEmployeeCode-contract", 1, 1, NOW.minusSeconds(901), NOW.plusSeconds(1000));
    private final List<Grant> grants = List.of(new Grant("assigned-role", "SELF", "BJ", POLICY.toString()));

    @Test void automaticallyRechecksOnlyExistingPolicyAndCityThenRenewsWithCas() {
        when(source.verify(any(), any())).thenReturn(verified("E1", grants));
        when(store.renewIfUnchanged(any(), any(), eq(old), any(), any())).thenReturn(true);
        var refreshed = renewer.renew(actor, old, grants).orElseThrow();
        assertThat(refreshed.verifiedAt()).isEqualTo(NOW);
        assertThat(refreshed.expiresAt()).isEqualTo(NOW.plusSeconds(28800));
        verify(source).verify(eq(actor), argThat(command -> command.userId().equals(USER)
                && command.iamPolicyIds().equals(List.of(POLICY)) && command.regionCodes().equals(List.of("BJ"))));
        verify(store, never()).replace(any(), any(), any(), any(), any());
    }
    @Test void concurrentManualRevocationIsNeverReinserted() {
        when(source.verify(any(), any())).thenReturn(verified("E1", grants));
        when(store.renewIfUnchanged(any(), any(), any(), any(), any())).thenReturn(false);
        assertThat(renewer.renew(actor, old, grants)).isEmpty();
        verify(store, never()).replace(any(), any(), any(), any(), any());
    }
    @Test void sourceFailureInvalidatesInsteadOfServingCachedGrant() {
        when(source.verify(any(), any())).thenThrow(new IllegalStateException("source unavailable"));
        assertThat(renewer.renew(actor, old, grants)).isEmpty();
        verify(store).revoke(TENANT.toString(), USER.toString(), USER.toString(), "AUTO_REVALIDATION_FAILED");
        verify(store, never()).renewIfUnchanged(any(), any(), any(), any(), any());
    }
    @Test void sourceCannotChangeEmployeeOrEnlargeExistingCitiesDuringRenewal() {
        when(source.verify(any(), any())).thenReturn(verified("E2", grants));
        assertThat(renewer.renew(actor, old, grants)).isEmpty();
        var expanded = List.of(grants.getFirst(), new Grant("assigned-role", "SELF", "SH", POLICY.toString()));
        when(source.verify(any(), any())).thenReturn(verified("E1", expanded));
        assertThat(renewer.renew(actor, old, grants)).isEmpty();
        verify(store, never()).renewIfUnchanged(any(), any(), any(), any(), any());
    }
    @Test void noExistingGrantIsNotAutomaticallyGranted() {
        assertThat(renewer.renew(actor, old, List.of())).isEmpty();
        verifyNoInteractions(store, source);
    }
    private static VerifiedIdentity verified(String employee, List<Grant> grants) {
        return new VerifiedIdentity(employee, employee, "iam:b", "hr:e", "crm:v1:ownerEmployeeCode-contract", 1, grants);
    }
}
