package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.TargetSettingsModels.*;
import com.rigour.hr.application.port.out.TargetSettingsStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class TargetSettingsServiceTest {
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    private final TargetSettingsStore store = mock(TargetSettingsStore.class);
    private final TargetSettingsService service =
            new TargetSettingsService(
                    store, Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC));
    private final Subject city = new Subject("CITY", "BJ", "北京", "BJ", "北京", null, null, false);
    private final Subject sales =
            new Subject("SALES_OWNER", "E1", "销售", "BJ", "北京", "北京", "ACTIVE", false);

    @BeforeEach
    void setup() {
        authorize(Set.of(TargetSettingsService.READ, TargetSettingsService.WRITE));
        when(store.subjects(tenant.toString())).thenReturn(List.of(city, sales));
        when(store.permittedSubjects(anyString(), anyString()))
                .thenReturn(Set.of("CITY:BJ", "SALES_OWNER:E1", "SALES_OWNER:NEW"));
        when(store.targets(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    void authorize(Set<String> permissions) {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of(),
                        permissions));
    }

    Change change(String type, String code, String metric, String value) {
        return new Change(type, code, metric, value == null ? null : new BigDecimal(value), 0);
    }

    @Test
    void directSalesMembersDoNotGetNewTargetsAndPreviouslySavedHistoryRemainsReadable() {
        var direct = new Subject("SALES_OWNER", "ROOT", "直属人员", "SALES", "销售部", "销售部", "ACTIVE", false);
        var prior = new Subject("SALES_OWNER", "ROOT", "直属人员", null, null, "销售部", null, false);
        when(store.subjects(tenant.toString())).thenReturn(List.of(city, sales, direct));
        when(store.permittedSubjects(anyString(), anyString()))
                .thenReturn(Set.of("CITY:BJ", "SALES_OWNER:E1", "SALES_OWNER:ROOT"));

        for (String month : List.of("2026-10", "2026-11")) {
            var live = service.settings(month);
            assertThat(live.subjects()).extracting(Subject::code).containsExactly("BJ", "E1");
            assertThat(live.targets()).noneMatch(t -> t.code().equals("ROOT"));
            assertThatThrownBy(() -> service.save(new Batch(month,
                    List.of(change("SALES_OWNER", "ROOT", "SALES_AMOUNT", "1")), "调整原因")))
                    .hasMessageContaining("直属人员");
        }
        verify(store, never()).save(anyString(), anyString(), anyString(), any(), any(), anyString(), any());

        var period = YearMonth.of(2026, 9);
        var initial = new Target("2026-09", "SALES_OWNER", "ROOT", "直属人员",
                "SALES_AMOUNT", new BigDecimal("40000"), 0);
        when(store.archive(tenant.toString(), period)).thenReturn(Optional.of(
                new Settings("2026-09", List.of(prior), List.of(initial), true)));
        assertThat(service.settings("2026-09").subjects()).isEmpty();

        var saved = new Target("2026-09", "SALES_OWNER", "ROOT", "直属人员",
                "SALES_AMOUNT", BigDecimal.ZERO, 2);
        when(store.archive(tenant.toString(), period)).thenReturn(Optional.of(
                new Settings("2026-09", List.of(prior), List.of(saved), true)));
        assertThat(service.settings("2026-09").targets()).containsExactly(saved);

        serviceCaller();
        assertThat(service.values("2026-10", "2026-10")).anyMatch(t -> t.code().equals("ROOT"));
    }

    @Test
    void newMetricsStartAtZeroWithoutReinterpretingSavedCustomerMetrics() {
        when(store.targets(any(), any(), any()))
                .thenReturn(
                        List.of(
                                new Target(
                                        "2026-10",
                                        "SALES_OWNER",
                                        "E1",
                                        "销售",
                                        "NEW_CUSTOMER",
                                        new BigDecimal("200"),
                                        1),
                                new Target(
                                        "2026-10",
                                        "SALES_OWNER",
                                        "E1",
                                        "销售",
                                        "REPEAT_CUSTOMER",
                                        new BigDecimal("100"),
                                        1)));
        var targets = service.settings("2026-10").targets();
        assertThat(targets).hasSize(12);
        assertThat(targets)
                .filteredOn(
                        t ->
                                Set.of(
                                                "CONTACTED_STORE_COUNT",
                                                "ORDERED_STORE_COUNT",
                                                "YANGZHANGGUI_SALES_AMOUNT")
                                        .contains(t.metric()))
                .hasSize(6)
                .allSatisfy(
                        t -> {
                            assertThat(t.value()).isZero();
                            assertThat(t.revision()).isZero();
                        });
        assertThat(targets)
                .noneMatch(t -> Set.of("NEW_CUSTOMER", "REPEAT_CUSTOMER").contains(t.metric()));
    }

    @Test
    void readDoesNotImplyWriteAndScopedSubjectsHideOverrides() {
        authorize(Set.of(TargetSettingsService.READ));
        when(store.permittedSubjects(tenant.toString(), TargetSettingsService.READ))
                .thenReturn(Set.of("SALES_OWNER:E1"));
        var result = service.settings("2026-10");
        assertThat(result.subjects())
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.code()).isEqualTo("E1");
                            assertThat(s.writable()).isFalse();
                        });
        assertThatThrownBy(
                        () ->
                                service.save(
                                        new Batch(
                                                "2026-10",
                                                List.of(change("CITY", "BJ", "SALES_AMOUNT", "1")),
                                                "原因")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).save(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void verifiesEntireBatchBeforeFirstWrite() {
        when(store.permittedSubjects(tenant.toString(), TargetSettingsService.WRITE))
                .thenReturn(Set.of("CITY:BJ"));
        assertThatThrownBy(
                        () ->
                                service.save(
                                        new Batch(
                                                "2026-10",
                                                List.of(
                                                        change("CITY", "BJ", "SALES_AMOUNT", "1"),
                                                        change(
                                                                "SALES_OWNER",
                                                                "E1",
                                                                "RECEIPT_AMOUNT",
                                                                "2")),
                                                "原因")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).save(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptsNewSalespersonWithoutOrdersAndExplicitZero() {
        service.save(
                new Batch(
                        "2026-10",
                        List.of(
                                change("SALES_OWNER", "E1", "SALES_AMOUNT", "0"),
                                change("SALES_OWNER", "E1", "RECEIPT_AMOUNT", "123.45")),
                        "试用期目标"));
        verify(store, times(2))
                .save(
                        eq(tenant.toString()),
                        eq(user.toString()),
                        eq("2026-10"),
                        eq(sales),
                        any(),
                        eq("试用期目标"),
                        any());
    }

    @Test
    void rejectsFractionsNegativeMissingReasonAndDuplicateKeys() {
        for (var c :
                List.of(
                        change("CITY", "BJ", "CONTACTED_STORE_COUNT", "1.5"),
                        change("CITY", "BJ", "SALES_AMOUNT", "-1"),
                        change("CITY", "BJ", "SALES_AMOUNT", "1.001"),
                        change("CITY", "BJ", "SALES_AMOUNT", null)))
            assertThatThrownBy(() -> service.save(new Batch("2026-10", List.of(c), "原因")))
                    .isInstanceOf(BusinessException.class);
        var c = change("CITY", "BJ", "SALES_AMOUNT", "10");
        assertThatThrownBy(() -> service.save(new Batch("2026-10", List.of(c), "")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.save(new Batch("2026-10", List.of(c, c), "原因")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void historyChecksReadScopeAndUnknownObject() {
        when(store.permittedSubjects(tenant.toString(), TargetSettingsService.READ))
                .thenReturn(Set.of("SALES_OWNER:E1"));
        assertThatThrownBy(() -> service.history("2026-10", "CITY", "BJ"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.history("2026-10", "CITY", "UNKNOWN"))
                .isInstanceOf(BusinessException.class);
        verify(store, never()).history(any(), any(), any(), any());
    }

    @Test
    void newRosterMembersReceiveInitialValuesWithoutDatabaseWrites() {
        var settings = service.settings("2026-10");
        assertThat(settings.targets()).hasSize(12);
        assertThat(settings.targets())
                .filteredOn(
                        t -> t.dimensionType().equals("CITY") && t.metric().equals("SALES_AMOUNT"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.value()).isEqualByComparingTo("100000");
                            assertThat(t.revision()).isZero();
                        });
        assertThat(settings.targets())
                .filteredOn(
                        t ->
                                t.dimensionType().equals("SALES_OWNER")
                                        && t.metric().equals("RECEIPT_AMOUNT"))
                .singleElement()
                .satisfies(t -> assertThat(t.value()).isEqualByComparingTo("20000"));
        when(store.subjects(tenant.toString()))
                .thenReturn(
                        List.of(
                                city,
                                sales,
                                new Subject(
                                        "SALES_OWNER",
                                        "NEW",
                                        "新入职",
                                        "BJ",
                                        "北京",
                                        "北京",
                                        "ACTIVE",
                                        false)));
        assertThat(service.settings("2026-10").targets()).hasSize(18);
        verify(store, never()).save(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void savedZeroWinsAndTenantUsersCannotCallInternalReadContract() {
        when(store.targets(any(), any(), any()))
                .thenReturn(
                        List.of(
                                new Target(
                                        "2026-10",
                                        "SALES_OWNER",
                                        "E1",
                                        "销售",
                                        "SALES_AMOUNT",
                                        BigDecimal.ZERO,
                                        2)));
        assertThat(service.settings("2026-10").targets())
                .filteredOn(t -> t.code().equals("E1") && t.metric().equals("SALES_AMOUNT"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.value()).isZero();
                            assertThat(t.revision()).isEqualTo(2);
                        });
        assertThatThrownBy(() -> service.values("2026-10", "2026-10"))
                .isInstanceOf(AuthorizationDeniedException.class);
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "SERVICE",
                        user,
                        tenant,
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:targets:service-read")));
        assertThat(service.values("2026-10", "2026-11")).hasSize(20);
        assertThatThrownBy(() -> service.values("2026-11", "2026-10"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.values("2026-01", "2028-01"))
                .isInstanceOf(BusinessException.class);
    }
    void serviceCaller() {
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",user,tenant,null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("hr:targets:service-read")));
    }
    @Test void businessTargetsUseOnlyTheSelectedEmployeesMonthlyBusinessMetrics() {
        assertThatThrownBy(() -> service.businessValues("E1","2026-09","2026-11")).isInstanceOf(AuthorizationDeniedException.class);
        serviceCaller();
        when(store.targets(tenant.toString(),"2026-09","2026-11")).thenReturn(List.of(
                new Target("2026-09","SALES_OWNER","E1","销售","SALES_AMOUNT",new BigDecimal("50000"),1),
                new Target("2026-10","SALES_OWNER","E1","销售","RECEIPT_AMOUNT",BigDecimal.ZERO,2),
                new Target("2026-11","SALES_OWNER","E1","销售","FULLY_PAID_RECEIPT_BASIS",new BigDecimal("7000"),1),
                new Target("2026-11","SALES_OWNER","OTHER","其他","SALES_AMOUNT",new BigDecimal("99999"),1),
                new Target("2026-11","CITY","BJ","北京","RECEIPT_AMOUNT",new BigDecimal("99999"),1)));
        var targets = service.businessValues("E1","2026-09","2026-11");
        assertThat(targets.salesTargetAmount()).isEqualByComparingTo("130000");
        assertThat(targets.receiptTargetAmount()).isEqualByComparingTo("40000");
        verify(store).targets(tenant.toString(),"2026-09","2026-11");
        assertThatThrownBy(() -> service.businessValues("MISSING","2026-11","2026-11")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.businessValues("E1","2026-11","2026-10")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.businessValues("E1","2026-01","2027-01")).isInstanceOf(BusinessException.class);
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",user,tenant,null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of()));
        assertThatThrownBy(() -> service.businessValues("E1","2026-11","2026-11")).isInstanceOf(AuthorizationDeniedException.class);
    }
    @Test void businessTargetsMatchSettingsAndIgnorePayrollStageRules() {
        var payroll = mock(com.rigour.hr.application.port.out.HrPayrollStore.class);
        var schemes = mock(com.rigour.hr.application.port.out.HrPayrollSchemeStore.class);
        var components = mock(com.rigour.hr.application.port.out.HrPayrollComponentStore.class);
        service.setPayrollRules(components, schemes, payroll);
        serviceCaller();
        when(store.targets(any(),any(),any())).thenReturn(List.of(new Target("2026-11","SALES_OWNER","E1","销售","FULLY_PAID_RECEIPT_BASIS",new BigDecimal("7000"),1)));
        var defaults = service.businessValues("E1","2026-11","2026-11");
        assertThat(defaults.salesTargetAmount()).isEqualByComparingTo("40000");
        assertThat(defaults.receiptTargetAmount()).isEqualByComparingTo("20000");
        var year = service.businessValues("E1","2026-01","2026-11");
        assertThat(year.salesTargetAmount()).isEqualByComparingTo("440000");
        assertThat(year.receiptTargetAmount()).isEqualByComparingTo("220000");
        verifyNoInteractions(payroll, schemes, components);
        when(store.targets(any(),any(),any())).thenReturn(List.of(
                new Target("2026-11","SALES_OWNER","E1","销售","SALES_AMOUNT",BigDecimal.ZERO,1),
                new Target("2026-11","SALES_OWNER","E1","销售","RECEIPT_AMOUNT",new BigDecimal("25000"),1)));
        var changed = service.businessValues("E1","2026-11","2026-11");
        assertThat(changed.salesTargetAmount()).isZero();
        assertThat(changed.receiptTargetAmount()).isEqualByComparingTo("25000");
        authorize(Set.of(TargetSettingsService.READ));
        var settings = new TargetSettingsService(store, Clock.systemUTC()).settings("2026-11").targets();
        assertThat(settings).anySatisfy(t -> { assertThat(t.code()).isEqualTo("E1"); assertThat(t.metric()).isEqualTo("RECEIPT_AMOUNT"); assertThat(t.value()).isEqualTo(changed.receiptTargetAmount()); });
    }

}
