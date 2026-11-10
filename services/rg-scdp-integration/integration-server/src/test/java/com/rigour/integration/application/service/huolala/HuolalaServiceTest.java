package com.rigour.integration.application.service.huolala;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.integration.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

class HuolalaServiceTest {
    HuolalaStore store = mock(HuolalaStore.class);
    HuolalaGateway gateway = mock(HuolalaGateway.class);
    HuolalaDomainClient domains = mock(HuolalaDomainClient.class);
    ObjectMapper json = new ObjectMapper();
    HuolalaService service =
            new HuolalaService(
                    store,
                    gateway,
                    domains,
                    json,
                    Runnable::run,
                    new HuolalaService.DeploymentSettings(null, null, null, null, null));
    UUID tenant = UUID.randomUUID(),
            actor = UUID.randomUUID(),
            connector = UUID.randomUUID(),
            run = UUID.randomUUID();

    Connection connection() {
        return new Connection(
                connector.toString(),
                "12345",
                "示例企业",
                "env://HLL",
                false,
                "http://localhost:5100/huolala-authorized.html",
                true,
                30,
                LocalDate.now().minusYears(1),
                "AUTHORIZED",
                Instant.now().plusSeconds(3600),
                false,
                1,
                null,
                null);
    }

    static void setActor(CallerIdentity identity) {
        try {
            var method =
                    AuthorizationContext.class.getDeclaredMethod(
                            identity == null ? "clear" : "set",
                            identity == null
                                    ? new Class<?>[] {}
                                    : new Class<?>[] {CallerIdentity.class});
            method.setAccessible(true);
            if (identity == null) method.invoke(null);
            else method.invoke(null, identity);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setup() {
        setActor(
                new CallerIdentity(
                        "TENANT",
                        actor,
                        tenant,
                        actor,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(
                                "integration:huolala:read",
                                "integration:huolala:write",
                                "integration:huolala:sync")));
        when(store.connection(tenant)).thenReturn(Optional.of(connection()));
        when(store.startRun(any(), any(), any(), any(), any())).thenReturn(run);
        when(store.claim(any(), any(), any())).thenReturn(true);
        when(store.employees(tenant)).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        setActor(null);
    }

    @Test
    void missingCredentialsDoesNotPretendAuthorizationOrSyncSucceeded() {
        when(gateway.ready(any())).thenReturn(false);
        assertThat(service.connection().credentialsReady()).isFalse();
        assertThatThrownBy(service::authorize).hasMessageContaining("配置应用凭据");
        verify(store, never()).beginAuth(any(), any(), any(), any());
        var unauthorized =
                new Connection(
                        connector.toString(),
                        "12345",
                        "示例企业",
                        "env://HLL",
                        false,
                        connection().redirectUri(),
                        false,
                        30,
                        LocalDate.now(),
                        "UNAUTHORIZED",
                        null,
                        false,
                        1,
                        null,
                        null);
        when(store.connection(tenant)).thenReturn(Optional.of(unauthorized));
        assertThatThrownBy(() -> service.sync(null)).hasMessageContaining("尚未授权");
    }

    @Test
    void authorizationCannotReplayAnotherActorsStateAndHumanReadNeedsPermission() {
        when(store.consumeAuth(any(), any(), any(), any())).thenReturn(false);
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        new AuthorizationFinish("temporary-code", "wrong-state")))
                .hasMessageContaining("过期或不属于");
        verifyNoInteractions(gateway);
        setActor(null);
        assertThatThrownBy(service::connection).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void browserCannotReplaceEnterpriseOrTechnicalConfiguration() {
        when(store.save(any(), any(), any())).thenReturn(connection());
        service.save(
                new SaveConnection(
                        "999",
                        "其他企业",
                        "env://ATTACK",
                        true,
                        "https://other.example/huolala-authorized.html",
                        false,
                        15,
                        LocalDate.now(),
                        1));
        verify(store)
                .save(
                        eq(tenant),
                        eq(actor),
                        argThat(
                                c ->
                                        c.enterpriseId().equals("12345")
                                                && c.enterpriseName().equals("示例企业")
                                                && c.secretRef().equals("env://HLL")
                                                && !c.sandbox()
                                                && c.redirectUri()
                                                        .equals(connection().redirectUri())
                                                && c.intervalMinutes() == 15));
    }

    @Test
    void initialConnectionNeedsServerConfigurationAndValidCallback() {
        when(store.connection(tenant)).thenReturn(Optional.empty());
        var command =
                new SaveConnection(null, null, null, false, null, false, 15, LocalDate.now(), 0);
        assertThatThrownBy(() -> service.save(command)).hasMessageContaining("联系管理员");
        service =
                new HuolalaService(
                        store,
                        gateway,
                        domains,
                        json,
                        Runnable::run,
                        new HuolalaService.DeploymentSettings(
                                "12345",
                                "示例企业",
                                "env://HLL",
                                "http://untrusted.example/huolala-authorized.html",
                                false));
        assertThatThrownBy(() -> service.save(command)).hasMessageContaining("HTTPS");
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void largeManualWindowsAreValidatedBeforeExternalRequest() {
        assertThatThrownBy(
                        () ->
                                service.sync(
                                        new SyncCommand(
                                                "ORDERS",
                                                LocalDate.now().minusDays(94),
                                                LocalDate.now(),
                                                null)))
                .hasMessageContaining("93天");
        verifyNoInteractions(gateway);
    }

    @Test
    void refreshTokenIsUsedForEmployeeAndOrderRequestsAndRunsFinishPersistently() {
        var old = new HuolalaGateway.Token("old", "old-refresh", Instant.now().minusSeconds(1));
        var token = new HuolalaGateway.Token("new", "new-refresh", Instant.now().plusSeconds(3600));
        when(gateway.load(tenant.toString(), connector.toString())).thenReturn(old);
        when(gateway.refresh(connection(), old)).thenReturn(token);
        // Connection contains an expiry calculated on each call; match by actual object instead.
        when(gateway.refresh(any(), eq(old))).thenReturn(token);
        when(gateway.read(any(), eq(token), eq("e-staff-construct"), any()))
                .thenReturn(json.createObjectNode().putArray("list"));
        when(gateway.read(any(), eq(token), eq("e-order-list"), any()))
                .thenReturn(
                        json.createObjectNode()
                                .put("total", 0)
                                .set("order_arr", json.createArrayNode()));
        service.sync(new SyncCommand("ALL", LocalDate.now().minusDays(2), LocalDate.now(), null));
        verify(gateway).save(tenant.toString(), connector.toString(), token);
        verify(gateway)
                .read(
                        any(),
                        eq(token),
                        eq("e-order-list"),
                        argThat(
                                p ->
                                        p.containsKey("order_start_datetime")
                                                && !p.containsKey("modify_time")));
        verify(store)
                .finishRun(eq(tenant), eq(run), eq(0L), eq(0L), eq(0L), eq(0L), isNull(), any());
        verifyNoInteractions(domains);
    }

    @Test
    void dailyScheduledJobRechecksWholeSupportedYearInsteadOfOnlyOldestQuarter() {
        when(store.due(any())).thenReturn(List.of(tenant));
        when(store.historyDue(eq(tenant), any())).thenReturn(true);
        var token = new HuolalaGateway.Token("access", "refresh", Instant.now().plusSeconds(3600));
        when(gateway.load(any(), any())).thenReturn(token);
        when(gateway.read(any(), any(), eq("e-staff-construct"), any()))
                .thenReturn(json.createObjectNode().putArray("list"));
        when(gateway.read(any(), any(), eq("e-order-list"), any()))
                .thenReturn(
                        json.createObjectNode()
                                .put("total", 0)
                                .set("order_arr", json.createArrayNode()));
        service.tick();
        verify(gateway)
                .read(
                        any(),
                        any(),
                        eq("e-order-list"),
                        argThat(
                                p -> {
                                    long duration =
                                            ((Number) p.get("order_end_datetime")).longValue()
                                                    - ((Number) p.get("order_start_datetime"))
                                                            .longValue();
                                    return duration > 300 * 86400L;
                                }));
        verify(store).startRun(eq(tenant), any(), eq("ALL"), eq("SCHEDULED"), any());
    }
}
