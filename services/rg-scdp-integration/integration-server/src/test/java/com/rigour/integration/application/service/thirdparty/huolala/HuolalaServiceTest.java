package com.rigour.integration.application.service.thirdparty.huolala;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.HrEmployeeProjectionApi.IdentityLookup;
import com.rigour.hr.api.v1.HrEmployeeProjectionApi.IdentityMatch;
import com.rigour.hr.api.v1.HrEmployeeProjectionApi.IdentityMatchMethod;
import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.integration.application.port.out.*;
import com.rigour.integration.application.port.out.thirdparty.huolala.HuolalaDomainClient;
import com.rigour.integration.application.port.out.thirdparty.huolala.HuolalaGateway;
import com.rigour.integration.application.port.out.thirdparty.huolala.HuolalaStore;
import com.rigour.integration.application.port.out.thirdparty.oauth.ThirdPartyTokenStore.Stored;
import com.rigour.integration.application.service.thirdparty.oauth.*;
import com.rigour.integration.domain.thirdparty.oauth.OAuthToken;
import com.rigour.order.api.v1.model.LogisticsImportModels.Row;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

class HuolalaServiceTest {
    HuolalaStore store = mock(HuolalaStore.class);
    HuolalaGateway gateway = mock(HuolalaGateway.class);
    HuolalaDomainClient domains = mock(HuolalaDomainClient.class);
    ThirdPartyTokenService tokens = mock(ThirdPartyTokenService.class);
    ThirdPartyAuthorizationService authorizations = mock(ThirdPartyAuthorizationService.class);
    ObjectMapper json = new ObjectMapper();
    HuolalaService service =
            new HuolalaService(
                    store,
                    gateway,
                    domains,
                    json,
                    Runnable::run,
                    new HuolalaService.DeploymentSettings(null, null, null, null, null, true),
                    tokens,
                    authorizations);
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
                "http://localhost:26882/api/v1/integration/huolala/oauth/callback",
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
        when(tokens.current(any(), any(), any()))
                .thenReturn(
                        Optional.of(
                                new Stored(
                                        UUID.randomUUID(),
                                        tenant,
                                        "HUOLALA",
                                        connector,
                                        "app",
                                        new OAuthToken(
                                                "access",
                                                "refresh",
                                                Instant.now().plusSeconds(3600)),
                                        "AUTHORIZED",
                                        Instant.now().plusSeconds(3000),
                                        Instant.now(),
                                        null,
                                        null,
                                        0,
                                        1)));
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
        when(authorizations.create(any(), any()))
                .thenThrow(new IllegalStateException("货拉拉应用凭据未配置完整"));
        assertThatThrownBy(service::authorize).hasMessageContaining("应用凭据");
        when(tokens.current(any(), any(), any())).thenReturn(Optional.empty());
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
    void humanReadNeedsPermission() {
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
                                false),
                        tokens,
                        authorizations);
        assertThatThrownBy(() -> service.save(command)).hasMessageContaining("HTTPS");
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void manualSyncCoversNineMonthsInOneRunWithoutGapsOrOverlaps() {
        stubEmptyOrderList();
        LocalDate from = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusYears(1).withDayOfYear(1);
        LocalDate to = from.plusMonths(9);

        service.sync(new SyncCommand("ORDERS", from, to, null));

        List<Map<String, Object>> requests = orderListRequests(10);
        long nextStart = orderDayStart(from);
        for (int i = 0; i < requests.size(); i++) {
            var request = requests.get(i);
            assertThat(request).containsEntry("order_start_datetime", nextStart)
                    .containsEntry("order_status", 2)
                    .containsEntry("page_no", 1);
            long nextBoundary = orderDayStart(i == 9 ? to.plusDays(1) : from.plusMonths(i + 1));
            assertThat(request).containsEntry("order_end_datetime", nextBoundary - 1);
            nextStart = nextBoundary;
        }
        verify(store).startRun(eq(tenant), eq(actor), eq("ORDERS"), eq("MANUAL"), any());
        verify(store).progress(tenant, run, 0, 0, 0, 0, from, to, 1);
        verify(store).finishRun(eq(tenant), eq(run), eq(0L), eq(0L), eq(0L), eq(0L), isNull(), any());
    }

    @Test
    void invalidDatesAndOrderNumbersReportSpecificErrorsBeforeSubmission() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertThatThrownBy(() -> service.sync(new SyncCommand("ORDERS", today, today.minusDays(1), null)))
                .hasMessage("结束日期不能早于开始日期");
        assertThatThrownBy(() -> service.sync(new SyncCommand("ORDERS", today, today.plusDays(1), null)))
                .hasMessage("结束日期不能晚于今天");
        assertThatThrownBy(() -> service.sync(new SyncCommand("ORDERS", today.minusYears(3).minusDays(1), today, null)))
                .hasMessage("开始日期须在近三年内");
        assertThatThrownBy(() -> service.sync(new SyncCommand("ORDERS", null, null, "9999999999999999999")))
                .hasMessage("请输入完整的货拉拉数字订单号");
        verify(store, never()).startRun(any(), any(), any(), any(), any());
        verify(gateway, never()).read(any(), any(), any(), any());
    }

    @Test
    void orderNumberSyncDoesNotApplyDateFiltersOrDateValidation() {
        stubEmptyOrderList();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        service.sync(new SyncCommand("ORDERS", today, today.minusDays(1), " 3624071813921329726 "));

        assertThat(orderListRequests(1).getFirst())
                .containsEntry("order_display_id", 3624071813921329726L)
                .doesNotContainKeys("order_start_datetime", "order_end_datetime");
    }

    @Test
    void failedMonthIsReportedAndStopsRemainingMonthsInTheSameRun() {
        stubEmptyOrderList();
        LocalDate from = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusYears(1).withDayOfYear(1);
        when(gateway.read(any(), any(), eq("e-order-list"),
                argThat(p -> Objects.equals(p.get("order_start_datetime"), orderDayStart(from.plusMonths(1))))))
                .thenThrow(new IllegalStateException("平台暂不可用"));

        service.sync(new SyncCommand("ORDERS", from, from.plusMonths(3), null));

        orderListRequests(2);
        verify(store).progress(tenant, run, 0, 0, 0, 0, from, from.plusMonths(1).minusDays(1), 1);
        verify(store).finishRun(eq(tenant), eq(run), eq(0L), eq(0L), eq(0L), eq(0L),
                eq("用车日期 " + from.plusMonths(1) + " 至 " + from.plusMonths(2).minusDays(1)
                        + " 同步未完成，平台暂不可用"), any());
    }

    private static long orderDayStart(LocalDate date) {
        return date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    private List<Map<String, Object>> orderListRequests(int times) {
        org.mockito.ArgumentCaptor<Map<String, Object>> requests = org.mockito.ArgumentCaptor.captor();
        verify(gateway, times(times)).read(any(), any(), eq("e-order-list"), requests.capture());
        return requests.getAllValues();
    }

    private void stubEmptyOrderList() {
        when(tokens.valid(any(), any(), any()))
                .thenReturn(new OAuthToken("access", "refresh", Instant.now().plusSeconds(3600)));
        var list = json.createObjectNode().put("total", 0);
        list.putArray("order_arr");
        when(gateway.read(any(), any(), eq("e-order-list"), any())).thenReturn(list);
    }

    @Test
    void onlyFinalCompletedOrdersAreRequestedAndDeliveredEvenIfListIgnoresFilter() {
        var token = new OAuthToken("access", "refresh", Instant.now().plusSeconds(3600));
        when(tokens.valid(any(), any(), any())).thenReturn(token);
        var list = json.createObjectNode().put("total", 19);
        var orders = list.putArray("order_arr");
        for (int state : List.of(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18))
            orders.addObject()
                    .put("order_display_id", "3624071813921329726")
                    .put("ep_id", "12345")
                    .put("order_status", state)
                    .put("order_time", "2026-11-08 10:00:00");
        when(gateway.read(any(), any(), eq("e-order-list"), any())).thenReturn(list);
        when(gateway.read(any(), any(), eq("e-order-detail"), any()))
                .thenReturn(completedDetail(2));
        when(store.raw(any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID().toString());
        when(domains.receive(any(), any()))
                .thenReturn(
                        new com.rigour.order.api.v1.model.LogisticsImportModels.Row(
                                        "R1", 1, "UNMATCHED", null, "接口", null)
                                .withSyncOutcome("CREATED"));
        service.sync(
                new SyncCommand("ORDERS", LocalDate.now().minusDays(1), LocalDate.now(), null));
        verify(gateway)
                .read(
                        any(),
                        any(),
                        eq("e-order-list"),
                        argThat(p -> Integer.valueOf(2).equals(p.get("order_status"))));
        verify(gateway, times(1)).read(any(), any(), eq("e-order-detail"), any());
        verify(domains, times(2)).receive(any(), argThat(s -> "已完成".equals(s.order().status())));
        verify(store)
                .finishRun(eq(tenant), eq(run), eq(1L), eq(1L), eq(0L), eq(0L), isNull(), any());
    }

    @Test
    void statusChangedBetweenListAndDetailDoesNotCreateAnEarlierCompletedSnapshot() {
        stubCompletedList();
        when(gateway.read(any(), any(), eq("e-order-detail"), any()))
                .thenReturn(completedDetail(14));
        service.sync(
                new SyncCommand("ORDERS", LocalDate.now(), LocalDate.now(), "3624071813921329726"));
        verify(gateway)
                .read(
                        any(),
                        any(),
                        eq("e-order-list"),
                        argThat(
                                p ->
                                        Integer.valueOf(2).equals(p.get("order_status"))
                                                && p.containsKey("order_display_id")));
        verifyNoInteractions(domains);
        verify(store, never()).raw(any(), any(), any(), eq("ORDER_LIST"), any(), any());
    }

    @Test
    void completedListIsRetainedWhenDetailFailsAndRemainsRetryable() {
        stubCompletedList();
        when(gateway.read(any(), any(), eq("e-order-detail"), any()))
                .thenThrow(new IllegalStateException("detail timeout"));
        when(store.raw(any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID().toString());
        when(domains.receive(any(), any()))
                .thenReturn(
                        new com.rigour.order.api.v1.model.LogisticsImportModels.Row(
                                        "R1", 1, "UNMATCHED", null, "接口", null)
                                .withSyncOutcome("CREATED"));
        service.sync(new SyncCommand("ORDERS", LocalDate.now(), LocalDate.now(), null));
        verify(domains).receive(any(), argThat(s -> "已完成".equals(s.order().status())));
        verify(store, atLeastOnce()).stages(tenant, run, new StageCounts(1, 0, 1, 0));
    }

    private tools.jackson.databind.JsonNode completedDetail(int state) {
        var detail = json.createObjectNode();
        detail.putObject("order_detail_item")
                .putObject("order_item")
                .put("order_display_id", "3624071813921329726")
                .put("ep_id", "12345")
                .put("order_status", state)
                .put("order_time", "2026-11-08 10:00:00")
                .put("price_fen", 13764);
        return detail;
    }

    private void stubCompletedList() {
        when(tokens.valid(any(), any(), any()))
                .thenReturn(new OAuthToken("access", "refresh", Instant.now().plusSeconds(3600)));
        var list = json.createObjectNode().put("total", 1);
        list.putArray("order_arr")
                .add(completedDetail(2).path("order_detail_item").path("order_item"));
        when(gateway.read(any(), any(), eq("e-order-list"), any())).thenReturn(list);
    }

    @Test
    void refreshTokenIsUsedForEmployeeAndOrderRequestsAndRunsFinishPersistently() {
        var old = new OAuthToken("old", "old-refresh", Instant.now().minusSeconds(1));
        var token = new OAuthToken("new", "new-refresh", Instant.now().plusSeconds(3600));
        when(tokens.valid(tenant, "HUOLALA", connector)).thenReturn(token);
        // Connection contains an expiry calculated on each call; match by actual object instead.
        when(gateway.read(any(), eq(token), eq("e-staff-construct"), any()))
                .thenReturn(json.createObjectNode().putArray("list"));
        when(gateway.read(any(), eq(token), eq("e-order-list"), any()))
                .thenReturn(
                        json.createObjectNode()
                                .put("total", 0)
                                .set("order_arr", json.createArrayNode()));
        service.sync(new SyncCommand("ALL", LocalDate.now().minusDays(2), LocalDate.now(), null));
        verify(tokens, atLeastOnce()).valid(tenant, "HUOLALA", connector);
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
    void scheduledJobWithoutAppCredentialsLeavesDueWorkForAnotherInstance() {
        when(store.due(any())).thenReturn(List.of(tenant));
        when(gateway.ready(any())).thenReturn(false);

        service.tick();

        verify(store, never()).startRun(any(), any(), any(), any(), any());
        verify(store, never()).claim(any(), any(), any());
        verify(store, never()).finish(any(), any(), any(), any());
    }

    @Test
    void dailyScheduledJobRechecksWholeSupportedYearInsteadOfOnlyOldestQuarter() {
        when(gateway.ready(any())).thenReturn(true);
        when(gateway.appKey(any())).thenReturn("app");
        when(store.due(any())).thenReturn(List.of(tenant));
        when(store.historyDue(eq(tenant), any())).thenReturn(true);
        var token = new OAuthToken("access", "refresh", Instant.now().plusSeconds(3600));
        when(tokens.valid(any(), any(), any())).thenReturn(token);
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

    @Test
    void employeeDirectoryFailureDoesNotBlockOrderSyncAndIsReportedSeparately() {
        var token = new OAuthToken("access", "refresh", Instant.now().plusSeconds(3600));
        when(tokens.valid(any(), any(), any())).thenReturn(token);
        when(gateway.read(any(), eq(token), eq("e-staff-construct"), any()))
                .thenThrow(new IllegalStateException("directory permission unavailable"));
        var list = json.createObjectNode().put("total", 1);
        list.putArray("order_arr")
                .addObject()
                .put("order_display_id", "3624071813921329726")
                .put("ep_id", "12345")
                .put("user_id", 22)
                .put("order_status", 2)
                .put("order_time", "2026-11-08 10:00:00")
                .put("price_fen", 13764);
        when(gateway.read(any(), eq(token), eq("e-order-list"), any())).thenReturn(list);
        var detail = json.createObjectNode();
        detail.putObject("order_detail_item")
                .putObject("order_item")
                .put("order_display_id", "3624071813921329726")
                .put("ep_id", "12345")
                .put("order_status", 2)
                .put("order_time", "2026-11-08 10:00:00")
                .put("price_fen", 13764)
                .put("paid_amount", 13764)
                .put("unpaid_amount", 0)
                .put("refund_amount", 0);
        when(gateway.read(any(), eq(token), eq("e-order-detail"), any())).thenReturn(detail);
        when(store.raw(any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID().toString());
        when(domains.receive(any(), any()))
                .thenReturn(
                        new com.rigour.order.api.v1.model.LogisticsImportModels.Row(
                                        "R1", 1, "UNMATCHED", null, "接口", null)
                                .withSyncOutcome("CREATED"));
        service.sync(new SyncCommand("ALL", LocalDate.now().minusDays(1), LocalDate.now(), null));
        verify(store)
                .delivery(
                        eq(tenant),
                        eq(run),
                        any(),
                        eq("DIRECTORY"),
                        eq("员工目录暂未获取，订单继续同步；请检查员工查询权限后重试"));
        verify(store)
                .finishRun(eq(tenant), eq(run), eq(1L), eq(1L), eq(0L), eq(1L), isNull(), any());
        verify(store, atLeastOnce()).stages(tenant, run, new StageCounts(1, 1, 0, 0));
        var receipts =
                org.mockito.ArgumentCaptor.forClass(
                        com.rigour.order.api.v1.model.LogisticsImportModels.ApiSource.class);
        verify(domains, times(2)).receive(any(), receipts.capture());
        assertThat(receipts.getAllValues())
                .allSatisfy(
                        r -> {
                            assertThat(r.order().orderNo()).isEqualTo("3624071813921329726");
                            assertThat(r.order().facts().mappedEmployeeCode()).isNull();
                            assertThat(r.order().facts().sourceEmployeeName()).isNull();
                        });
        assertThat(receipts.getAllValues().get(1).order().facts().billAmount())
                .isEqualByComparingTo("137.64");
    }

    @Test
    void employeesOnlyFailureStaysFailedAndExpiredAuthorizationDoesNotReadOrders() {
        var token = new OAuthToken("access", "refresh", Instant.now().plusSeconds(3600));
        when(tokens.valid(any(), any(), any())).thenReturn(token);
        when(gateway.read(any(), any(), eq("e-staff-construct"), any()))
                .thenThrow(new IllegalStateException("directory unavailable"));
        service.sync(new SyncCommand("EMPLOYEES", LocalDate.now(), LocalDate.now(), null));
        verify(gateway, never()).read(any(), any(), eq("e-order-list"), any());
        verify(store)
                .finishRun(
                        eq(tenant),
                        eq(run),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq("directory unavailable"),
                        any());
        reset(gateway, store);
        setup();
        when(tokens.valid(any(), any(), any())).thenThrow(new IllegalStateException("平台需要重新授权"));
        service.sync(new SyncCommand("ALL", LocalDate.now(), LocalDate.now(), null));

        verify(gateway, never()).read(any(), any(), any(), any());
    }

    @Test
    void directoryEmployeesRemainUsableWhenSupplementalDetailIsUnavailable() {
        var token = new OAuthToken("access", "refresh", Instant.now().plusSeconds(3600));
        when(tokens.valid(any(), any(), any())).thenReturn(token);
        var tree = json.createObjectNode();
        var department =
                tree.putArray("list")
                        .addObject()
                        .put("code", "10")
                        .put("name", "平台运营部")
                        .put("flag", 1);
        department
                .putArray("children")
                .addObject()
                .put("code", "11")
                .put("name", "李经理")
                .put("phone_no", "13800000000")
                .put("flag", 2);
        when(gateway.read(any(), any(), eq("e-staff-construct"), any())).thenReturn(tree);
        when(gateway.read(any(), any(), eq("e-staff-detail"), any()))
                .thenThrow(new IllegalStateException("not granted"));
        when(store.raw(any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID().toString());
        service.sync(new SyncCommand("EMPLOYEES", LocalDate.now(), LocalDate.now(), null));
        verify(store)
                .raw(
                        eq(tenant),
                        eq(connector),
                        eq(run),
                        eq("EMPLOYEE"),
                        eq("11"),
                        argThat(
                                node ->
                                        node.path("staff_name").asText().equals("李经理")
                                                && node.path("depart_name").asText().equals("平台运营部")
                                                && !node.has("user_id")));
        verify(gateway, never()).read(any(), any(), eq("e-depart-detail"), any());
        verify(store)
                .finishRun(eq(tenant), eq(run), eq(1L), eq(1L), eq(0L), eq(1L), isNull(), any());
    }

    @Test
    void directorySyncAutomaticallyBindsOnlyUnambiguousNewEmployees() {
        var token = new OAuthToken("access", "refresh", Instant.now().plusSeconds(3600));
        when(tokens.valid(any(), any(), any())).thenReturn(token);
        var tree = json.createObjectNode();
        var entries = tree.putArray("list");
        var eligible = new Employee("11", "101", "职员甲", "+86 13800000000", "", "", null, null, null, "UNMATCHED", 0);
        var other = new Employee("12", "102", "职员乙", "13800000002", "", "", null, null, null, "UNMATCHED", 0);
        var duplicate = new Employee("13", "103", "职员丙", "13800000002", "", "", null, null, null, "UNMATCHED", 0);
        var unlinked = new Employee("14", "104", "职员丁", "13800000003", "", "", null, null, null, "UNMATCHED", 2);
        var directory = List.of(eligible, other, duplicate, unlinked);
        directory.forEach(staff -> entries.addObject().put("code", staff.staffId())
                .put("name", staff.name()).put("phone_no", staff.phone()).put("flag", 2));
        when(gateway.read(any(), any(), eq("e-staff-construct"), any())).thenReturn(tree);
        when(gateway.read(any(), any(), eq("e-staff-detail"), any()))
                .thenAnswer(call -> {
                    var parameters = (Map<?, ?>) call.getArgument(3);
                    return json.createObjectNode().put("ep_id", "12345")
                            .put("staff_id", parameters.get("staff_id").toString());
                });
        when(store.raw(any(), any(), any(), any(), any(), any())).thenReturn("raw");
        when(store.employees(tenant)).thenReturn(directory);
        var lookups = List.of(
                new IdentityLookup("11", "13800000000", "职员甲", true, true),
                new IdentityLookup("12", "13800000002", "职员乙", false, true),
                new IdentityLookup("13", "13800000002", "职员丙", false, true));
        when(domains.matchEmployees(any(), eq(lookups)))
                .thenReturn(List.of(new IdentityMatch("11", 91L, "EMP91", "职员甲", "武汉销售部",
                        IdentityMatchMethod.MOBILE)));
        service.sync(new SyncCommand("EMPLOYEES", null, null, null));
        verify(domains).matchEmployees(argThat(caller -> tenant.equals(caller.tenantId())),
                eq(lookups));
        verify(store).bind(eq(tenant), any(), eq(eligible),
                eq(new EmployeeCandidate(91L, "EMP91", "职员甲", null, "武汉销售部", "ACTIVE")),
                eq(0L), eq("系统匹配：手机号唯一"));
        verify(store, times(1)).bind(any(), any(), any(), any(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "SCHEDULED"})
    void orderOnlySyncMatchesEmployeesBeforeDeliveringOrders(String trigger) {
        stubEmptyOrderList();
        var orders = json.createObjectNode().put("total", 1);
        orders.putArray("order_arr").addObject()
                .put("order_display_id", "3624071813921329726")
                .put("ep_id", "12345")
                .put("user_id", "101")
                .put("order_status", 2)
                .put("order_time", LocalDate.now(ZoneId.of("Asia/Shanghai")) + " 10:00:00");
        when(gateway.read(any(), any(), eq("e-order-list"), any())).thenReturn(orders);
        when(gateway.read(any(), any(), eq("e-order-detail"), any())).thenReturn(completedDetail(2));
        when(store.raw(any(), any(), any(), any(), any(), any())).thenReturn("raw");
        when(domains.receive(any(), any()))
                .thenReturn(new Row("R1", 1, "UNMATCHED", null, "接口", null)
                        .withSyncOutcome("UNCHANGED"));
        when(gateway.ready(any())).thenReturn(true);
        when(gateway.appKey(any())).thenReturn("app");
        when(store.due(any())).thenReturn(List.of(tenant));
        var external = new Employee("11", "101", "职员甲", "13800000000", "", "",
                null, null, null, "UNMATCHED", 0);
        var linked = new Employee("11", "101", "职员甲", "13800000000", "", "",
                "EMP91", "职员甲", "武汉销售部", "LINKED", 1);
        when(store.employees(tenant)).thenReturn(List.of(external), List.of(linked));
        when(domains.matchEmployees(any(), anyList()))
                .thenReturn(List.of(new IdentityMatch("11", 91L, "EMP91", "职员甲", "武汉销售部",
                        IdentityMatchMethod.NAME)));
        for (int attempt = 0; attempt < 2; attempt++) {
            if ("SCHEDULED".equals(trigger)) service.tick();
            else service.sync(new SyncCommand("ORDERS", null, null, "3624071813921329726"));
        }
        verify(store, times(2)).startRun(eq(tenant), any(), eq("ORDERS"), eq(trigger), any());
        verify(domains, times(1)).matchEmployees(any(),
                eq(List.of(new IdentityLookup("11", "13800000000", "职员甲", true, true))));
        verify(store, times(1)).bind(eq(tenant), any(), eq(external),
                eq(new EmployeeCandidate(91L, "EMP91", "职员甲", null, "武汉销售部", "ACTIVE")),
                eq(0L), eq("系统匹配：姓名唯一"));
        verify(gateway, never()).read(any(), any(), eq("e-staff-construct"), any());
        verify(gateway, times(2)).read(any(), any(), eq("e-order-list"), any());
        verify(domains, times(4)).receive(any(), argThat(source ->
                "EMP91".equals(source.order().facts().mappedEmployeeCode())
                        && "职员甲".equals(source.order().facts().sourceEmployeeName())
                        && "武汉销售部".equals(source.order().facts().mappedDepartmentName())));
    }

    @Test
    void employeeBindingTakesDepartmentFromSelectedHrEmployeeRatherThanPlatformDepartment() {
        var external =
                new Employee(
                        "11",
                        "22",
                        "李经理",
                        "13800000000",
                        "S11",
                        "10",
                        null,
                        null,
                        null,
                        "UNMATCHED",
                        0,
                        "平台运营部");
        var internal = new EmployeeCandidate(91L, "EMP91", "李经理", "13800000000", "武汉销售部", "ACTIVE");
        when(store.employee(tenant, "11")).thenReturn(Optional.of(external));
        when(domains.employee(any(), eq(91L))).thenReturn(internal);
        service.bind(new BindEmployee("11", 91L, 0, "首次关联"));
        verify(store).bind(tenant, actor, external, internal, 0, "首次关联");
        verify(gateway, never()).read(any(), any(), any(), any());
    }

    @Test
    void employeeMappingDeliveryRetriesAndDoesNotNeedPlatformAuthorizationOrOrderResync() {
        var update =
                new com.rigour.order.api.v1.model.LogisticsImportModels.EmployeeMappingUpdate(
                        connector.toString(), "12345", "22", "EMP91", 4);
        var job = new HuolalaStore.PendingBinding(UUID.randomUUID(), tenant, 0, update);
        when(store.bindingsDue(any())).thenReturn(List.of(job));
        when(store.claimBinding(eq(job), any())).thenReturn(true);
        doThrow(new IllegalStateException("Order temporarily unavailable"))
                .doNothing()
                .when(domains)
                .employeeMapping(any(), eq(update));
        service.publishEmployeeBindings();
        verify(store).finishBinding(eq(job), eq(false), any());
        service.publishEmployeeBindings();
        verify(store).finishBinding(eq(job), eq(true), any());
        verify(domains, times(2))
                .employeeMapping(
                        argThat(
                                c ->
                                        "SERVICE".equals(c.principalScope())
                                                && tenant.equals(c.tenantId())
                                                && c.permissions()
                                                        .contains("integration:huolala:sync")),
                        eq(update));
        verify(gateway, never()).read(any(), any(), any(), any());
    }
}
