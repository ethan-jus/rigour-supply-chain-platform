package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.HrFieldworkOrganization;
import com.rigour.merchant.api.v1.model.FieldworkSources;
import com.rigour.sales.api.v1.model.FieldworkReport.*;
import com.rigour.sales.application.port.out.*;
import com.rigour.sales.application.port.out.FieldworkFacts.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

class FieldworkReportServiceTest {
    final LocalDate day = LocalDate.of(2026, 11, 20);
    final Instant now = Instant.parse("2026-11-20T09:40:00Z");
    final UUID tenant = UUID.randomUUID(),
            a = UUID.randomUUID(),
            b = UUID.randomUUID(),
            plan = UUID.randomUUID();
    final FieldworkFacts facts = mock(FieldworkFacts.class);
    final FieldworkDirectory directory = mock(FieldworkDirectory.class);
    final SalesManagementScope scope = mock(SalesManagementScope.class);
    final SalesManagementDirectory.Access access =
            new SalesManagementDirectory.Access(false, Set.of("A", "B"));
    final FieldworkReportService service =
            new FieldworkReportService(facts, directory, scope, Clock.fixed(now, ZoneOffset.UTC));

    void bind(Set<String> actions) throws Exception {
        var m = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        m.setAccessible(true);
        var user = UUID.randomUUID();
        m.invoke(
                null,
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
                        actions));
    }

    @AfterEach
    void clear() throws Exception {
        var m = AuthorizationContext.class.getDeclaredMethod("clear");
        m.setAccessible(true);
        m.invoke(null);
    }

    Instant at(String t) {
        return LocalDateTime.parse("2026-11-20T" + t)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
    }

    StaffPunch punch(UUID p, String type, String t) {
        return new StaffPunch(p, new Punch(type, at(t), at(t)));
    }

    VisitFact visit(
            UUID owner,
            String code,
            int seq,
            String store,
            UUID linked,
            int files,
            int verified,
            long ms) {
        return new VisitFact(
                new Visit(
                        UUID.randomUUID(),
                        owner,
                        day,
                        code,
                        code,
                        store,
                        null,
                        "CHECKED_OUT",
                        at("10:00"),
                        at("10:12"),
                        "完成",
                        null,
                        null,
                        linked,
                        seq,
                        "VALID",
                        null,
                        "v7",
                        10,
                        1,
                        files,
                        verified,
                        ms,
                        null,
                        null,
                        null,
                        at("10:13"), true),
                store,
                null);
    }

    @BeforeEach
    void seed() throws Exception {
        bind(Set.of("sales:dashboard:read"));
        when(scope.resolve(any(), eq("sales:dashboard:read"))).thenReturn(access);
        when(facts.roster(tenant, day, access))
                .thenReturn(
                        List.of(
                                new Profile(
                                        a,
                                        "A",
                                        "销售甲",
                                        "101",
                                        UUID.randomUUID(),
                                        "FINISHED",
                                        now.plusSeconds(3600),
                                        5800,
                                        1,
                                        false),
                                new Profile(
                                        b,
                                        "B",
                                        "销售乙",
                                        "102",
                                        UUID.randomUUID(),
                                        "ACTIVE",
                                        now.minusSeconds(1),
                                        null,
                                        null,
                                        true)));
        when(directory.organization(eq(tenant), any(), anyList()))
                .thenReturn(
                        List.of(
                                new HrFieldworkOrganization(
                                        "A", "甲", "杭州", 1L, "北组", Map.of(1L, "北组", 9L, "总部")),
                                new HrFieldworkOrganization(
                                        "B", "乙", "杭州", 2L, "南组", Map.of(2L, "南组", 9L, "总部"))));
        when(directory.salesDepartments(tenant)).thenReturn(Map.of("1", "北组", "2", "南组", "8", "其他区域"));
        when(facts.punches(tenant, day, access))
                .thenReturn(
                        List.of(
                                punch(a, "CHECK_IN", "08:45"),
                                punch(a, "CHECK_OUT", "12:10"),
                                punch(a, "CHECK_IN", "13:05"),
                                punch(a, "CHECK_OUT", "17:25"),
                                punch(b, "CHECK_IN", "09:00")));
        when(facts.plans(tenant, day, access))
                .thenReturn(List.of(new StaffPlan(a, plan), new StaffPlan(a, UUID.randomUUID())));
        when(facts.visits(tenant, day, day, access, null))
                .thenReturn(
                        List.of(
                                visit(a, "A", 1, "same", plan, 2, 1, 123000),
                                visit(a, "A", 2, "same", plan, 1, 1, 30000),
                                visit(b, "B", 1, "same", null, 0, 0, 0)));
        var contact = new FieldworkSources.Contact("shared", at("10:10"));
        when(directory.sources(eq(tenant), any()))
                .thenReturn(
                        new FieldworkSources.Result(
                                Map.of(
                                        "101",
                                                new FieldworkSources.Contacts(
                                                        "READY", now, List.of(contact)),
                                        "102",
                                                new FieldworkSources.Contacts(
                                                        "READY", now, List.of(contact))),
                                Map.of()));
    }

    @Test
    void fullScopeDeduplicatesAcrossPagesAndRetainsPartialCoverage() {
        var filters =
                new FieldworkReportService.Filters(
                        "杭州", "9", true, null, null, false, "visits", 1, 1);
        var report = service.report(day, filters);
        assertThat(report.total()).isEqualTo(2);
        assertThat(report.items()).hasSize(1);
        var s = report.summary();
        assertThat(
                        List.of(
                                s.stores(),
                                s.visits(),
                                s.punches(),
                                s.planned(),
                                s.completedPlans(),
                                s.files(),
                                s.verifiedFiles(),
                                s.contacts(),
                                s.withContacts(),
                                s.withDistance(),
                                s.missingCheckout()))
                .containsExactly(1, 3, 5, 2, 1, 3, 2, 1, 2, 1, 1);
        assertThat(s.verifiedMillis()).isEqualTo(153000);
        assertThat(s.workMinutes()).isEqualTo(465);
        assertThat(report.items().getFirst().periods()).hasSize(2);
        assertThat(
                        service.report(
                                        day,
                                        new FieldworkReportService.Filters(
                                                "杭州", "1", false, null, null, false, "risk", 1, 20))
                                .total())
                .isOne();
        assertThat(
                        service.ledger(
                                        day, day, null, "杭州", "1", false, "same", "VALID", null,
                                        null, null, 1, 1)
                                .total())
                .isEqualTo(2);
    }

    @Test
    void ledgerUsesTheOriginalVisitAndScopeAcrossPlanAndEmployeeFilters() {
        var all =
                service.ledger(day, day, null, null, null, false, "", "", null, null, null, 1, 25);
        var validPage =
                service.ledger(day, day, null, null, null, false, "", "VALID", null, null, null, 1, 1);
        assertThat(validPage.items()).hasSize(1);
        assertThat(validPage.assessmentCounts()).isEqualTo(all.assessmentCounts());
        assertThat(validPage.assessmentCounts().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(all.total());
        var v = all.items().stream().filter(i -> i.planId() != null).findFirst().orElseThrow();
        var exact =
                service.ledger(
                        day, day, a, null, null, false, "", "", "PLANNED", v.id(), "A", 1, 25);
        assertThat(exact.items()).singleElement().isEqualTo(v);
        assertThat(v.city()).isEqualTo("杭州");
        assertThat(v.departmentName()).isEqualTo("北组");
        assertThat(v.receivedAt()).isEqualTo(at("10:13"));
        var byNumber = service.ledger(day, day, a, null, null, false, v.id().toString(), "",
                null, null, null, 1, 25);
        assertThat(byNumber.items()).isEmpty();
        assertThat(all.items().stream().filter(row -> row.profileId().equals(a)).map(Visit::sequence))
                .containsExactly(1, 2);
        assertThat(exact.cities()).containsExactly("杭州");
        assertThat(exact.departments()).extracting(Option::id).containsExactlyInAnyOrder("1", "2");
        assertThat(
                        service.ledger(
                                        day,
                                        day,
                                        b,
                                        null,
                                        null,
                                        false,
                                        "",
                                        "",
                                        "UNPLANNED",
                                        null,
                                        null,
                                        1,
                                        25)
                                .total())
                .isOne();
        assertThat(
                        service.ledger(
                                        day, day, b, null, null, false, "", "", null, v.id(), null,
                                        1, 25)
                                .items())
                .isEmpty();
    }

    @Test
    void missingAndUnavailableAreUnknownAndDashboardPermissionIsRequired() throws Exception {
        when(directory.sources(any(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("test"));
        var result =
                service.report(
                        day,
                        new FieldworkReportService.Filters(
                                null, null, false, null, null, false, "risk", 1, 20));
        assertThat(result.summary().withContacts()).isZero();
        assertThat(result.items()).allMatch(r -> r.newContacts() == null && r.issues().isEmpty());
        bind(Set.of());
        assertThatThrownBy(
                        () ->
                                service.report(
                                        day,
                                        new FieldworkReportService.Filters(
                                                null, null, false, null, null, false, "risk", 1,
                                                20)))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test void httpContractsExposeTheScopedReportAndSupportOldLedgerFields() throws Exception {
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.rigour.sales.api.FieldworkReportController(service)).build();
        var overviewPulse = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/sales/management/fieldwork/report")
                .param("date", day.toString()).param("employeeCode", "B").param("size", "1");
        http.perform(overviewPulse)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.summary.people").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].employeeCode").value("B"));
        var report=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/sales/management/fieldwork/report")
                .param("date",day.toString()).param("departmentId","1").param("size","1");
        http.perform(report).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.summary.workMinutes").value(465));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/sales/management/visits/records")
                .param("from",day.toString()).param("to",day.toString()).param("person",b.toString()).param("origin","UNPLANNED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].employee").value("B"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].sequence").value(1));
    }
    @Test void emptyDatesReturnAuthorizedHistoryAndStoreSearchOnlyMatchesDisplayedStore() throws Exception {
        var oldDay = day.minusYears(2);
        var old = visit(b, "B", 1, "历史门店", null, 0, 0, 0);
        var raw = old.visit();
        var historical = new Visit(raw.id(), b, oldDay, "B", "B", raw.store(), null, raw.status(),
                raw.arrived().minus(Duration.ofDays(730)), raw.departed().minus(Duration.ofDays(730)),
                null, null, null, null, 1, "VALID", null, null, null, 0, 0, 0, 0, null, null, null, null, false);
        when(facts.visits(tenant, null, null, access, null)).thenReturn(List.of(
                new VisitFact(historical, old.storeKey(), null), visit(a, "A", 1, "本月门店", null, 0, 0, 0)));
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.rigour.sales.api.FieldworkReportController(service)).build();
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/sales/management/visits/records");
        http.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].store").value("本月门店"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[1].store").value("历史门店"));
        assertThat(service.ledger(null, null, null, null, null, false, "历史", "VALID",
                null, null, null, 1, 25).items()).extracting(Visit::id).containsExactly(raw.id());
        assertThat(service.ledger(null, null, null, null, null, false, "乙", "",
                null, null, null, 1, 25).items()).isEmpty();
    }

    @Test void riskQueueIntersectsItsOwnEmployeeScopeWithDashboardScope() throws Exception {
        var f=new FieldworkReportService.Filters(null,null,false,null,null,true,"risk",1,20);
        assertThatThrownBy(()->service.report(day,f,true)).isInstanceOf(AuthorizationDeniedException.class);
        bind(Set.of("sales:dashboard:read","sales:risk:read"));
        when(scope.resolve(any(),eq("sales:risk:read"))).thenReturn(new SalesManagementDirectory.Access(false,Set.of("A")));
        when(facts.issues(tenant,day,access)).thenReturn(List.of(new StaffIssue(a,new Issue(UUID.randomUUID(),null,"GAP","轨迹缺口","OPEN")),
                new StaffIssue(b,new Issue(UUID.randomUUID(),null,"GAP","范围外","OPEN"))));
        var r=service.report(day,f,true);
        assertThat(r.items()).singleElement().satisfies(row->{assertThat(row.salesProfileId()).isEqualTo(a);assertThat(row.issues()).hasSize(1);});
        assertThat(r.summary().pendingPeople()).isOne();
        assertThat(r.people()).hasSize(1);
    }
}
