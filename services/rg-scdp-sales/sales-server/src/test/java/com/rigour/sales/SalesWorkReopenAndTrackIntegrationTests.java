package com.rigour.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.ResumeWorkCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationPointCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayTrackView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayView;
import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.application.port.out.AmapPoiClient.Coordinates;
import com.rigour.sales.application.port.out.AmapPoiClient.GpsPoint;
import com.rigour.sales.application.service.SalesWorkAttendanceService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 验证签退后重新签到（重开工作日）与本人当日轨迹查询的真实事务闭环。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SalesWorkReopenAndTrackIntegrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work_reopen")
            .withUsername("sales_work_reopen_test")
            .withPassword("rigour_sales_work_reopen_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("rigour.workbench.releases.android.latest-build", () -> 226);
        registry.add("rigour.workbench.releases.android.minimum-build", () -> 225);
        registry.add("rigour.workbench.releases.android.install-url", () -> "https://example.org/install");
        registry.add("spring.datasource.url", () -> utcJdbcUrl(MYSQL));
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", () -> utcJdbcUrl(MYSQL));
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final UUID policyVersionId = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SalesWorkAttendanceService attendanceService;

    @MockitoBean private AmapPoiClient maps;

    @Autowired private com.rigour.sales.application.port.out.SalesFieldworkRepository fieldwork;
    @Autowired private com.rigour.sales.application.service.SalesFieldworkMetricsWorker metricsWorker;
    @Autowired private com.rigour.sales.application.port.out.SalesFieldworkEvidenceRepository fieldEvidence;
    @Autowired private com.rigour.sales.application.port.out.FieldworkFacts fieldworkFacts;
    @Autowired private com.rigour.sales.application.port.out.SalesFieldworkReviewRepository reviewCases;
    @Autowired private com.rigour.sales.application.port.out.SalesWorkAttendanceRepository workDays;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired com.rigour.sales.application.port.out.FieldworkFacts dailyFacts;

    @Test
    void dailyReportReadsRealPunchesAndEnforcesTenantAndScope() {
        var location=new LocationEvidence(new BigDecimal("120.1"),new BigDecimal("30.2"),new BigDecimal("15"),"APP_NATIVE");
        var day=attendanceService.checkIn(new CheckInCommand("v2-workday","client",Instant.now(),location,null,"ONLINE"));
        var all=new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(true,Set.of());
        var none=new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(false,Set.of());
        assertThat(dailyFacts.roster(tenantId,day.businessDate(),all)).extracting(r->r.id()).containsExactly(profileId);
        assertThat(dailyFacts.punches(tenantId,day.businessDate(),all)).hasSize(1);
        assertThat(dailyFacts.visits(tenantId,day.businessDate(),day.businessDate(),all,null)).isEmpty();
        assertThat(dailyFacts.plans(tenantId,day.businessDate(),all)).isEmpty();
        assertThat(dailyFacts.issues(tenantId,day.businessDate(),all)).isEmpty();
        assertThat(dailyFacts.roster(tenantId,day.businessDate(),none)).isEmpty();
        assertThat(dailyFacts.punches(UUID.randomUUID(),day.businessDate(),all)).isEmpty();
    }

    @Test
    void manualReviewIsIdempotentAndPreservesExistingAutomaticEvidence() {
        var location=new LocationEvidence(new BigDecimal("120.1"),new BigDecimal("30.2"),new BigDecimal("15"),"APP_NATIVE");
        var day=attendanceService.checkIn(new CheckInCommand("review-in","client",Instant.now(),location,null,"ONLINE"));
        reviewCases.detect(tenantId,profileId,day.id(),null,"automatic-device","DEVICE","原自动线索","{}",Instant.now());
        var access=org.mockito.Mockito.mock(com.rigour.sales.application.service.SalesFieldworkService.class);
        var review=new com.rigour.sales.application.service.SalesFieldworkReviewService(reviewCases,workDays,null,access,
                org.mockito.Mockito.mock(com.rigour.shared.audit.AuditSink.class),java.time.Clock.systemUTC());
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var command=new com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.StartReview("manual-1","DEVICE","请说明设备变更原因");
        var created=tx.execute(status->review.start(profileId,day.id(),command));
        var replay=tx.execute(status->review.start(profileId,day.id(),command));
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.version()).isEqualTo(1);assertThat(replay.events()).hasSize(1);
        assertThat(replay.status()).isEqualTo("AWAITING_STATEMENT");
        assertThat(reviewCases.findEvidence(tenantId,"automatic-device").orElseThrow().version()).isZero();
        assertThatThrownBy(()->tx.execute(status->review.start(profileId,day.id(),
                new com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.StartReview("manual-2","DEVICE","另一条说明"))))
                .hasMessageContaining("已有核查事项");
        assertThatThrownBy(()->tx.execute(status->review.start(profileId,day.id(),
                new com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.StartReview("manual-1","DEVICE","另一条说明"))))
                .hasMessageContaining("请求标识已用于不同内容");
        org.mockito.Mockito.doThrow(new com.rigour.shared.context.AuthorizationDeniedException("sales:location:sensitive:read"))
                .when(access).requireProfile(profileId,"sales:location:sensitive:read");
        assertThatThrownBy(()->tx.execute(status->review.start(profileId,day.id(),
                new com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.StartReview("manual-location","LOCATION","核实出发地点"))))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        assertThat(reviewCases.byDay(tenantId,day.id())).hasSize(2);
        assertThat(reviewCases.findEvidence(UUID.randomUUID(),"manual:"+day.id()+":DEVICE")).isEmpty();
    }

    @Test
    void nameRiskAndPendingFiltersUseIdenticalRowsAndTotals() {
        var location=new LocationEvidence(new BigDecimal("120.1"),new BigDecimal("30.2"),new BigDecimal("15"),"APP_NATIVE");
        var day=attendanceService.checkIn(new CheckInCommand("filter-in","client",Instant.now(),location,null,"ONLINE"));
        jdbc.update("UPDATE sales_profile SET employee_id=NULL,platform_user_id=?,hr_employee_id=100,hr_employee_code='MATCHED' WHERE id=?",bin(userId),bin(profileId));
        reviewCases.detect(tenantId,profileId,day.id(),null,"filter-device","DEVICE","设备待核查","{}",Instant.now());
        var scope=new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(false,Set.of("MATCHED"));
        var search=new com.rigour.sales.application.port.out.SalesFieldworkRepository.Search(null,"员工姓名",Set.of("MATCHED"),true,"DEVICE");
        assertThat(fieldwork.page(tenantId,day.businessDate(),scope,search,10,0)).hasSize(1);
        assertThat(fieldwork.totals(tenantId,day.businessDate(),scope,search).total()).isEqualTo(1);
        var wrongKind=new com.rigour.sales.application.port.out.SalesFieldworkRepository.Search(null,null,Set.of(),true,"LOCATION");
        assertThat(fieldwork.page(tenantId,day.businessDate(),scope,wrongKind,10,0)).isEmpty();
        assertThat(fieldwork.totals(tenantId,day.businessDate(),scope,wrongKind).total()).isZero();
        var item=reviewCases.findEvidence(tenantId,"filter-device").orElseThrow();
        reviewCases.append(tenantId,item.id(),userId,"resolve","REVIEW","CLEARED","已核实","RESOLVED",Instant.now());
        assertThat(fieldwork.page(tenantId,day.businessDate(),scope,search,10,0)).isEmpty();
        assertThat(fieldwork.totals(tenantId,day.businessDate(),scope,search).total()).isZero();
    }

    @Test
    void deviceEvidenceKeepsInstallationChangesAndTenantBoundaries() {
        var location=new LocationEvidence(new BigDecimal("120.1"),new BigDecimal("30.2"),new BigDecimal("15"),"FEISHU");
        var day=attendanceService.checkIn(new CheckInCommand("device-in","client",Instant.now(),location,"installation-a","ONLINE"));
        attendanceService.checkOut(day.id(),new CheckOutCommand("device-out",Instant.now(),location,"installation-b","ONLINE"));
        var events=fieldEvidence.devices(tenantId,profileId,day.id());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).installationReference()).hasSize(16).isNotEqualTo(events.get(1).installationReference());
        assertThat(events.get(0).firstSeenAt()).isEqualTo(events.get(0).receivedAt());
        assertThat(fieldEvidence.devices(UUID.randomUUID(),profileId,day.id())).isEmpty();
        assertThat(fieldEvidence.devices(tenantId,UUID.randomUUID(),day.id())).isEmpty();
    }

    @Test
    void historyCountsDaysSeparatelyAndNeverJoinsUnmappedStoresOrOtherScopes() {
        UUID other=UUID.randomUUID(),visitPolicy=UUID.randomUUID(),visitVersion=UUID.randomUUID();
        jdbc.update("UPDATE sales_profile SET employee_id=NULL,platform_user_id=?,hr_employee_id=100,hr_employee_code='SELF' WHERE id=?",bin(userId),bin(profileId));
        jdbc.update("INSERT INTO sales_profile(id,tenant_id,employee_id,platform_user_id,hr_employee_id,hr_employee_code,sales_no,status,effective_from,source_version,version,created_at,updated_at) VALUES(?,?,NULL,?,101,'OTHER','S-OTHER','ACTIVE',UTC_TIMESTAMP(6),1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",bin(other),bin(tenantId),bin(UUID.randomUUID()));
        jdbc.update("INSERT INTO sales_visit_policy(id,tenant_id,policy_code,policy_name,status,created_at,updated_at) VALUES(?,?,'EVIDENCE','证据测试','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",bin(visitPolicy),bin(tenantId));
        jdbc.update("INSERT INTO sales_visit_policy_version(id,tenant_id,policy_id,version_no,publish_status,check_in_radius_meters,minimum_dwell_minutes,required_photo_count,created_by,created_at) VALUES(?,?,?,1,'PUBLISHED',100,0,1,?,UTC_TIMESTAMP(6))",bin(visitVersion),bin(tenantId),bin(visitPolicy),bin(userId));
        LocalDate date=LocalDate.of(2026,11,13);
        UUID current=historyCapture(profileId,100,date,77L,"10:00:00",visitVersion);
        historyCapture(profileId,100,date,77L,"08:00:00",visitVersion);
        historyCapture(profileId,100,date,77L,"09:00:00",visitVersion);
        historyCapture(profileId,100,date.minusDays(7),77L,"09:00:00",visitVersion);
        historyCapture(profileId,100,date.minusDays(30),77L,"09:00:00",visitVersion);
        historyCapture(profileId,100,date,null,"07:00:00",visitVersion);
        historyCapture(profileId,100,date,78L,"07:00:00",visitVersion);
        historyCapture(profileId,100,date,77L,"11:00:00",visitVersion);
        historyCapture(other,101,date,77L,"07:00:00",visitVersion);
        var capture=fieldEvidence.capture(tenantId,current).orElseThrow();
        var own=fieldEvidence.history(tenantId,capture,new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(false,Set.of("SELF")),1,2,false);
        assertThat(own.ownVisits7()).isEqualTo(2);assertThat(own.ownDays7()).isEqualTo(1);
        assertThat(own.ownVisits30()).isEqualTo(3);assertThat(own.ownDays30()).isEqualTo(2);
        assertThat(own.total()).isEqualTo(3);assertThat(own.items()).hasSize(2).allMatch(v->v.salesProfileId().equals(profileId));
        var all=fieldEvidence.history(tenantId,capture,new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(true,Set.of()),1,10,false);
        assertThat(all.visibleVisits30()).isEqualTo(4);assertThat(all.ownVisits30()).isEqualTo(3);
        assertThat(fieldEvidence.history(tenantId,capture,new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(false,Set.of()),1,10,false).total()).isZero();
        var allScope = new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(true, Set.of());
        var throughVisit = fieldEvidence.history(tenantId, capture, allScope, 1, 10, true);
        assertThat(throughVisit.ownPriorVisits()).isEqualTo(4);
        assertThat(throughVisit.visiblePriorVisits()).isEqualTo(5);
        assertThat(throughVisit.total()).isEqualTo(5);
        assertThat(throughVisit.items()).anyMatch(v -> v.businessDate().equals(date.minusDays(30)));
        var counts = fieldworkFacts.storeVisitCounts(tenantId, allScope, List.of(current)).get(current);
        assertThat(counts.storeVisits()).isEqualTo(6);
        assertThat(counts.salespersonVisits()).isEqualTo(5);
        assertThat(fieldEvidence.capture(UUID.randomUUID(),current)).isEmpty();
    }

    @Test
    void cancelledVisitsDoNotEnterTrackNumbersOrTravelSegments() {
        UUID policy=UUID.randomUUID(), version=UUID.randomUUID();
        jdbc.update("INSERT INTO sales_visit_policy(id,tenant_id,policy_code,policy_name,status,created_at,updated_at) VALUES(?,?,'TRACK','轨迹测试','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",bin(policy),bin(tenantId));
        jdbc.update("INSERT INTO sales_visit_policy_version(id,tenant_id,policy_id,version_no,publish_status,check_in_radius_meters,minimum_dwell_minutes,required_photo_count,created_by,created_at) VALUES(?,?,?,1,'PUBLISHED',100,0,1,?,UTC_TIMESTAMP(6))",bin(version),bin(tenantId),bin(policy),bin(userId));
        LocalDate date=LocalDate.of(2026,11,13);
        UUID last=historyCapture(profileId,100,date,79L,"10:00:00",version);
        UUID first=historyCapture(profileId,100,date,77L,"08:00:00",version);
        UUID cancelled=historyCapture(profileId,100,date,78L,"09:00:00",version);
        for (UUID visit : List.of(last,first,cancelled)) {
            jdbc.update("UPDATE sales_visit SET checked_out_at=DATE_ADD(checked_in_at,INTERVAL 10 MINUTE) WHERE id=?",bin(visit));
            jdbc.update("INSERT INTO sales_visit_target_snapshot(id,tenant_id,visit_id,target_type,store_name,store_address,store_longitude,store_latitude,captured_at) VALUES(?,?,?,'STORE','测试门店','测试地址',116,40,UTC_TIMESTAMP(6))",bin(UUID.randomUUID()),bin(tenantId),bin(visit));
        }
        jdbc.update("UPDATE sales_visit SET status='CANCELLED' WHERE id=?",bin(cancelled));
        var track=attendanceService.track(date);
        assertThat(track.visits()).extracting(v->v.visitId()).containsExactly(first,last);
        assertThat(track.visits()).extracting(v->v.sequence()).containsExactly(1,2);
        assertThat(track.segments()).hasSize(1);
        assertThat(track.segments().getFirst().fromVisitId()).isEqualTo(first);
        assertThat(track.segments().getFirst().toVisitId()).isEqualTo(last);
        assertThat(track.segments().getFirst().fromSequence()).isEqualTo(1);
        assertThat(track.segments().getFirst().toSequence()).isEqualTo(2);
        assertThat(track.distanceAvailable()).isFalse();
    }

    @Test
    void trackClassifiesCrmRevisitsInOneScopedQueryAndIgnoresCancelledHistory() {
        UUID policy=UUID.randomUUID(), version=UUID.randomUUID(), other=UUID.randomUUID();
        jdbc.update("INSERT INTO sales_visit_policy(id,tenant_id,policy_code,policy_name,status,created_at,updated_at) VALUES(?,?,'TRACK-TYPE','轨迹类型','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",bin(policy),bin(tenantId));
        jdbc.update("INSERT INTO sales_visit_policy_version(id,tenant_id,policy_id,version_no,publish_status,check_in_radius_meters,minimum_dwell_minutes,required_photo_count,created_by,created_at) VALUES(?,?,?,1,'PUBLISHED',100,0,1,?,UTC_TIMESTAMP(6))",bin(version),bin(tenantId),bin(policy),bin(userId));
        jdbc.update("INSERT INTO sales_profile(id,tenant_id,platform_user_id,employee_id,hr_employee_id,hr_employee_code,sales_no,status,effective_from,source_version,version,created_at,updated_at) VALUES(?,?,?,NULL,101,'TRACK-OTHER','TRACK-OTHER','ACTIVE',UTC_TIMESTAMP(6),1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",bin(other),bin(tenantId),bin(UUID.randomUUID()));
        LocalDate date=LocalDate.of(2026,11,13);
        UUID cancelled=historyCapture(profileId,100,date,77L,"07:00:00",version);
        jdbc.update("UPDATE sales_visit SET status='CANCELLED' WHERE id=?",bin(cancelled));
        historyCapture(other,101,date,77L,"07:30:00",version);
        UUID first=historyCapture(profileId,100,date,77L,"08:00:00",version);
        UUID again=historyCapture(profileId,100,date,77L,"09:00:00",version);
        UUID different=historyCapture(profileId,100,date,78L,"10:00:00",version);
        historyCapture(profileId,100,date.minusDays(1),79L,"08:00:00",version);
        UUID crossDay=historyCapture(profileId,100,date,79L,"11:00:00",version);
        for (UUID visit : List.of(first,again,different,crossDay)) {
            jdbc.update("INSERT INTO sales_visit_target_snapshot(id,tenant_id,visit_id,target_type,store_name,store_address,store_longitude,store_latitude,captured_at) VALUES(?,?,?,'STORE','测试门店','测试地址',116,40,UTC_TIMESTAMP(6))",bin(UUID.randomUUID()),bin(tenantId),bin(visit));
        }
        var track=attendanceService.track(date);
        assertThat(track.visits()).extracting(v->v.visitId()).containsExactly(first,again,different,crossDay);
        assertThat(track.visits()).extracting(v->v.visitType()).containsExactly("FIRST_VISIT","REVISIT","FIRST_VISIT","REVISIT");
        var repository=new com.rigour.sales.infrastructure.persistence.JdbcSalesWorkVisitRepository(jdbc);
        assertThat(repository.findRevisitedVisitIds(UUID.randomUUID(),profileId,track.workDayId())).isEmpty();
        assertThat(repository.findRevisitedVisitIds(tenantId,other,track.workDayId())).isEmpty();
    }

    private UUID historyCapture(UUID profile,long hrId,LocalDate date,Long store,String time,UUID policy) {
        var dayIds=jdbc.query("SELECT id FROM sales_work_day WHERE tenant_id=? AND sales_profile_id=? AND business_date=?",(r,n)->r.getBytes(1),bin(tenantId),bin(profile),date);
        byte[] day;
        if(dayIds.isEmpty()) {
            day=bin(UUID.randomUUID());
            jdbc.update("INSERT INTO sales_work_day(id,tenant_id,employee_id,hr_employee_id,sales_profile_id,business_date,timezone_id,field_policy_version_id,status,created_at,updated_at) VALUES(?,?,NULL,?,?,?,'Asia/Shanghai',?,'FINISHED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",day,bin(tenantId),hrId,bin(profile),date,bin(policyVersionId));
        } else day=dayIds.getFirst();
        UUID visit=UUID.randomUUID();
        jdbc.update("INSERT INTO sales_visit(id,tenant_id,work_day_id,sales_profile_id,target_type,crm_store_id,visit_policy_version_id,status,checked_in_at,result_note,created_at,updated_at) VALUES(?,?,?,?,'STORE',?,?,'CHECKED_OUT',?,'原始拜访说明',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",bin(visit),bin(tenantId),day,bin(profile),store,bin(policy),Timestamp.valueOf(date+" "+time));
        return visit;
    }

    @Autowired private org.springframework.web.context.WebApplicationContext web;

    @Test
    void oldBuildCanReadReleaseAndFinishWorkButCannotStartANewVisit() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        mvc.perform(get("/api/v1/sales/me/mobile-release/android"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.minimumBuild").value(225));
        mvc.perform(post("/api/v1/sales/work/store-visits")
                        .header("X-Workbench-Platform", "android").header("X-Workbench-Build", "224"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        // An empty completion reaches normal request validation with the same old client headers.
        mvc.perform(post("/api/v1/sales/work-days/{id}/check-out", UUID.randomUUID())
                        .header("X-Workbench-Platform", "android").header("X-Workbench-Build", "224"))
                .andExpect(status().isBadRequest());
    }

    @BeforeEach
    void seedSalesProjection() {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO sales_identity_projection
                    (id, tenant_id, platform_user_id, employee_id, status, effective_from,
                     source_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6) - INTERVAL 60 SECOND,
                        1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(UUID.randomUUID()), bin(tenantId), bin(userId), bin(employeeId));
        jdbc.update("""
                INSERT INTO sales_profile
                    (id, tenant_id, employee_id, sales_no, city_org_id, status, effective_from,
                     source_version, version, created_at, updated_at)
                VALUES (?, ?, ?, 'S-REOPEN-001', NULL, 'ACTIVE', UTC_TIMESTAMP(6) - INTERVAL 60 SECOND,
                        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(profileId), bin(tenantId), bin(employeeId));
        jdbc.update("""
                INSERT INTO sales_field_policy
                    (id, tenant_id, policy_code, policy_name, status, version, created_at, updated_at)
                VALUES (?, ?, 'FIELD-REOPEN', '重开测试外勤规则', 'ACTIVE', 1, ?, ?)
                """, bin(policyId), bin(tenantId), timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO sales_field_policy_version
                    (id, tenant_id, policy_id, version_no, publish_status, timezone_id,
                     business_day_cutoff, check_in_window_start, check_in_window_end,
                     check_out_window_start, check_out_window_end, standard_work_minutes,
                     minimum_work_minutes, require_check_out, allow_adjustment,
                     adjustment_deadline_hours, location_enabled, location_interval_minutes,
                     minimum_location_accuracy_meters, offline_upload_deadline_minutes,
                     effective_from, approved_by, approved_at, created_by, created_at)
                VALUES (?, ?, ?, 1, 'PUBLISHED', 'Asia/Shanghai', '04:00:00', NULL, NULL,
                        NULL, NULL, 480, 240, 1, 1, 24, 1, 20, 100.00, 120,
                        ?, ?, ?, ?, ?)
                """, bin(policyVersionId), bin(tenantId), bin(policyId), timestamp(now.minusSeconds(60)),
                bin(userId), timestamp(now.minusSeconds(60)), bin(userId), timestamp(now));
        setCaller();
    }

    @AfterEach
    void clearCaller() throws Exception {
        Method clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    @Test
    void explicitResumeAfterCheckOutPreservesPeriodsAndTrack() {
        LocationEvidence location = new LocationEvidence(
                new BigDecimal("120.1000000"), new BigDecimal("30.2000000"),
                new BigDecimal("15"), "FEISHU");
        WorkDayView first = attendanceService.checkIn(
                new CheckInCommand("reopen-check-in-1", "client-1", Instant.now(), location,
                        null, "ONLINE"));
        assertThat(first.status()).isEqualTo("ACTIVE");
        LocalDate businessDate = first.businessDate();

        attendanceService.uploadLocationPoints(first.id(), new LocationBatchCommand("reopen-location-1",
                List.of(new LocationPointCommand("reopen-point-1", new BigDecimal("120.1001000"),
                        new BigDecimal("30.2001000"), new BigDecimal("12"), Instant.now(), "FEISHU")), first.locationSessionId()));

        WorkDayView finished = attendanceService.checkOut(first.id(),
                new CheckOutCommand("reopen-check-out-1", Instant.now(), location, null, "ONLINE"));
        assertThat(finished.status()).isEqualTo("FINISHED");
        int firstSpanMinutes = finished.verifiedWorkMinutes();

        assertThatThrownBy(() -> attendanceService.checkIn(new CheckInCommand("ordinary-in", "client-1", Instant.now(), location, null, "ONLINE")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("恢复作业");
        assertThatThrownBy(() -> attendanceService.resumeWork(first.id(), new ResumeWorkCommand("empty-reason", "client-1", Instant.now(), location, null, "ONLINE", " ")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原因");
        var resume = new ResumeWorkCommand("reopen-check-in-2", "client-1", Instant.now(), location, null, "ONLINE", "客户临时追加拜访");
        WorkDayView reopened = attendanceService.resumeWork(first.id(), resume);
        assertThat(attendanceService.resumeWork(first.id(), resume).locationSessionId()).isEqualTo(reopened.locationSessionId());
        assertThat(reopened.workPeriods()).hasSize(2);
        assertThat(reopened.workPeriods().getLast().reason()).isEqualTo("客户临时追加拜访");
        assertThat(reopened.id()).isEqualTo(first.id());
        assertThat(reopened.status()).isEqualTo("ACTIVE");
        assertThat(reopened.checkedInAt()).isEqualTo(first.checkedInAt());
        assertThat(reopened.locationSessionId()).isNotEqualTo(first.locationSessionId());

        // 重开后的定位点进入新会话，轨迹查询能看到两段事实。
        var secondBatch = attendanceService.uploadLocationPoints(first.id(),
                new LocationBatchCommand("reopen-location-2",
                        List.of(new LocationPointCommand("reopen-point-2", new BigDecimal("120.1002000"),
                                new BigDecimal("30.2002000"), new BigDecimal("18"), Instant.now(), "FEISHU")), reopened.locationSessionId()));
        assertThat(secondBatch.acceptedCount()).isEqualTo(1);

        WorkDayView finishedAgain = attendanceService.checkOut(first.id(),
                new CheckOutCommand("reopen-check-out-2", Instant.now(), location, null, "ONLINE"));
        assertThat(finishedAgain.status()).isEqualTo("FINISHED");
        assertThat(finishedAgain.verifiedWorkMinutes()).isGreaterThanOrEqualTo(firstSpanMinutes);

        // 日结 summary 版本递增，不改写历史版本。
        Integer summaryCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_work_day_summary WHERE tenant_id=? AND work_day_id=?",
                Integer.class, bin(tenantId), bin(first.id()));
        assertThat(summaryCount).isEqualTo(2);
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(summary_version) FROM sales_work_day_summary WHERE tenant_id=? AND work_day_id=?",
                Integer.class, bin(tenantId), bin(first.id()));
        assertThat(maxVersion).isEqualTo(2);

        // 轨迹查询返回定位点和签到/签退打点，按时间升序。
        WorkDayTrackView track = attendanceService.track(businessDate);
        assertThat(track.workDayId()).isEqualTo(first.id());
        assertThat(track.points()).hasSize(2);
        // One point per session cannot establish either route; never join off-work time.
        assertThat(track.totalDistanceMeters()).isZero();
        assertThat(track.distanceAvailable()).isFalse();
        assertThat(track.visits()).isEmpty();
        assertThat(track.segments()).isEmpty();
        assertThat(track.punches()).hasSize(4);
        assertThat(track.punches().get(0).eventType()).isEqualTo("CHECK_IN");
        assertThat(track.punches().get(1).eventType()).isEqualTo("CHECK_OUT");
        assertThat(track.punches().get(2).eventType()).isEqualTo("CHECK_IN");
        assertThat(track.punches().get(3).eventType()).isEqualTo("CHECK_OUT");
        assertThat(track.punches()).allMatch(punch -> punch.longitude() != null);

        // 两个定位会话都已关闭。
        Integer closedSessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_location_session WHERE tenant_id=? AND work_day_id=? AND status='CLOSED'",
                Integer.class, bin(tenantId), bin(first.id()));
        assertThat(closedSessions).isEqualTo(2);
    }

    @Test
    void workDurationSumsActualPeriodsAndRoundsOnlyAfterSumming() {
        var location=new LocationEvidence(BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE,"APP_NATIVE");
        var day=attendanceService.checkIn(new CheckInCommand("duration-in","client",Instant.now(),location,null,null));
        attendanceService.checkOut(day.id(),new CheckOutCommand("duration-out",Instant.now(),location,null,null));
        attendanceService.resumeWork(day.id(),new ResumeWorkCommand("duration-resume","client",Instant.now(),location,null,null,"临时拜访"));
        var now=Instant.now();
        var events=jdbc.query("SELECT id FROM sales_punch_event WHERE tenant_id=? AND work_day_id=? ORDER BY server_received_at",(rs,n)->rs.getBytes(1),bin(tenantId),bin(day.id()));
        jdbc.update("UPDATE sales_punch_event SET server_received_at=? WHERE id=?",timestamp(now.minusSeconds(10800)),events.get(0));
        jdbc.update("UPDATE sales_punch_event SET server_received_at=? WHERE id=?",timestamp(now.minusSeconds(8120)),events.get(1));
        jdbc.update("UPDATE sales_punch_event SET server_received_at=? WHERE id=?",timestamp(now.minusSeconds(1840)),events.get(2));
        var finished=attendanceService.checkOut(day.id(),new CheckOutCommand("duration-final",now,location,null,null));
        assertThat(finished.totalWorkMinutes()).isEqualTo(75);
        assertThat(finished.verifiedWorkMinutes()).isEqualTo(75);
        assertThat(finished.workPeriods()).hasSize(2);
    }

    @Autowired private com.rigour.sales.application.service.SalesWorkDayExpiryWorker expiry;
    @Autowired private com.rigour.sales.application.service.SalesFieldworkReviewService reviewService;
    @Autowired private com.rigour.sales.application.port.out.SalesWorkQueryRepository policies;

    @Test
    void cutoffStopsCaptureWithoutManufacturingCheckoutAndAdjustmentRespectsOriginalRule() {
        var location=new LocationEvidence(BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE,"APP_NATIVE");
        var day=attendanceService.checkIn(new CheckInCommand("cutoff-in","client",Instant.now(),location,null,null));
        var previous=day.businessDate().minusDays(1);
        jdbc.update("UPDATE sales_work_day SET business_date=? WHERE id=?",previous,bin(day.id()));
        jdbc.update("UPDATE sales_work_day SET checked_in_at=DATE_SUB(checked_in_at,INTERVAL 1 DAY) WHERE id=?",bin(day.id()));
        jdbc.update("UPDATE sales_punch_event SET server_received_at=DATE_SUB(server_received_at,INTERVAL 1 DAY) WHERE work_day_id=?",bin(day.id()));
        jdbc.update("UPDATE sales_location_session SET started_at=DATE_SUB(started_at,INTERVAL 1 DAY) WHERE work_day_id=?",bin(day.id()));
        expiry.expireOwn(tenantId,profileId);
        var expired=attendanceService.workDay(previous);
        assertThat(expired.status()).isEqualTo("MISSING_CHECK_OUT");assertThat(expired.checkedOutAt()).isNull();
        assertThat(expired.totalWorkMinutes()).isZero();
        assertThat(workDays.findPunchEvents(tenantId,day.id())).hasSize(1);
        assertThat(workDays.findLocationSession(tenantId,day.id(),day.locationSessionId()).orElseThrow().status()).isEqualTo("CLOSED");
        assertThat(reviewCases.byDay(tenantId,day.id())).hasSize(1);
        expiry.expireOwn(tenantId,profileId);assertThat(reviewCases.byDay(tenantId,day.id())).hasSize(1);
        var command=new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Statement("adjust-once","实际结束时间18:00，忘记签退");
        var created=reviewService.adjustment(day.id(),command);
        assertThat(reviewService.adjustment(day.id(),command).events()).hasSize(1);
        jdbc.update("UPDATE sales_field_policy_version SET allow_adjustment=0 WHERE id=?",bin(policyVersionId));
        assertThatThrownBy(()->reviewService.adjustment(day.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Statement("adjust-disabled","新的说明"))).hasMessageContaining("未开放");
        assertThat(reviewService.adjustment(day.id(),command).id()).isEqualTo(created.id());
        jdbc.update("UPDATE sales_field_policy_version SET allow_adjustment=1,adjustment_deadline_hours=1 WHERE id=?",bin(policyVersionId));
        assertThatThrownBy(()->reviewService.adjustment(day.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Statement("adjust-expired","新的说明"))).hasMessageContaining("超过");
    }

    @Test
    void disabledResumeAndDisabledTrackingKeepPunchLocationRequirements() {
        jdbc.update("UPDATE sales_field_policy_version SET allow_resume=0,location_enabled=0 WHERE id=?",bin(policyVersionId));
        assertThatThrownBy(()->attendanceService.checkIn(new CheckInCommand("no-location","client",Instant.now(),null,null,null))).isInstanceOf(BusinessException.class);
        var location=new LocationEvidence(BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE,"APP_NATIVE");
        var day=attendanceService.checkIn(new CheckInCommand("no-track","client",Instant.now(),location,null,null));
        assertThat(day.locationSessionId()).isNull();
        attendanceService.checkOut(day.id(),new CheckOutCommand("no-track-out",Instant.now(),location,null,null));
        assertThatThrownBy(()->attendanceService.resumeWork(day.id(),new ResumeWorkCommand("not-allowed","client",Instant.now(),location,null,null,"临时拜访"))).hasMessageContaining("不允许");
    }

    @Test
    void businessDateHasNoGapWhenBoundaryChangesInEitherDirection() throws Exception {
        Method date=SalesWorkAttendanceService.class.getDeclaredMethod("businessDate",Instant.class,com.rigour.sales.application.port.out.SalesWorkQueryRepository.FieldPolicy.class);date.setAccessible(true);
        jdbc.update("UPDATE sales_field_policy_version SET business_day_cutoff='06:00',effective_from=? WHERE id=?",timestamp(Instant.parse("2026-11-18T16:00:00Z")),bin(policyVersionId));
        var later=policies.findFieldPolicy(tenantId,policyVersionId).orElseThrow();
        assertThat(date.invoke(null,Instant.parse("2026-11-18T21:59:59Z"),later)).isEqualTo(LocalDate.of(2026,11,19));
        assertThat(date.invoke(null,Instant.parse("2026-11-19T21:59:59Z"),later)).isEqualTo(LocalDate.of(2026,11,19));
        assertThat(date.invoke(null,Instant.parse("2026-11-19T22:00:00Z"),later)).isEqualTo(LocalDate.of(2026,11,20));
        jdbc.update("UPDATE sales_field_policy_version SET business_day_cutoff='00:00',effective_from=? WHERE id=?",timestamp(Instant.parse("2026-11-18T22:00:00Z")),bin(policyVersionId));
        var earlier=policies.findFieldPolicy(tenantId,policyVersionId).orElseThrow();
        assertThat(date.invoke(null,Instant.parse("2026-11-19T15:59:59Z"),earlier)).isEqualTo(LocalDate.of(2026,11,19));
        assertThat(date.invoke(null,Instant.parse("2026-11-19T16:00:00Z"),earlier)).isEqualTo(LocalDate.of(2026,11,20));
    }

    @Test
    void convertsTwelveRawFixesTogetherAndReplaysWithoutAnExternalCall() {
        var origin = new BigDecimal("120.100000");
        var latitude = new BigDecimal("30.200000");
        var accuracy = new BigDecimal("4");
        var location = new LocationEvidence(origin, latitude, accuracy, "APP_NATIVE");
        var now = Instant.now();
        var day = attendanceService.checkIn(new CheckInCommand("gps-in", "client", now.minusSeconds(59), location, null, null));
        var raw = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> new GpsPoint(origin.add(BigDecimal.valueOf(index * 2L, 4)), latitude)).toList();
        var offset = new BigDecimal("0.004123");
        when(maps.convertGpsBatch(raw)).thenReturn(raw.stream()
                .map(point -> new Coordinates(point.longitude().add(offset), point.latitude(), "GCJ02")).toList());
        var points = java.util.stream.IntStream.range(0, raw.size()).mapToObj(index ->
                new LocationPointCommand("gps-" + index, null, null, accuracy, now.minusSeconds(55 - index * 5L),
                        "APP_GPS", raw.get(index).longitude(), raw.get(index).latitude(), "WGS84")).toList();
        var command = new LocationBatchCommand("gps-batch", points, day.locationSessionId());

        assertThat(attendanceService.uploadLocationPoints(day.id(), command).acceptedCount()).isEqualTo(12);
        assertThat(attendanceService.uploadLocationPoints(day.id(), command).acceptedCount()).isEqualTo(12);
        verify(maps).convertGpsBatch(raw);
        var track = attendanceService.track(day.businessDate());
        assertThat(track.points()).hasSize(12);
        assertThat(track.legs()).hasSize(1);
        assertThat(track.totalDistanceMeters()).isGreaterThan(150);
        assertThat(track.points().getFirst().longitude()).isEqualByComparingTo(origin.add(offset));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_GPS", "APP_IOS_LOCATION"})
    void confirmedTrackBackfillsAcrossBatchesWithoutReprocessingAnOlderSession(String source) {
        jdbc.update("UPDATE sales_field_policy_version SET location_sample_interval_seconds=5 WHERE id=?", bin(policyVersionId));
        var lng = new BigDecimal("120.100000");
        var lat = new BigDecimal("30.200000");
        var accuracy = new BigDecimal("4");
        var location = new LocationEvidence(lng, lat, accuracy, "APP_NATIVE");
        var now = Instant.now();
        var old = attendanceService.checkIn(new CheckInCommand("old-in", "client", now, location, null, null));
        assertThat(old.confirmedTrack()).isFalse();
        attendanceService.uploadLocationPoints(old.id(), new LocationBatchCommand("old-batch", List.of(
                new LocationPointCommand("old-1", lng, lat, accuracy, now.minusSeconds(55), "APP_GPS"),
                new LocationPointCommand("old-2", lng, lat.add(new BigDecimal("0.0001")), accuracy, now.minusSeconds(50), "APP_GPS")),
                old.locationSessionId()));
        var oldTrack = attendanceService.track(old.businessDate());
        assertThat(oldTrack.totalDistanceMeters()).isEqualTo(11);
        attendanceService.checkOut(old.id(), new CheckOutCommand("old-out", now, location, null, null));

        var day = attendanceService.resumeWork(old.id(), new ResumeWorkCommand("confirmed-in", "client", now, location, null, null, "补充拜访", true));
        assertThat(day.confirmedTrack()).isTrue();
        assertThat(day.locationSessionId()).isNotEqualTo(old.locationSessionId());
        var p0 = lat;
        var p1 = lat.add(new BigDecimal("0.00006"));
        var p2 = lat.add(new BigDecimal("0.00012"));
        var p3 = lat.add(new BigDecimal("0.00012"));
        var east = lng.add(new BigDecimal("0.00005"));
        var original = List.of(new GpsPoint(lng, p0),new GpsPoint(lng,p1),new GpsPoint(lng,p2),new GpsPoint(east,p3),
                new GpsPoint(lng,p2),new GpsPoint(lng,p1),new GpsPoint(lng,p0),new GpsPoint(lng,p0));
        when(maps.convertGpsBatch(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<GpsPoint> fixes = invocation.getArgument(0);
            return fixes.stream().map(fix -> new Coordinates(fix.longitude().add(new BigDecimal("0.004")), fix.latitude(), "GCJ02")).toList();
        });
        var proofs = List.of(List.<String>of(), List.<String>of(), List.of("new-0","new-1","new-2"), List.<String>of(),
                List.of("new-2","new-3"), List.of("new-3","new-4"), List.of("new-4","new-5"), List.of("new-5","new-6"));
        var points = java.util.stream.IntStream.range(0, original.size()).mapToObj(index ->
                new LocationPointCommand("new-" + index, null, null, accuracy, now.minusSeconds(45 - index * 5L),
                        index == 7 && "APP_GPS".equals(source) ? "APP_GPS_STILL" : source,
                        original.get(index).longitude(), original.get(index).latitude(), "WGS84", proofs.get(index))).toList();
        var first = new LocationBatchCommand("confirmed-first", points.subList(0,2), day.locationSessionId());
        var second = new LocationBatchCommand("confirmed-second", points.subList(2,8), day.locationSessionId());
        assertThat(attendanceService.uploadLocationPoints(day.id(), first).acceptedCount()).isEqualTo(2);
        assertThat(attendanceService.track(day.businessDate()).legs()).isEqualTo(oldTrack.legs());
        assertThat(attendanceService.uploadLocationPoints(day.id(), second).acceptedCount()).isEqualTo(6);
        assertThat(attendanceService.uploadLocationPoints(day.id(), second).acceptedCount()).isEqualTo(6);
        verify(maps).convertGpsBatch(original.subList(0,2));
        verify(maps).convertGpsBatch(original.subList(2,8));
        var track = attendanceService.track(day.businessDate());
        assertThat(track.legs()).hasSize(2);
        assertThat(track.legs().getFirst()).isEqualTo(oldTrack.legs().getFirst());
        assertThat(track.legs().getLast().confirmed()).isTrue();
        assertThat(track.legs().getLast().points()).hasSize(7);
        assertThat(track.legs().getLast().distanceMeters()).isBetween(35,38);
        assertThat(track.totalDistanceMeters()).isEqualTo(11+track.legs().getLast().distanceMeters());
        assertThat(track.rejectedPointCount()).isZero();
        assertThat(track.confirmedTrack()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_location_point WHERE location_session_id=? AND route_path IS NOT NULL",
                Integer.class, bin(old.locationSessionId()))).isZero();
    }

    @Test
    void closedSessionAcceptsItsOwnLatePointsWithoutRelabelingReopenedSession() {
        var location = new LocationEvidence(new BigDecimal("120.1"), new BigDecimal("30.2"), new BigDecimal("10"), "APP_NATIVE");
        var first = attendanceService.checkIn(new CheckInCommand("late-in", "client", Instant.now(), location, null, null));
        var observed = Instant.now();
        attendanceService.checkOut(first.id(), new CheckOutCommand("late-out", Instant.now(), location, null, null));
        var batch = new LocationBatchCommand("late-points", List.of(new LocationPointCommand("late-event", location.longitude(), location.latitude(), location.accuracyMeters(), observed, "APP_NATIVE", new BigDecimal("120.095"), new BigDecimal("30.202"), "WGS84")), first.locationSessionId());
        assertThat(attendanceService.uploadLocationPoints(first.id(), batch).acceptedCount()).isEqualTo(1);
        var original=jdbc.queryForMap("SELECT raw_longitude,raw_latitude,raw_coordinate_system FROM sales_location_point WHERE tenant_id=? AND device_event_id='late-event'",bin(tenantId));
        assertThat((BigDecimal)original.get("raw_longitude")).isEqualByComparingTo("120.095");
        assertThat(original.get("raw_coordinate_system")).isEqualTo("WGS84");
        assertThat(attendanceService.uploadLocationPoints(first.id(), batch).acceptedCount()).isEqualTo(1);
        var reopened = attendanceService.resumeWork(first.id(), new ResumeWorkCommand("late-reopen", "client", Instant.now(), location, null, null, "补充拜访"));
        assertThat(reopened.locationSessionId()).isNotEqualTo(first.locationSessionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_location_point WHERE tenant_id=? AND location_session_id=?", Integer.class, bin(tenantId), bin(first.locationSessionId()))).isEqualTo(1);
        var invalid = new LocationBatchCommand("late-invalid", List.of(new LocationPointCommand("outside", location.longitude(), location.latitude(), location.accuracyMeters(), observed.plusSeconds(3600), "APP_NATIVE")), first.locationSessionId());
        assertThatThrownBy(() -> attendanceService.uploadLocationPoints(first.id(), invalid)).hasMessageContaining("不在原作业时段");
    }

    @Test
    void dailyReadModelIsVersionedAndUsesTheSameScopeForRowsAndCounts() {
        var location = new LocationEvidence(new BigDecimal("120.1"),new BigDecimal("30.2"),new BigDecimal("10"),"APP_NATIVE");
        var day = attendanceService.checkIn(new CheckInCommand("metrics-in","client",Instant.now(),location,null,null));
        var all = new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(true,Set.of());
        assertThat(fieldwork.page(tenantId,day.businessDate(),all,null,null,20,0)).hasSize(1);
        assertThat(fieldwork.page(tenantId,day.businessDate(),all,null,null,20,0).getFirst().summaryPending()).isTrue();
        metricsWorker.refresh();
        var row=fieldwork.page(tenantId,day.businessDate(),all,null,null,20,0).getFirst();
        assertThat(row.summaryPending()).isFalse();assertThat(row.distanceMeters()).isNull();
        var none = new com.rigour.sales.application.port.out.SalesManagementDirectory.Access(false,Set.of("another-department"));
        assertThat(fieldwork.totals(tenantId,day.businessDate(),none,null,null).total()).isZero();
        assertThat(fieldwork.page(tenantId,day.businessDate(),none,null,null,20,0)).isEmpty();
        assertThat(fieldwork.totals(tenantId,day.businessDate(),all,"NOT_STARTED",null).total()).isZero();
    }

    @Test
    void trackOfUnknownDateIsRejected() {
        assertThatThrownBy(() -> attendanceService.track(LocalDate.now().minusDays(30)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
    }

    private void setCaller() {
        try {
            CallerIdentity caller = new CallerIdentity("TENANT", userId, tenantId, userId, null,
                    UUID.randomUUID(), 1, 1, 1, Set.of("SALES"), Set.of(
                    "sales:context:read", "sales:work-day:write", "sales:location:write",
                    "sales:track:own:read"));
            Method set = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
            set.setAccessible(true);
            set.invoke(null, caller);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法设置测试调用人", error);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static byte[] bin(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static String utcJdbcUrl(MySQLContainer container) {
        return container.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
