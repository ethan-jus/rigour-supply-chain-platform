package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.*;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 真实 MySQL 与认证/CSRF 过滤器验证音频全哈希、可见关系、快照复核与可恢复历史登记，数据全部合成。 */
@Testcontainers(disabledWithoutDocker=true)
class TemporaryCheckinRiskIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work").withUsername("risk_test").withPassword("risk_test_password");
    static final UUID TENANT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID OTHER=UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID A=UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID B=UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final UUID C=UUID.fromString("20000000-0000-0000-0000-000000000003");
    static final UUID OA=UUID.fromString("20000000-0000-0000-0000-000000000004");
    static final UUID STORE=UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID OTHER_STORE=UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final String DEVICE="b".repeat(64),SHA="a".repeat(64);
    static final Instant NOW=Instant.parse("2026-09-08T02:00:00Z");
    static final AdminScope GLOBAL=new AdminScope(UUID.randomUUID(),"risk-global",null);
    static final AdminScope CITY=new AdminScope(UUID.randomUUID(),"risk-city","北京");
    JdbcTemplate jdbc;TemporaryCheckinRiskRepository repository;TemporaryCheckinRiskService service;MockMvc mvc;
    final ObjectMapper json=new ObjectMapper();

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).target("23").load().migrate();
        var old=new TemporaryCheckinRiskIntegrationTest();
        old.jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        old.person(TENANT,A,"销售甲","北京");old.store(TENANT,STORE);
        UUID legacy=old.submission(TENANT,A,"北京",NOW,DEVICE);old.audio(legacy,SHA,100,1000L,false);
        old.jdbc.update("UPDATE temp_sales_checkin_submission SET audio_segments_json=JSON_ARRAY() WHERE id=?",bin(legacy));
        UUID projected=old.submission(TENANT,A,"北京",NOW.plusSeconds(1),DEVICE);old.audio(projected,SHA,100,1000L,false);
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).load().migrate();
        assertThat(old.jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_risk_device",Long.class)).isEqualTo(1);
        assertThat(old.jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_risk_audio",Long.class)).isEqualTo(1);
        assertThat(old.jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_risk_audio_source",Long.class)).isEqualTo(2);
        assertThat(old.jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_identity_event",Long.class)).isZero();
    }
    @BeforeEach void prepare() {
        var data=new DriverManagerDataSource(MYSQL.getJdbcUrl()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(data);
        for(String table:List.of("temp_sales_checkin_identity_event","temp_sales_checkin_risk_review_member","temp_sales_checkin_risk_review","temp_sales_checkin_risk_assignment",
                "temp_sales_checkin_risk_audio","temp_sales_checkin_risk_device","temp_sales_checkin_submission","temp_sales_checkin_store","temp_sales_checkin_salesperson")) jdbc.update("DELETE FROM "+table);
        person(TENANT,A,"销售甲","北京");person(TENANT,B,"销售乙","北京");person(TENANT,C,"销售丙","深圳");person(OTHER,OA,"他租户销售","北京");
        store(TENANT,STORE);store(OTHER,OTHER_STORE);
        var properties=new TemporaryCheckinProperties();properties.setTenantId(TENANT.toString());
        repository=new TemporaryCheckinRiskRepository(jdbc);
        service=new TemporaryCheckinRiskService(repository,properties,Clock.fixed(NOW,ZoneOffset.UTC),new DataSourceTransactionManager(data));
        var auth=mock(TemporaryCheckinAdminAuthService.class);var authProperties=new TemporaryCheckinAdminAuthProperties();
        when(auth.authenticate("global")).thenReturn(principal(GLOBAL));when(auth.authenticate("city")).thenReturn(principal(CITY));
        mvc=MockMvcBuilders.standaloneSetup(new TemporaryCheckinRiskController(service,new TemporaryCheckinAdminAccessPolicy()))
                .setControllerAdvice(new TemporaryCheckinExceptionHandler())
                .addFilters(new TemporaryCheckinAdminAuthenticationFilter(auth,authProperties,json)).build();
    }

    @Test void exactFileMatchesAllActiveSegmentsWithoutCountingLegacyProjectionAndRespectsScope() throws Exception {
        UUID first=submission(TENANT,A,"北京",NOW.minusSeconds(90000),DEVICE),second=submission(TENANT,B,"北京",NOW,DEVICE),third=submission(TENANT,C,"深圳",NOW.plusSeconds(1),DEVICE);
        UUID legacy=audio(first,SHA,900,605077L,true),segment=audio(second,SHA,900,605077L,true);audio(third,SHA,900,605077L,true);
        UUID draft=submission(TENANT,A,"北京",NOW,DEVICE);audio(draft,SHA,900,605077L,true);jdbc.update("UPDATE temp_sales_checkin_submission SET status='DRAFT',submitted_at=NULL WHERE id=?",bin(draft));
        UUID hidden=submission(OTHER,OA,"北京",NOW,DEVICE);audio(hidden,SHA,900,605077L,true);
        UUID otherSize=submission(TENANT,A,"北京",NOW,"c".repeat(64));audio(otherSize,SHA,901,605077L,true);
        UUID otherHash=submission(TENANT,A,"北京",NOW,"d".repeat(64));audio(otherHash,"a".repeat(16)+"e".repeat(48),900,605077L,true);
        UUID removed=submission(TENANT,A,"北京",NOW,DEVICE);UUID removedSegment=audio(removed,SHA,900,605077L,true);
        jdbc.update("UPDATE temp_sales_checkin_submission SET audio_segments_json=JSON_SET(audio_segments_json,'$[0].deletedAt','2026-09-08T03:00:00Z') WHERE id=?",bin(removed));
        service.ensureRegistry();
        var all=service.summariesPrepared(GLOBAL,List.of(second),null,null,null,null).items().getFirst();
        assertThat(all.audios()).hasSize(1);assertThat(all.audios().getFirst().historyCount()).isEqualTo(3);
        assertThat(all.audios().getFirst().otherCount()).isEqualTo(2);assertThat(all.audios().getFirst().earlierCount()).isEqualTo(1);
        assertThat(all.audios().getFirst().salespersonCount()).isEqualTo(3);assertThat(all.riskLevel()).isEqualTo("HIGH");
        assertThat(all.riskReasons()).containsExactly("SHARED_DEVICE","AUDIO_DUPLICATE","AUDIO_CROSS_SALES","AUDIO_CROSS_DATE");
        var scoped=service.summariesPrepared(CITY,List.of(first,second,third,hidden,draft),null,null,null,A).items();
        assertThat(scoped).hasSize(2);assertThat(scoped.getFirst().audios().getFirst().historyCount()).isEqualTo(2);
        assertThat(scoped.getFirst().audios().getFirst().filterCount()).isEqualTo(1);
        var details=service.detail(CITY,"AUDIO",Long.parseLong(all.audios().getFirst().id()),null,null,null,null,0,20,"HISTORY",second);
        assertThat(details.salespeople()).extracting(SalespersonSummary::salespersonName).containsExactly("销售甲","销售乙");
        assertThat(details.visits().items()).flatExtracting(Visit::audios).extracting(AudioReference::segmentId).containsExactlyInAnyOrder(legacy.toString(),segment.toString());
        assertThat(details.visits().items()).flatExtracting(Visit::audios).allSatisfy(a->assertThat(a.originalUrl()).startsWith("/sales-checkin/admin/submissions/"));
        assertThat(details.sha256()).isEqualTo(SHA);
        mvc.perform(get("/sales-checkin/admin/api/v1/risk/summaries").cookie(cookie("city")).param("submissionIds",first+","+second))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(DEVICE))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SHA))));
    }

    @Test void filteredTimelinePaginatesFullHistoryAndShanghaiDatesWithoutChangingAssociationCounts() {
        UUID old=submission(TENANT,A,"北京",Instant.parse("2026-09-07T15:59:59Z"),DEVICE);audio(old,SHA,100,700000L,false);
        UUID one=submission(TENANT,A,"北京",Instant.parse("2026-09-07T16:00:00Z"),DEVICE);audio(one,SHA,100,700000L,false);
        UUID two=submission(TENANT,B,"北京",Instant.parse("2026-09-07T16:00:01Z"),DEVICE);audio(two,SHA,100,700000L,false);
        service.ensureRegistry();long id=Long.parseLong(service.summariesPrepared(GLOBAL,List.of(one),null,null,null,null).items().getFirst().device().id());
        var selected=service.detail(GLOBAL,"DEVICE",id,LocalDate.of(2026,9,8),LocalDate.of(2026,9,8),null,null,0,1,"FILTERED",null);
        assertThat(selected.summary().historyCount()).isEqualTo(3);assertThat(selected.summary().filterCount()).isEqualTo(2);
        assertThat(selected.visits().totalElements()).isEqualTo(2);assertThat(selected.visits().items().getFirst().submissionId()).isEqualTo(two);
        assertThat(service.detail(GLOBAL,"DEVICE",id,LocalDate.of(2026,9,8),LocalDate.of(2026,9,8),null,null,1,1,"FILTERED",null)
                .visits().items().getFirst().submissionId()).isEqualTo(one);
        assertThat(service.detail(GLOBAL,"DEVICE",id,null,null,null,A,0,20,"HISTORY",null).summary().historyCount()).isEqualTo(3);
        assertThatThrownBy(()->service.detail(CITY,"DEVICE",id,null,null,"深圳",null,0,20,"HISTORY",null)).isInstanceOf(TemporaryCheckinException.class);
    }

    @Test void reviewIsIndependentIdempotentVersionedAndNewLinksBecomePendingAgain() {
        UUID first=submission(TENANT,A,"北京",NOW,DEVICE),second=submission(TENANT,B,"北京",NOW.plusSeconds(1),DEVICE);
        audio(first,SHA,100,700000L,true);audio(second,SHA,100,700000L,true);service.ensureRegistry();
        GroupSummary group=service.summariesPrepared(GLOBAL,List.of(first),null,null,null,null).items().getFirst().audios().getFirst();long id=Long.parseLong(group.id());
        var request=new ReviewRequest(UUID.randomUUID(),group.evidenceVersion(),"EXPLAINED","合成共访证据");
        var event=service.review(GLOBAL,"AUDIO",id,request);assertThat(service.review(GLOBAL,"AUDIO",id,request)).isEqualTo(event);
        assertThat(service.detail(GLOBAL,"AUDIO",id,null,null,null,null,0,20,"HISTORY",null).summary().reviewStatus()).isEqualTo("EXPLAINED");
        assertThat(service.list(GLOBAL,"AUDIO",null,null,null,null,null,false,false,"EXPLAINED",null,null,0,20).totalElements()).isEqualTo(1);
        assertThat(service.list(GLOBAL,"AUDIO",null,null,null,null,null,false,false,"PENDING",null,null,0,20).totalElements()).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT review_status FROM temp_sales_checkin_submission WHERE id=?",String.class,bin(first))).isEqualTo("PENDING");
        assertThat(service.detail(CITY,"AUDIO",id,null,null,null,null,0,20,"HISTORY",null).summary().reviewStatus()).isEqualTo("PENDING");
        UUID third=submission(TENANT,C,"深圳",NOW.plusSeconds(2),DEVICE);audio(third,SHA,100,700000L,true);service.ensureRegistry();
        var changed=service.detail(GLOBAL,"AUDIO",id,null,null,null,null,0,20,"HISTORY",null);
        assertThat(changed.summary().newEvidence()).isTrue();assertThat(changed.summary().reviewStatus()).isEqualTo("PENDING");assertThat(changed.reviews()).hasSize(1);
        assertThat(service.list(GLOBAL,"AUDIO",null,null,null,null,null,false,false,"EXPLAINED",null,null,0,20).totalElements()).isEqualTo(0);
        assertThat(service.list(GLOBAL,"AUDIO",null,null,null,null,null,false,false,"PENDING",null,null,0,20).totalElements()).isEqualTo(1);
        assertThat(service.review(GLOBAL,"AUDIO",id,request)).isEqualTo(event);
        assertThatThrownBy(()->service.review(GLOBAL,"AUDIO",id,new ReviewRequest(UUID.randomUUID(),group.evidenceVersion(),"FLAGGED","旧证据不覆盖新关联")))
                .isInstanceOf(TemporaryCheckinException.class).hasMessageContaining("关联证据已变化");
        var newer=service.review(GLOBAL,"AUDIO",id,new ReviewRequest(UUID.randomUUID(),changed.summary().evidenceVersion(),"INCONCLUSIVE","需要进一步核实"));
        assertThat(newer.reviewedAt()).isAfter(event.reviewedAt());
        assertThat(service.detail(GLOBAL,"AUDIO",id,null,null,null,null,0,20,"HISTORY",null).summary().reviewStatus()).isEqualTo("INCONCLUSIVE");
    }

    @Test void assignmentKeepsAppendOnlyEvidenceScopesAndConcurrentIdempotence() {
        UUID first=submission(TENANT,A,"北京",NOW,DEVICE),second=submission(TENANT,B,"北京",NOW.plusSeconds(1),DEVICE);service.ensureRegistry();
        long id=Long.parseLong(service.summariesPrepared(GLOBAL,List.of(first),null,null,null,null).items().getFirst().device().id());
        var request=new AssignmentRequest(UUID.randomUUID(),"PERSONAL",A,LocalDate.of(2026,9,1),null,"合成保管交接依据");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var left=CompletableFuture.supplyAsync(()->service.assign(CITY,id,request),executor);
            var right=CompletableFuture.supplyAsync(()->service.assign(CITY,id,request),executor);
            assertThat(left.join()).isEqualTo(right.join());
        }
        service.assign(GLOBAL,id,new AssignmentRequest(UUID.randomUUID(),"SHARED",null,null,null,"总部共用说明"));
        assertThat(service.assignments(GLOBAL,id,0,20).totalElements()).isEqualTo(2);
        assertThat(service.assignments(CITY,id,0,20).items()).hasSize(1).allSatisfy(a->assertThat(a.scopeCity()).isEqualTo("北京"));
        var readOnly=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        readOnly.setReadOnly(true);
        var evidence=readOnly.execute(tx->service.exportEvidencePrepared(CITY,List.of(first),null,null,null,A));
        assertThat(evidence.summaries().items()).hasSize(1);
        assertThat(evidence.groupSummaries().values()).singleElement().satisfies(g->{assertThat(g.historyCount()).isEqualTo(2);assertThat(g.filterCount()).isEqualTo(1);});
        assertThat(evidence.members().values()).singleElement().satisfies(m->assertThat(m).hasSize(2));
        assertThat(evidence.latestAssignments().get(id).assignmentType()).isEqualTo("PERSONAL");
        assertThat(service.exportEvidencePrepared(GLOBAL,List.of(first),null,null,null,A).latestAssignments().get(id).assignmentType()).isEqualTo("SHARED");
        assertThatThrownBy(()->service.assign(CITY,id,new AssignmentRequest(UUID.randomUUID(),"PERSONAL",C,null,null,"不可跨城指定")))
                .isInstanceOf(TemporaryCheckinException.class);
        assertThatThrownBy(()->service.assign(CITY,id,new AssignmentRequest(request.clientEventId(),"SHARED",null,null,null,"同编号改请求")))
                .isInstanceOf(TemporaryCheckinException.class).hasMessageContaining("内容不同");
        assertThat(service.detail(GLOBAL,"DEVICE",id,null,null,null,null,0,20,"HISTORY",null).summary().firstSalespersonName()).isEqualTo("销售甲");
    }

    @Test void realAuthFilterRejectsAnonymousCsrfAndHiddenGroupsWithoutExposingKeys() throws Exception {
        UUID first=submission(TENANT,C,"深圳",NOW,DEVICE);service.ensureRegistry();
        long id=Long.parseLong(service.summariesPrepared(GLOBAL,List.of(first),null,null,null,null).items().getFirst().device().id());
        String path="/sales-checkin/admin/api/v1/risk/devices/"+id;
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(cookie("city"))).andExpect(status().isNotFound());
        mvc.perform(get(path).cookie(cookie("global"))).andExpect(status().isOk()).andExpect(jsonPath("$.summary.reviewStatus").value("NOT_REQUIRED"));
        String body=json.writeValueAsString(new AssignmentRequest(UUID.randomUUID(),"SHARED",null,null,null,"有依据的测试说明"));
        mvc.perform(post(path+"/assignments").cookie(cookie("global")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/assignments").cookie(cookie("global")).header("X-CSRF-Token","test-csrf").contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post(path+"/assignments").cookie(cookie("global")).header("X-CSRF-Token","test-csrf").contentType("application/json")
                .content(json.writeValueAsString(new AssignmentRequest(UUID.randomUUID(),"SHARED",null,null,null," ")))).andExpect(status().isBadRequest());
        mvc.perform(get("/sales-checkin/admin/api/v1/risk/summaries").cookie(cookie("global")).param("submissionIds",String.join(",",java.util.Collections.nCopies(101,first.toString())))).andExpect(status().isBadRequest());
    }

    @Test void registryDoesNotBecomeHistoricalTruthAndDurationsRemainHonest() {
        UUID first=submission(TENANT,A,"北京",NOW,DEVICE),second=submission(TENANT,B,"北京",NOW.plusSeconds(1),DEVICE);
        UUID segment=audio(first,SHA,100,1000L,false);audio(second,SHA,100,2000L,true);service.ensureRegistry();
        GroupSummary initial=service.summariesPrepared(GLOBAL,List.of(first),null,null,null,null).items().getFirst().audios().getFirst();long id=Long.parseLong(initial.id());
        assertThat(initial.durationMs()).isNull();assertThat(initial.durationSource()).isEqualTo("UNKNOWN");
        assertThat(service.detail(GLOBAL,"AUDIO",id,null,null,null,null,0,20,"HISTORY",null).firstReceivedSource()).isEqualTo("UNKNOWN");
        jdbc.update("INSERT INTO temp_sales_checkin_media_derivative(id,tenant_id,submission_id,media_id,kind,source_object_key,source_sha256,source_size_bytes,status,duration_ms,created_at,updated_at) VALUES(?,?,?,?, 'AUDIO','fixture/audio',?,100,'READY',1234,?,?)",
                bin(UUID.randomUUID()),bin(TENANT),bin(first),segment.toString(),SHA,utc(NOW),utc(NOW));
        assertThat(service.summariesPrepared(GLOBAL,List.of(first),null,null,null,null).items().getFirst().audios().getFirst().durationMs()).isEqualTo(1234);
        jdbc.update("UPDATE temp_sales_checkin_submission SET status='DRAFT',submitted_at=NULL WHERE id=?",bin(second));
        assertThat(service.detail(GLOBAL,"AUDIO",id,null,null,null,null,0,20,"HISTORY",null).summary().historyCount()).isEqualTo(1);
        assertThat(service.detail(GLOBAL,"AUDIO",id,null,null,null,null,0,20,"HISTORY",null).summary().reviewStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_risk_audio WHERE id=?",Long.class,id)).isEqualTo(1);
    }

    @Test void sharedMainListSqlAgreesWithScopeSpecificReviewAndPreservesOriginalHighRisk() {
        UUID first=submission(TENANT,A,"北京",NOW,DEVICE),second=submission(TENANT,B,"北京",NOW.plusSeconds(1),DEVICE);
        UUID third=submission(TENANT,C,"深圳",NOW.plusSeconds(2),DEVICE);service.ensureRegistry();
        var originals=new TemporaryCheckinRepository(jdbc);
        var pending=new TemporaryCheckinRepository.AdminReadOptions(null,null,null,null,null,null,List.of(),null,null,null,"PENDING",null);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,"SUBMITTED",null,null,pending).total()).isEqualTo(3);
        var group=service.summariesPrepared(GLOBAL,List.of(first),null,null,null,null).items().getFirst().device();
        service.review(GLOBAL,"DEVICE",Long.parseLong(group.id()),new ReviewRequest(UUID.randomUUID(),group.evidenceVersion(),"EXPLAINED","总部完整关联已说明"));
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,"SUBMITTED",null,null,pending).total()).isEqualTo(0);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,"北京",null,"SUBMITTED",null,null,pending.withScope("北京")).total()).isEqualTo(2);
        var explained=new TemporaryCheckinRepository.AdminReadOptions(null,null,null,null,null,null,List.of(),null,null,null,"EXPLAINED",null);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,"SUBMITTED",null,null,explained).total()).isEqualTo(3);
        UUID old=submission(TENANT,A,"北京",NOW.plusSeconds(4),null);
        jdbc.update("UPDATE temp_sales_checkin_submission SET risk_level='HIGH' WHERE id=?",bin(old));
        assertThat(service.summariesPrepared(GLOBAL,List.of(old),null,null,null,null).items().getFirst().riskLevel()).isEqualTo("HIGH");
        var high=new TemporaryCheckinRepository.AdminReadOptions(null,null,null,null,null,"HIGH",List.of(),null,null,null,null,null);
        assertThat(originals.adminSubmissionStats(TENANT,null,null,null,null,"SUBMITTED",null,null,high).total()).isEqualTo(4);
    }

    @Test void sixThousandSyntheticVisitsHaveStableCodesAndBoundedBatchQueriesWithRealExplain() throws Exception {
        UUID source=submission(TENANT,A,"北京",NOW,DEVICE);
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission(id,tenant_id,client_submission_id,submission_key_hash,status,city,
                  salesperson_id,salesperson_name_snapshot,store_id,store_name_snapshot,customer_name,visit_result,
                  privacy_accepted,created_at,updated_at,submitted_at,device_token_hash,audio_segments_json,
                  storefront_photo_object_key,storefront_photo_content_type,storefront_photo_size_bytes,storefront_photo_sha256,storefront_photo_original_filename)
                SELECT UUID_TO_BIN(UUID()),s.tenant_id,UUID_TO_BIN(UUID()),s.submission_key_hash,'SUBMITTED',s.city,
                  IF(MOD(n.n,2)=0,?,?),s.salesperson_name_snapshot,s.store_id,s.store_name_snapshot,s.customer_name,s.visit_result,
                  0,s.created_at,s.updated_at,TIMESTAMPADD(SECOND,n.n,s.submitted_at),LPAD(MOD(n.n,20),64,'b'),
                  JSON_ARRAY(JSON_OBJECT('segmentId',UUID(),'objectKey','fixture/audio','sha256',LPAD(MOD(n.n,40),64,'a'),
                    'sizeBytes',1024,'originalFilename','合成测试.wav','contentType','audio/wav','clientDurationMs',1000,
                    'captureSource','BROWSER_RECORDER','uploadedAt','2026-09-08T02:00:00Z')),
                  s.storefront_photo_object_key,s.storefront_photo_content_type,s.storefront_photo_size_bytes,s.storefront_photo_sha256,s.storefront_photo_original_filename
                FROM temp_sales_checkin_submission s CROSS JOIN (
                  SELECT x.n+20*y.n+400*z.n n FROM JSON_TABLE('[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19]','$[*]' COLUMNS(n INT PATH '$'))x
                  CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19]','$[*]' COLUMNS(n INT PATH '$'))y
                  CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14]','$[*]' COLUMNS(n INT PATH '$'))z
                )n WHERE s.id=?
                """,bin(A),bin(B),bin(source));
        long started=System.nanoTime();service.ensureRegistry();long registerMs=(System.nanoTime()-started)/1_000_000;
        var ids=jdbc.query("SELECT id FROM temp_sales_checkin_submission WHERE tenant_id=? ORDER BY submitted_at DESC LIMIT 100",(rs,n)->SalesUuidCodec.decode(rs.getBytes(1)),bin(TENANT));
        started=System.nanoTime();var batch=service.summariesPrepared(GLOBAL,ids,null,null,null,null);long batchMs=(System.nanoTime()-started)/1_000_000;
        assertThat(batch.items()).hasSize(100);assertThat(batch.items()).allSatisfy(s->assertThat(s.audios().getFirst().historyCount()).isEqualTo(150));
        var codes=batch.items().stream().map(s->s.device().code()).toList();service.ensureRegistry();
        assertThat(service.summariesPrepared(GLOBAL,ids,null,null,null,null).items().stream().map(s->s.device().code()).toList()).isEqualTo(codes);
        var allIds=jdbc.query("SELECT id FROM temp_sales_checkin_submission WHERE tenant_id=? ORDER BY submitted_at",(rs,n)->SalesUuidCodec.decode(rs.getBytes(1)),bin(TENANT));
        started=System.nanoTime();var exported=service.summariesForExportPrepared(GLOBAL,allIds,null,null,null,null);long exportMs=(System.nanoTime()-started)/1_000_000;
        assertThat(exported.items()).hasSize(6001);assertThat(exportMs).isLessThan(10000);
        assertThatThrownBy(()->service.summariesForExportPrepared(GLOBAL,java.util.Collections.nCopies(20001,source),null,null,null,null))
                .isInstanceOf(TemporaryCheckinException.class).hasMessageContaining("20000");
        started=System.nanoTime();var page=service.list(GLOBAL,"AUDIO",null,null,null,null,null,true,false,"historyCount","desc",0,20);long pageMs=(System.nanoTime()-started)/1_000_000;
        assertThat(page.totalElements()).isEqualTo(40);assertThat(page.items()).hasSize(20);
        String explain=jdbc.queryForObject("EXPLAIN FORMAT=JSON SELECT COUNT(*) FROM temp_sales_checkin_risk_audio_source a WHERE a.tenant_id=?",String.class,bin(TENANT));
        assertThat(explain).contains("query_block");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/risk-query-explain.json"),explain);
        System.out.printf("RISK_SYNTHETIC_PERF rows=6001 register_ms=%d batch100_ms=%d export6001_ms=%d audio_page20_ms=%d explain_bytes=%d%n",registerMs,batchMs,exportMs,pageMs,explain.length());
        assertThat(batchMs).isLessThan(10000);assertThat(pageMs).isLessThan(10000);
    }

    @Test void identityEventsApplyTenantAndCityBeforeCountsAndPreviousAccountWithoutInventingHistory() throws Exception {
        UUID visit=submission(TENANT,A,"北京",NOW,DEVICE);
        UUID hiddenVisit=submission(TENANT,C,"深圳",NOW,"c".repeat(64));
        service.ensureRegistry();
        long id=Long.parseLong(service.summariesPrepared(GLOBAL,List.of(visit),null,null,null,null).items().getFirst().device().id());
        var empty=service.identityEvents(CITY,id,false,0,20);
        assertThat(empty.availableEventCount()).isZero();assertThat(empty.totalElements()).isZero();
        assertThat(empty.firstAvailableEventAt()).isNull();assertThat(empty.completeHistory()).isFalse();
        identityEvent(TENANT,A,"北京",DEVICE,NOW.minusSeconds(4));
        identityEvent(TENANT,C,"深圳",DEVICE,NOW.minusSeconds(3));
        identityEvent(OTHER,OA,"北京",DEVICE,NOW.minusSeconds(2));
        identityEvent(TENANT,B,"北京",DEVICE,NOW.minusSeconds(1));
        identityEvent(TENANT,B,"北京",DEVICE,NOW);
        var city=service.identityEvents(CITY,id,false,0,2);
        assertThat(city.availableEventCount()).isEqualTo(3);assertThat(city.totalElements()).isEqualTo(3);assertThat(city.totalPages()).isEqualTo(2);
        assertThat(city.firstAvailableEventAt()).isEqualTo(NOW.minusSeconds(4));assertThat(city.completeHistory()).isFalse();
        assertThat(city.items()).extracting(IdentityEvent::eventType).containsExactly("IDENTITY_VERIFIED","VISIBLE_ACCOUNT_CHANGED");
        assertThat(city.items().get(1).previousSalespersonId()).isEqualTo(A);
        assertThat(city.items().get(1).previousSalespersonName()).isEqualTo("销售甲");
        assertThat(city.items().get(1).previousOccurredAt()).isEqualTo(NOW.minusSeconds(4));
        var last=service.identityEvents(CITY,id,false,1,2);
        assertThat(last.items()).singleElement().satisfies(e->{assertThat(e.salespersonId()).isEqualTo(A);assertThat(e.previousSalespersonId()).isNull();assertThat(e.previousEventId()).isNull();});
        var changes=service.identityEvents(CITY,id,true,0,20);
        assertThat(changes.totalElements()).isEqualTo(1);assertThat(changes.availableEventCount()).isEqualTo(3);
        assertThat(changes.items()).extracting(IdentityEvent::salespersonId).containsExactly(B);
        var all=service.identityEvents(GLOBAL,id,true,0,20);
        assertThat(all.availableEventCount()).isEqualTo(4);assertThat(all.totalElements()).isEqualTo(2);
        assertThat(all.items()).extracting(IdentityEvent::salespersonId).containsExactly(B,C);
        assertThat(all.items().getFirst().previousSalespersonId()).isEqualTo(C);
        String path="/sales-checkin/admin/api/v1/risk/devices/"+id+"/identity-events";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(cookie("city")).param("salespersonChangesOnly","true"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.completeHistory").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("销售丙"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(DEVICE))));
        long hiddenId=Long.parseLong(service.summariesPrepared(GLOBAL,List.of(hiddenVisit),null,null,null,null).items().getFirst().device().id());
        identityEvent(TENANT,A,"北京","c".repeat(64),NOW);
        mvc.perform(get("/sales-checkin/admin/api/v1/risk/devices/"+hiddenId+"/identity-events").cookie(cookie("city")))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE temp_sales_checkin_submission SET deletion_state='PENDING' WHERE id=?",bin(visit));
        assertThatThrownBy(()->service.identityEvents(CITY,id,false,0,20)).isInstanceOf(TemporaryCheckinException.class).hasMessageContaining("不存在");
    }

    @Test void visitAccountChangesAreComputedBeforeDateSalesFiltersAndPaginationButAfterVisibility() throws Exception {
        UUID first=submission(TENANT,A,"北京",Instant.parse("2026-09-07T15:59:59Z"),DEVICE);
        UUID changed=submission(TENANT,B,"北京",Instant.parse("2026-09-07T16:00:00Z"),DEVICE);
        UUID same=submission(TENANT,B,"北京",Instant.parse("2026-09-07T16:00:01Z"),DEVICE);
        submission(TENANT,C,"深圳",Instant.parse("2026-09-07T16:00:02Z"),DEVICE);
        UUID afterHidden=submission(TENANT,B,"北京",Instant.parse("2026-09-07T16:00:03Z"),DEVICE);
        UUID changedBack=submission(TENANT,A,"北京",Instant.parse("2026-09-07T16:00:04Z"),DEVICE);
        service.ensureRegistry();long id=Long.parseLong(service.summariesPrepared(GLOBAL,List.of(first),null,null,null,null).items().getFirst().device().id());
        var date=LocalDate.of(2026,9,8);
        var one=service.detail(CITY,"DEVICE",id,date,date,null,null,0,1,"FILTERED",null,true);
        assertThat(one.summary().historyCount()).isEqualTo(5);assertThat(one.summary().filterCount()).isEqualTo(4);
        assertThat(one.visits().totalElements()).isEqualTo(2);assertThat(one.visits().items()).extracting(Visit::submissionId).containsExactly(changedBack);
        assertThat(service.detail(CITY,"DEVICE",id,date,date,null,null,1,1,"FILTERED",null,true).visits().items())
                .extracting(Visit::submissionId).containsExactly(changed);
        assertThat(service.detail(CITY,"DEVICE",id,date,date,null,B,0,20,"FILTERED",null,true).visits().items())
                .extracting(Visit::submissionId).containsExactly(changed);
        assertThat(service.detail(CITY,"DEVICE",id,date,date,null,null,0,20,"HISTORY",null,false).visits().items())
                .extracting(Visit::submissionId).containsExactly(changedBack,afterHidden,same,changed,first);
        assertThat(service.detail(GLOBAL,"DEVICE",id,date,date,null,null,0,20,"FILTERED",null,true).visits().totalElements()).isEqualTo(4);
        mvc.perform(get("/sales-checkin/admin/api/v1/risk/devices/"+id).cookie(cookie("city"))
                .param("salespersonChangesOnly","true").param("timelineScope","FILTERED")
                .param("from","2026-09-08").param("to","2026-09-08"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visits.totalElements").value(2));
    }

    @Test void asynchronousIdentityRecorderPersistsOnlyMinimalAuthorizedFactsWithServerMicrosecondTime() throws Exception {
        UUID visit=submission(TENANT,A,"北京",NOW,DEVICE);service.ensureRegistry();
        var properties=new TemporaryCheckinProperties();properties.setTenantId(TENANT.toString());
        var person=new TemporaryCheckinRepository(jdbc).findSalesperson(TENANT,A).orElseThrow();
        Instant verified=NOW.plusNanos(123456789);
        try(var recorder=new TemporaryCheckinIdentityEventRecorder(jdbc,properties)) {
            recorder.record(new TemporaryCheckinSalesIdentityService.AuthorizedRequest(person,"PERSONAL_CODE",verified,
                    NOW.plusSeconds(3600),DEVICE,null));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            while(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_identity_event",Long.class)==0&&System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_identity_event",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT event_type FROM temp_sales_checkin_identity_event",String.class)).isEqualTo("PERSONAL_CODE_VERIFIED");
            assertThat(jdbc.queryForObject("SELECT HEX(tenant_id) FROM temp_sales_checkin_identity_event",String.class)).isEqualTo(TENANT.toString().replace("-","").toUpperCase(java.util.Locale.ROOT));
            long id=Long.parseLong(service.summariesPrepared(CITY,List.of(visit),null,null,null,null).items().getFirst().device().id());
            var events=service.identityEvents(CITY,id,false,0,20);
            assertThat(events.items()).singleElement().satisfies(event->{
                assertThat(event.salespersonId()).isEqualTo(A);assertThat(event.city()).isEqualTo("北京");
                assertThat(event.occurredAt()).isEqualTo(verified.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            });
            assertThat(recorder.droppedEvents()).isZero();
            var columns=jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='temp_sales_checkin_identity_event'",String.class);
            assertThat(columns).containsExactlyInAnyOrder("id","event_id","tenant_id","device_token_hash","salesperson_id","salesperson_name_snapshot","identity_city","event_type","occurred_at");
        }
    }

    private void identityEvent(UUID tenant,UUID person,String city,String device,Instant at) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_identity_event(event_id,tenant_id,device_token_hash,salesperson_id,
                    salesperson_name_snapshot,identity_city,event_type,occurred_at)
                VALUES(?,?,?,?,?,?,'PERSONAL_CODE_VERIFIED',?)
                """,bin(UUID.randomUUID()),bin(tenant),device,bin(person),person.equals(A)?"销售甲":person.equals(B)?"销售乙":person.equals(C)?"销售丙":"他租户销售",city,utc(at));
    }

    private UUID submission(UUID tenant,UUID person,String city,Instant at,String device) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission(id,tenant_id,client_submission_id,submission_key_hash,status,city,
                  salesperson_id,salesperson_name_snapshot,store_id,store_name_snapshot,customer_name,visit_result,
                  privacy_accepted,created_at,updated_at,submitted_at,device_token_hash,user_agent_summary,
                  storefront_photo_object_key,storefront_photo_content_type,storefront_photo_size_bytes,storefront_photo_sha256,storefront_photo_original_filename)
                VALUES(?,?,?,?,'SUBMITTED',?,?,?,?,?,'合成客户','合成拜访',0,?,?,?,?,
                  '测试浏览器','fixture/photo','image/jpeg',4,?,'合成照片.jpg')
                """,bin(id),bin(tenant),bin(UUID.randomUUID()),"f".repeat(64),city,bin(person),person.equals(A)?"销售甲":person.equals(B)?"销售乙":person.equals(C)?"销售丙":"他租户销售",
                bin(tenant.equals(TENANT)?STORE:OTHER_STORE),"合成门店",utc(at),utc(at),utc(at),device,"e".repeat(64));return id;
    }
    private UUID audio(UUID submission,String sha,long size,Long clientMs,boolean realSource) {
        UUID segment=UUID.randomUUID();Map<String,Object> data=new java.util.LinkedHashMap<>();
        data.put("segmentId",segment.toString());data.put("objectKey","fixture/audio/"+segment);data.put("sha256",sha);data.put("sizeBytes",size);
        data.put("originalFilename","合成录音.wav");data.put("contentType","audio/wav");data.put("uploadedAt",NOW.toString());data.put("clientDurationMs",clientMs);
        if(realSource)data.put("captureSource","BROWSER_RECORDER");
        jdbc.update("UPDATE temp_sales_checkin_submission SET audio_segments_json=CAST(? AS JSON),audio_object_key=?,audio_sha256=?,audio_size_bytes=?,audio_content_type='audio/wav',audio_original_filename='合成录音.wav' WHERE id=?",
                json.writeValueAsString(List.of(data)),data.get("objectKey"),sha,size,bin(submission));return segment;
    }
    private void person(UUID tenant,UUID id,String name,String city) {
        jdbc.update("INSERT INTO temp_sales_checkin_salesperson(id,tenant_id,name,city,employment_status,status,created_at,updated_at) VALUES(?,?,?,?,'在职','ACTIVE',?,?)",bin(id),bin(tenant),name,city,utc(NOW),utc(NOW));
    }
    private void store(UUID tenant,UUID id) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store(id,tenant_id,client_store_id,city,attribute,name,operating_status,contact_name,
                  area_range,facility_count,business_types_json,intended_businesses_json,cooperation_intent,tags_json,status,created_at,updated_at)
                VALUES(?,?,?,'北京','台球','合成门店','营业中','合成联系人','100平','10',JSON_ARRAY(),JSON_ARRAY(),'待沟通',JSON_ARRAY(),'ACTIVE',?,?)
                """,bin(id),bin(tenant),bin(UUID.randomUUID()),utc(NOW),utc(NOW));
    }
    private static TemporaryCheckinAdminPrincipal principal(AdminScope scope) {
        return new TemporaryCheckinAdminPrincipal(scope.accountId(),UUID.randomUUID(),scope.username(),scope.username(),scope.allCities()?"GLOBAL_ADMIN":"CITY_ADMIN",
                scope.allCities()?null:UUID.randomUUID(),scope.city(),false,"test-csrf");
    }
    private static Cookie cookie(String value) {return new Cookie("__Host-rigour-sales-checkin-admin",value);}
    private static byte[] bin(UUID id) {return SalesUuidCodec.encode(id);}
    private static LocalDateTime utc(Instant at) {return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
}
