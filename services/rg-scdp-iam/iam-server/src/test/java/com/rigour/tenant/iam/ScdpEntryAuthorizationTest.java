package com.rigour.tenant.iam;

import com.rigour.tenant.iam.api.controller.management.ScdpNavigationController;
import com.rigour.tenant.iam.application.port.out.AppSettingsStore;
import com.rigour.tenant.iam.application.port.out.IdentityAccessReader;
import com.rigour.tenant.iam.application.service.identity.*;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.Context;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScdpEntryAuthorizationTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final Actor actor = new Actor("TENANT", user, tenant);
    final AppSettingsStore settings = mock(AppSettingsStore.class);
    @AfterEach void reset() { SecurityContextHolder.clearContext(); }
    void login() {
        var jwt = Jwt.withTokenValue("validated").header("alg", "RS256")
                .claim("principalScope", "TENANT").claim("principalId", user.toString()).claim("tenantId", tenant.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
    @Test void onlyBootstrapAdministratorReceivesInitializationMenu() {
        login();
        var controller = new ScdpNavigationController(settings);
        when(settings.context(actor)).thenReturn(new Context(false, true, "PREPARING", 0, Set.of()));
        assertThat(controller.navigation()).extracting(n -> n.routePath()).containsExactly("/supply-chain/settings");
        when(settings.context(actor)).thenReturn(new Context(false, false, "PREPARING", 0, Set.of()));
        assertThatThrownBy(controller::navigation).isInstanceOf(AccessDeniedException.class);
        verify(settings, never()).navigation(any());
    }
    @Test void activeUserWithoutGrantsCannotUseStaleNavigation() {
        login();
        when(settings.context(actor)).thenReturn(new Context(true, false, "ACTIVE", 3, Set.of()));
        assertThatThrownBy(new ScdpNavigationController(settings)::navigation).isInstanceOf(AccessDeniedException.class);
        verify(settings, never()).navigation(any());
    }
    @Test void activatedRolesReplaceLegacyPermissionsAndRevocationsAreImmediate() {
        var reader = mock(IdentityAccessReader.class);
        var query = new IdentityAccessQuery("TENANT", user, tenant);
        when(reader.readCurrentUser(query)).thenReturn(new CurrentUser(user, tenant, "tenant", "TENANT", "account", "name", Set.of("TENANT_SUPER_ADMIN"), Set.of("old:permission", "*:*:*")));
        when(settings.initialized(actor)).thenReturn(true);
        when(reader.readSupplyRoles(query)).thenReturn(Set.of("SALES"));
        var service = new IdentityAccessService(reader, settings);
        when(settings.context(actor)).thenReturn(new Context(true, false, "ACTIVE", 2, Set.of("order:sales:read")));
        assertThat(service.currentUser(query).permissions()).containsExactly("order:sales:read");
        assertThat(service.currentUser(query).roles()).containsExactly("SALES");
        when(settings.context(actor)).thenReturn(new Context(true, false, "ACTIVE", 3, Set.of()));
        assertThat(service.currentUser(query).permissions()).isEmpty();
    }
}
