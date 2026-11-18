package com.rigour.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CreateVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.PoiTargetCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitResultCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ReviewVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.CancelVisitPlanCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.UpsertVisitPlanCommand;
import com.rigour.sales.application.service.SalesWorkAttendanceService;
import com.rigour.sales.application.service.SalesWorkEvidenceService;
import com.rigour.sales.application.service.SalesWorkRecordingService;
import com.rigour.sales.application.service.SalesWorkManagementService;
import com.rigour.sales.application.service.SalesWorkVisitService;
import com.rigour.sales.application.service.SalesWorkVisitPlanService;
import com.rigour.sales.testing.RecordingMediaSamples;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 隔离MySQL验证拜访闭环：我的门店/POI双来源、到店签到、离店、CRM投影沉淀和幂等。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SalesWorkVisitIntegrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work_visit")
            .withUsername("sales_work_visit_test")
            .withPassword("rigour_sales_work_visit_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> utcJdbcUrl(MYSQL));
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", () -> utcJdbcUrl(MYSQL));
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        // 录音字节写入临时目录，避免测试在工程目录留下文件。
        registry.add("sales.recording.storage-dir",
                () -> System.getProperty("java.io.tmpdir") + "/rigour-visit-test-recordings");
    }

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID fieldPolicyId = UUID.randomUUID();
    private final UUID fieldPolicyVersionId = UUID.randomUUID();
    private final UUID visitPolicyId = UUID.randomUUID();
    private final UUID visitPolicyVersionId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SalesWorkAttendanceService attendanceService;

    @Autowired
    private SalesWorkVisitService visitService;

    @Autowired
    private SalesWorkRecordingService recordingService;

    @Autowired
    private SalesWorkEvidenceService evidenceService;

    @Autowired
    private SalesWorkManagementService managementService;

    @Autowired
    private SalesWorkVisitPlanService visitPlanService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rigour.sales.application.port.out.CrmStoreVisitClient crmVisit;
    @Autowired private com.rigour.sales.application.port.out.StoreVisitCaptureStore captureStore;
    @Autowired private com.rigour.sales.application.port.out.SalesWorkRecordingRepository recordingRows;
    @Autowired private com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository analysisRows;
    @Autowired private com.rigour.sales.application.service.SalesRecordingAnalysisService analysisService;
    @Autowired private com.rigour.sales.application.port.out.SalesFieldworkReviewRepository caseRows;
    @Autowired private com.rigour.sales.application.service.SalesFieldworkReviewService caseService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rigour.sales.application.port.out.SalesManagementDirectory managementDirectory;

    @BeforeEach
    void seed() {
        org.mockito.Mockito.when(managementDirectory.authorization(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    var caller = (CallerIdentity) invocation.getArgument(0);
                    var all = new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit("ALL", java.util.List.of());
                    var clause = new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Clause(UUID.randomUUID(), "EMPLOYEE", "ALL", all, all, all, false);
                    return new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView("ACTIVE", caller.tenantId(), caller.userId(), "TEST-EMPLOYEE", 1,1,1,1,
                            caller.permissions(), invocation.getArgument(1), true, java.util.List.of(clause), all, all);
                });
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
                VALUES (?, ?, ?, 'S-VISIT-001', NULL, 'ACTIVE', UTC_TIMESTAMP(6) - INTERVAL 60 SECOND,
                        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(profileId), bin(tenantId), bin(employeeId));
        seedFieldPolicy(now);
        seedVisitPolicy(now);
        jdbc.update("""
                INSERT INTO crm_store_projection
                    (id, tenant_id, customer_id, store_id, customer_name, store_name,
                     store_address, longitude, latitude, store_status, source_version,
                     source_updated_at, projected_at)
                VALUES (?, ?, ?, ?, '测试客户', '我的测试门店', '测试地址', 120.1000000, 30.2000000,
                        'ACTIVE', 1, ?, ?)
                """, bin(UUID.randomUUID()), bin(tenantId), bin(customerId), bin(storeId),
                timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO crm_sales_assignment_projection
                    (id, tenant_id, sales_profile_id, customer_id, store_id, assignment_type,
                     effective_from, effective_to, status, source_version, source_updated_at, projected_at)
                VALUES (?, ?, ?, ?, ?, 'PRIMARY', UTC_TIMESTAMP(6) - INTERVAL 60 SECOND,
                        NULL, 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(UUID.randomUUID()), bin(tenantId), bin(profileId), bin(customerId), bin(storeId));
        setCaller();
    }

    @AfterEach
    void clearCaller() throws Exception {
        Method clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    @Test
    void employeeStatementsAreAppendOnlyAndReviewUsesCurrentEvidenceVersion() {
        var now=Instant.now();
        var day=attendanceService.checkIn(new CheckInCommand("case-day","client",now,location("120.1000000","30.2000000"),null,"ONLINE"));
        caseRows.detect(tenantId,profileId,day.id(),null,"gap-test","TRACK_GAP","轨迹缺失待核查","{}",now);
        caseRows.detect(tenantId,profileId,day.id(),null,"gap-test","TRACK_GAP","重复任务不应改原材料","{\"changed\":true}",now);
        var original=caseRows.byDay(tenantId,day.id()).getFirst();
        assertThat(original.title()).isEqualTo("轨迹缺失待核查");
        var explained=caseService.statement(original.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Statement("own-1","当时在地下门店，无定位信号"));
        assertThat(explained.version()).isEqualTo(1);
        assertThat(explained.events()).hasSize(1);
        assertThat(caseService.statement(original.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Statement("own-1","当时在地下门店，无定位信号")).events()).hasSize(1);
        assertThatThrownBy(()->caseService.statement(original.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Statement("own-1","改写原文"))).hasMessageContaining("不同内容");
        assertThatThrownBy(()->caseService.review(original.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Review("review-1",0,"CLEARED","核对门头照时间一致"))).hasMessageContaining("新增说明");
        var reviewed=caseService.review(original.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Review("review-1",1,"CLEARED","核对门头照时间一致"));
        assertThat(reviewed.status()).isEqualTo("RESOLVED");
        assertThat(reviewed.events()).hasSize(2);
        assertThat(reviewed.events().getFirst().note()).isEqualTo("当时在地下门店，无定位信号");
        assertThat(caseService.statement(original.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Statement("own-2","补充：定位在出店后恢复")).status()).isEqualTo("EXPLAINED");
        caseRows.detect(tenantId,UUID.randomUUID(),day.id(),null,"foreign-case","DEVICE","设备待核查","{}",now);
        var foreign=caseRows.byDay(tenantId,day.id()).stream().filter(c->c.kind().equals("DEVICE")).findFirst().orElseThrow();
        assertThatThrownBy(()->caseService.statement(foreign.id(),new com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Statement("foreign","无权填写"))).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
    }

    @Test
    void recordingAnalysisKeepsOneJobPerClipAndRejectsExpiredLeaseResults() {
        var now=Instant.now();
        var day=attendanceService.checkIn(new CheckInCommand("asr-day","client",now,location("120.1000000","30.2000000"),null,"ONLINE"));
        var visit=visitService.createVisit(new CreateVisitCommand("asr-visit",day.id(),null,"MY_STORE",storeId,null,
                location("120.1000000","30.2000000"),now,"asr-device"));
        jdbc.update("UPDATE sales_visit SET status='CHECKED_OUT' WHERE id=?",bin(visit.id()));
        var recordingSession=recordingRows.ensureSession(UUID.randomUUID(),tenantId,visit.id(),now);
        var clip=UUID.randomUUID();
        recordingRows.insertClip(clip,tenantId,recordingSession,"asr-clip",1,tenantId+"/asr-test.aac","audio/aac",10,"a".repeat(64),null,
                30000L,30000L,"VERIFIED",now.minusSeconds(30),now,now);
        analysisRows.enqueue(tenantId,visit.id(),clip,now);
        analysisRows.enqueue(tenantId,visit.id(),clip,now);
        assertThat(analysisRows.list(tenantId,visit.id())).hasSize(1);
        var first=analysisRows.claim(now.plusSeconds(1),UUID.randomUUID(),now.plusSeconds(180)).orElseThrow();
        assertThat(analysisRows.claim(now.plusSeconds(1),UUID.randomUUID(),now.plusSeconds(180))).isEmpty();
        var replacement=analysisRows.claim(now.plusSeconds(181),UUID.randomUUID(),now.plusSeconds(400)).orElseThrow();
        analysisRows.transcribed(first,new com.rigour.sales.application.port.out.SalesSpeechTranscriber.Result("过期回调不应写入",List.of(),"test-model","expired","speaker-test",List.of()),now.plusSeconds(182));
        assertThat(analysisRows.list(tenantId,visit.id()).getFirst().transcript()).isNull();
        analysisRows.deferred(replacement,"ASR_UNAVAILABLE",true,now.plusSeconds(183));
        assertThat(analysisRows.list(tenantId,visit.id()).getFirst().status()).isEqualTo("FAILED");
        assertThat(analysisRows.retry(UUID.randomUUID(),visit.id(),clip,now.plusSeconds(184))).isZero();
        assertThat(analysisRows.retry(tenantId,visit.id(),clip,now.plusSeconds(184))).isEqualTo(1);
        var retried=analysisRows.claim(now.plusSeconds(185),UUID.randomUUID(),now.plusSeconds(400)).orElseThrow();
        var segments=List.of(new com.rigour.sales.application.port.out.SalesAudioAiClient.TranscriptSegment(100,1400,"需要更换台呢",null));
        analysisRows.transcribed(retried,new com.rigour.sales.application.port.out.SalesSpeechTranscriber.Result("需要更换台呢",segments,"paraformer-int8","request","speaker-test",List.of()),now.plusSeconds(186));
        var saved=analysisRows.list(tenantId,visit.id()).getFirst();
        assertThat(saved.transcript()).isEqualTo("需要更换台呢");
        assertThat(saved.segments()).isEqualTo(segments);
        assertThat(saved.status()).isEqualTo("SUCCEEDED");
        assertThat(saved.summaryStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(saved.summary()).isNull();
        assertThat(jdbc.queryForObject("SELECT asr_model FROM sales_recording_analysis WHERE tenant_id=? AND clip_id=?",String.class,bin(tenantId),bin(clip))).isEqualTo("paraformer-int8");
        assertThat(analysisRows.list(UUID.randomUUID(),visit.id())).isEmpty();
        assertThat(analysisRows.claim(now.plusSeconds(401),UUID.randomUUID(),now.plusSeconds(600))).isEmpty();
        assertThat(analysisRows.retry(tenantId,visit.id(),clip,now.plusSeconds(402))).isZero();
        var ownResult=analysisService.read(visit.id(),false);
        assertThat(analysisService.read(visit.id(),true)).isEqualTo(ownResult);
        var original=AuthorizationContext.requireCurrent();
        var otherUser=UUID.randomUUID();
        setAnalysisCaller(tenantId,otherUser,original.permissions());
        try {
            // 管理员可查看他人的同一份原文，个人接口不允许越权。
            assertThat(analysisService.read(visit.id(),true)).isEqualTo(ownResult);
            assertThatThrownBy(() -> analysisService.read(visit.id(),false)).isInstanceOf(BusinessException.class);
            setAnalysisCaller(UUID.randomUUID(),otherUser,original.permissions());
            assertThatThrownBy(() -> analysisService.read(visit.id(),true)).isInstanceOf(BusinessException.class);
            setAnalysisCaller(tenantId,otherUser,Set.of("sales:recording:own:read"));
            assertThatThrownBy(() -> analysisService.read(visit.id(),true))
                    .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        } finally {
            setCaller();
        }
        assertThat(analysisRows.list(tenantId,visit.id())).containsExactly(saved);
        assertThat(analysisRows.claim(now.plusSeconds(500),UUID.randomUUID(),now.plusSeconds(700))).isEmpty();

    }

    @Test
    void speakerContextIsVisitScopedOrderedAndCommittedWithTranscript() {
        var now=Instant.now();
        var day=attendanceService.checkIn(new CheckInCommand("speaker-day","client",now,location("120.1000000","30.2000000"),null,"ONLINE"));
        var visit=visitService.createVisit(new CreateVisitCommand("speaker-visit",day.id(),null,"MY_STORE",storeId,null,
                location("120.1000000","30.2000000"),now,"speaker-device"));
        var recordingSession=recordingRows.ensureSession(UUID.randomUUID(),tenantId,visit.id(),now);
        var first=UUID.randomUUID();var second=UUID.randomUUID();
        for(int index=0;index<2;index++) {
            var clip=index==0?first:second;
            recordingRows.insertClip(clip,tenantId,recordingSession,"speaker-"+index,index,tenantId+"/speaker-"+index+".aac","audio/aac",10,
                    String.valueOf(index).repeat(64),null,300000L,300000L,"VERIFIED",now.minusSeconds(600-index*300),now.minusSeconds(300-index*300),now);
        }
        // Upload completion order differs from recording order.
        analysisRows.enqueue(tenantId,visit.id(),second,now);
        analysisRows.enqueue(tenantId,visit.id(),first,now);
        assertThat(analysisRows.claim(now.plusSeconds(1),UUID.randomUUID(),now.plusSeconds(180))).isEmpty();
        jdbc.update("UPDATE sales_visit SET status='CHECKED_OUT' WHERE id=?",bin(visit.id()));
        com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository.Job firstJob;
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var gate=new java.util.concurrent.CountDownLatch(1);
            var futures=java.util.stream.IntStream.range(0,4).mapToObj(index->workers.submit(()->{
                gate.await();return analysisRows.claim(now.plusSeconds(1),UUID.randomUUID(),now.plusSeconds(180));
            })).toList();
            gate.countDown();
            var claimed=new java.util.ArrayList<com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository.Job>();
            for(var future:futures)future.get().ifPresent(claimed::add);
            assertThat(claimed).hasSize(1);firstJob=claimed.getFirst();
        } catch(Exception error) {throw new IllegalStateException(error);}
        assertThat(firstJob.clipId()).isEqualTo(first);
        assertThat(firstJob.speakers()).isEmpty();
        assertThat(analysisRows.claim(now.plusSeconds(2),UUID.randomUUID(),now.plusSeconds(180))).isEmpty();
        var vector=java.util.stream.IntStream.range(0,192).mapToObj(i->i==0?1.0:0.0).toList();
        var profiles=List.of(new com.rigour.sales.application.port.out.SalesSpeechTranscriber.SpeakerProfile(0,vector));
        var lines=List.of(new com.rigour.sales.application.port.out.SalesAudioAiClient.TranscriptSegment(100,1400,"A的原文",0));
        var result=new com.rigour.sales.application.port.out.SalesSpeechTranscriber.Result("A的原文",lines,"paraformer-int8","request","speaker-test",profiles);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(()->tx.execute(status->{analysisRows.transcribed(firstJob,result,now.plusSeconds(3));throw new IllegalStateException("rollback");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM sales_visit_speaker_context WHERE tenant_id=? AND visit_id=?",Long.class,bin(tenantId),bin(visit.id()))).isZero();
        assertThat(analysisRows.list(tenantId,visit.id()).getFirst().transcript()).isNull();
        analysisRows.transcribed(firstJob,result,now.plusSeconds(4));
        var secondJob=analysisRows.claim(now.plusSeconds(5),UUID.randomUUID(),now.plusSeconds(180)).orElseThrow();
        assertThat(secondJob.clipId()).isEqualTo(second);
        assertThat(secondJob.speakerVersion()).isEqualTo(1);
        assertThat(secondJob.speakers()).isEqualTo(profiles);
        analysisRows.transcribed(secondJob,result,now.plusSeconds(6));
        // An old callback cannot roll the visit profiles or either clip back.
        analysisRows.transcribed(firstJob,new com.rigour.sales.application.port.out.SalesSpeechTranscriber.Result("过期",List.of(),"old","old","old",List.of()),now.plusSeconds(7));
        assertThat(analysisRows.list(tenantId,visit.id())).allSatisfy(a->assertThat(a.segments().getFirst().speakerId()).isZero());
        assertThat(jdbc.queryForObject("SELECT version FROM sales_visit_speaker_context WHERE tenant_id=? AND visit_id=?",Long.class,bin(tenantId),bin(visit.id()))).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sales_visit_speaker_context WHERE tenant_id=?",UUID.randomUUID())).isZero();
        var voices=new com.rigour.sales.infrastructure.persistence.JdbcSalesVoiceProfileRepository(jdbc,new tools.jackson.databind.ObjectMapper());
        var enrollment=new com.rigour.sales.application.port.out.SalesVoiceEnrollmentClient.Enrollment("pyannote3-campplus-zh-v1",vector,12000);
        var registered=voices.save(tenantId,profileId,"陈小明",0,enrollment,now);
        assertThat(registered.registered()).isTrue();
        voices.identifyVisits();
        assertThat(analysisRows.speakers(tenantId,visit.id()).getFirst().name()).isEqualTo("陈小明");
        assertThat(analysisRows.speakers(UUID.randomUUID(),visit.id())).isEmpty();
        assertThatThrownBy(()->voices.save(tenantId,profileId,"不应覆盖",0,enrollment,now)).isInstanceOf(com.rigour.shared.core.exception.StateConflictException.class);
        voices.delete(tenantId,profileId,"陈小明",registered.revision(),now.plusSeconds(8));voices.identifyVisits();
        assertThat(voices.read(tenantId,profileId,"陈小明").registered()).isFalse();
        assertThat(analysisRows.speakers(tenantId,visit.id()).getFirst().name()).isEqualTo("A");
        // A visiting colleague is identified from the same tenant's registry, not the recording owner's profile.
        var colleague=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_profile(id,tenant_id,employee_id,sales_no,status,effective_from,created_at,updated_at)
                VALUES(?,?,?,'S-COLLEAGUE','ACTIVE',UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,bin(colleague),bin(tenantId),bin(UUID.randomUUID()));
        voices.save(tenantId,colleague,"同行销售",0,enrollment,now);
        voices.identifyVisits();
        assertThat(analysisRows.speakers(tenantId,visit.id()).getFirst().name()).isEqualTo("同行销售");
        voices.delete(tenantId,colleague,"同行销售",1,now.plusSeconds(9));
        voices.save(UUID.randomUUID(),colleague,"其他公司",0,enrollment,now);
        voices.identifyVisits();
        assertThat(analysisRows.speakers(tenantId,visit.id()).getFirst().name()).isEqualTo("A");
        assertThat(analysisService.read(visit.id(),false)).isEqualTo(analysisService.read(visit.id(),true));
    }

    @Test
    void managementCountsAndQueueDenyOutOfDepartmentEmployees() {
        var day = attendanceService.checkIn(new CheckInCommand("scope-day", "client", Instant.now(), location("120.1000000", "30.2000000"), null, "ONLINE"));
        var all = new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit("ALL", java.util.List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
                    var caller = (CallerIdentity) invocation.getArgument(0);
                    return new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView("ACTIVE", caller.tenantId(), caller.userId(), "OTHER-EMPLOYEE",1,1,1,1,
                            caller.permissions(),invocation.getArgument(1),true,java.util.List.of(
                            new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Clause(UUID.randomUUID(),"EMPLOYEE","SELF",all,all,all,false)),all,all);
                }).when(managementDirectory).authorization(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        var dashboard=managementService.dashboard(day.businessDate(), day.businessDate());
        assertThat(dashboard.people()).isEmpty();
        assertThat(dashboard.totals().activeSalesCount()).isZero();
        assertThat(dashboard.totals().attendedSalesCount()).isZero();
        assertThat(dashboard.totals().assignedStoreCount()).isZero();
        assertThat(managementService.reviewQueue(day.businessDate(),day.businessDate(),1,20).total()).isZero();
        assertThatThrownBy(() -> attendanceService.managementTrack(profileId, day.businessDate()))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
    }

    @Test
    void automaticDepartureUsesIndependentRadiusWhileArrivalStillUsesItsOwnRule(){
        jdbc.update("UPDATE sales_visit_policy_version SET capture_rules_json=? WHERE id=?", "{\"automaticCheckout\":true,\"automaticCheckoutRadiusMeters\":800,\"maximumLocationAccuracyMeters\":100,\"requireLocationOnOpen\":false,\"automaticRecording\":false,\"allowPhotoImport\":false,\"allowRecordingImport\":false}",bin(visitPolicyVersionId));
        var day=attendanceService.checkIn(new CheckInCommand("radius-day","client",Instant.now(),location("120.1000000","30.2000000"),null,"ONLINE"));
        var visit=visitService.createVisit(new CreateVisitCommand("radius-in",day.id(),null,"MY_STORE",storeId,null,location("120.1000000","30.2000000"),Instant.now(),"radius-in-event"));
        assertThat(visitService.autoDepartureContext(visit.id()).radiusMeters()).isEqualTo(800);
        assertThatThrownBy(()->visitService.autoCheckOutVisit(visit.id(),new CheckOutVisitCommand("radius-mid",Instant.now(),new LocationEvidence(new BigDecimal("120.1065"),new BigDecimal("30.2000"),new BigDecimal("10"),"APP_NATIVE"),"radius-mid"))).hasMessageContaining("尚未确认离开");
    }

    @Test
    void automaticDepartureRequiresOutsideAccurateLocationAndAllowsMissingResult() {
        jdbc.update("UPDATE sales_visit_policy_version SET capture_rules_json=?, recording_enabled=1, minimum_recording_seconds=0 WHERE id=?",
                "{\"automaticCheckout\":true,\"maximumLocationAccuracyMeters\":100,\"requireLocationOnOpen\":false,\"automaticRecording\":true,\"allowPhotoImport\":false,\"allowRecordingImport\":false}", bin(visitPolicyVersionId));
        var day=attendanceService.checkIn(new CheckInCommand("auto-day","client",Instant.now(),location("120.1000000","30.2000000"),null,"ONLINE"));
        var visit=visitService.createVisit(new CreateVisitCommand("auto-in",day.id(),null,"MY_STORE",storeId,null,
                location("120.1000000","30.2000000"),Instant.now(),"auto-in-event"));
        assertThat(visitService.autoDepartureContext(visit.id()).enabled()).isTrue();
        assertThatThrownBy(()->visitService.autoCheckOutVisit(visit.id(),new CheckOutVisitCommand("auto-near",Instant.now(),
                new LocationEvidence(new BigDecimal("120.1000000"),new BigDecimal("30.2000000"),new BigDecimal("20"),"APP_NATIVE"),"auto-near")))
                .hasMessageContaining("尚未确认离开");
        assertThatThrownBy(()->visitService.autoCheckOutVisit(visit.id(),new CheckOutVisitCommand("auto-uncertain",Instant.now(),
                new LocationEvidence(new BigDecimal("120.1200000"),new BigDecimal("30.2000000"),new BigDecimal("200"),"APP_NATIVE"),"auto-uncertain")))
                .hasMessageContaining("定位精度");
        var command=new CheckOutVisitCommand("auto-out",Instant.now(),
                new LocationEvidence(new BigDecimal("120.1200000"),new BigDecimal("30.2000000"),new BigDecimal("20"),"APP_NATIVE"),"auto-out");
        var ended=visitService.autoCheckOutVisit(visit.id(),command);
        assertThat(ended.status()).isEqualTo("CHECKED_OUT");
        assertThat(ended.resultSubmittedAt()).isNull();
        assertThat(visitService.autoCheckOutVisit(visit.id(),command).id()).isEqualTo(visit.id());
        assertThat(ended.checkpoints()).hasSize(2);
        assertThat(visitService.submitVisitResult(visit.id(),new VisitResultCommand("CONTACTED","测试接待","13800000001","NONE","离店后补充真实结果")).resultSubmittedAt()).isNotNull();
    }

    @Test
    void visitFlowCoversMyStorePoiRadiusAndPoiProjection() {
        WorkDayView workDay = attendanceService.checkIn(new CheckInCommand("visit-check-in-day-1",
                "client-1", Instant.now(), location("120.1000000", "30.2000000"), null, "ONLINE"));
        assertThat(workDay.status()).isEqualTo("ACTIVE");

        Instant visitReceivedAt = Instant.now();
        VisitView myStore = visitService.createVisit(new CreateVisitCommand("visit-create-my-store-1",
                workDay.id(), null, "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                visitReceivedAt, "device-visit-check-in-1"));
        assertThat(myStore.status()).isEqualTo("CHECKED_IN");
        assertThat(myStore.targetSnapshot().storeId()).isEqualTo(storeId);
        assertThat(myStore.targetSnapshot().customerName()).isEqualTo("测试客户");

        VisitView replayed = visitService.createVisit(new CreateVisitCommand("visit-create-my-store-1",
                workDay.id(), null, "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                visitReceivedAt, "device-visit-check-in-1"));
        assertThat(replayed.id()).isEqualTo(myStore.id());

        VisitView outside = null;
        try {
            outside = visitService.createVisit(new CreateVisitCommand("visit-create-my-store-outside",
                    workDay.id(), null, "MY_STORE", storeId, null,
                    location("121.5000000", "31.5000000"), Instant.now(), "device-visit-outside-1"));
        } catch (BusinessException error) {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_VISIT_OUTSIDE_RADIUS);
        }
        assertThat(outside).isNull();

        PoiTargetCommand poi = new PoiTargetCommand("TESTPOI-0001", "附近测试便利店", "科技园路88号",
                new BigDecimal("120.1100000"), new BigDecimal("30.2100000"), new BigDecimal("80"));
        assertThatThrownBy(() -> visitService.createVisit(new CreateVisitCommand("visit-create-poi-overlap",
                workDay.id(), null, "POI", null, poi, location("120.1100000", "30.2100000"),
                Instant.now(), "device-visit-overlap")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_VISIT_INVALID_STATE));
        assertThatThrownBy(() -> attendanceService.checkOut(workDay.id(),
                new CheckOutCommand("workday-check-out-with-active-visit", Instant.now(),
                        location("120.1000000", "30.2000000"), null, "ONLINE")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_WORK_DAY_INVALID_STATE));

        visitService.submitVisitResult(myStore.id(),
                new VisitResultCommand("CONTACTED", "王店长", "13800000001", "LOW", "完成首次拜访"));
        visitService.checkOutVisit(myStore.id(),
                new CheckOutVisitCommand("visit-check-out-my-store-1", Instant.now(),
                        location("120.1000000", "30.2000000"), "device-visit-check-out-1"));

        VisitView poiVisit = visitService.createVisit(new CreateVisitCommand("visit-create-poi-1",
                workDay.id(), null, "POI", null, poi, location("120.1100000", "30.2100000"),
                Instant.now(), "device-visit-check-in-2"));
        assertThat(poiVisit.status()).isEqualTo("CHECKED_IN");
        assertThat(poiVisit.targetSnapshot().storeName()).isEqualTo("附近测试便利店");

        UUID poiStoreId = poiVisit.targetSnapshot().storeId();
        assertThat(count("SELECT COUNT(*) FROM crm_store_projection WHERE tenant_id=? AND store_id=?",
                tenantId, poiStoreId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM crm_sales_assignment_projection WHERE tenant_id=? "
                + "AND sales_profile_id=? AND store_id=?", tenantId, profileId, poiStoreId)).isEqualTo(1);
        assertThat(visitService.visits(1, 20).total()).isEqualTo(2);

        assertThatThrownBy(() -> visitService.checkOutVisit(poiVisit.id(),
                new CheckOutVisitCommand("visit-check-out-without-result", Instant.now(),
                        location("120.1100000", "30.2100000"), "device-visit-check-out-no-result")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_VISIT_INVALID_STATE));
        visitService.submitVisitResult(poiVisit.id(),
                new VisitResultCommand("CONTACTED", "李店长", "13900001234", "MEDIUM", "后续复访"));
        VisitView checkedOut = visitService.checkOutVisit(poiVisit.id(),
                new CheckOutVisitCommand("visit-check-out-poi-1", Instant.now(),
                        location("120.1100000", "30.2100000"), "device-visit-check-out-2"));
        assertThat(checkedOut.status()).isEqualTo("CHECKED_OUT");
        assertThat(checkedOut.checkedOutAt()).isNotNull();
        assertThat(checkedOut.checkpoints()).hasSize(2);

        VisitView detail = visitService.visit(poiVisit.id());
        assertThat(detail.checkpoints().stream()
                .map(point -> point.checkpointType()).toList())
                .containsExactly("CHECK_IN", "CHECK_OUT");
        assertThat(visitService.visits(1, 20).items().get(0).status()).isEqualTo("CHECKED_OUT");

        var datedVisits = visitService.visits(workDay.businessDate(), 1, 20);
        assertThat(datedVisits.total()).isEqualTo(2);
        assertThat(datedVisits.items()).allMatch(item -> "FIRST_VISIT".equals(item.visitType()));
        var activity = visitService.activitySummary(workDay.businessDate(), workDay.businessDate());
        assertThat(activity.totalVisitCount()).isEqualTo(2);
        assertThat(activity.completedVisitCount()).isEqualTo(2);
        assertThat(activity.effectiveVisitCount()).isZero();
        assertThat(activity.pendingReviewVisitCount()).isEqualTo(2);
        assertThat(activity.firstVisitCount()).isEqualTo(2);
        assertThat(activity.uniqueStoreCount()).isEqualTo(2);
        assertThat(activity.assignedStoreCount()).isEqualTo(2);
        var firstVisits = visitService.activityVisits(workDay.businessDate().withDayOfMonth(1), workDay.businessDate(), "first", 1, 1);
        assertThat(firstVisits.total()).isEqualTo(activity.firstVisitCount());
        assertThat(firstVisits.items()).hasSize(1);
        assertThat(visitService.activityVisits(workDay.businessDate(), workDay.businessDate(), "effective", 1, 20).total()).isZero();
        assertThat(visitService.activityVisits(workDay.businessDate().minusDays(1), workDay.businessDate().minusDays(1), "all", 1, 20).items()).isEmpty();


        var track = attendanceService.track(workDay.businessDate());
        assertThat(track.visits()).hasSize(2);
        assertThat(track.visits().get(0).storeName()).isEqualTo("我的测试门店");
        assertThat(track.visits().get(1).storeName()).isEqualTo("附近测试便利店");
        assertThat(track.segments()).singleElement()
                .satisfies(segment -> assertThat(segment.distanceSource()).isEqualTo("UNAVAILABLE"));
        assertThat(attendanceService.managementTrack(profileId, workDay.businessDate()).workDayId())
                .isEqualTo(workDay.id());

        var management = managementService.dashboard(workDay.businessDate(), workDay.businessDate());
        assertThat(management.totals().activeSalesCount()).isEqualTo(1);
        assertThat(management.totals().completedVisitCount()).isEqualTo(2);
        assertThat(management.totals().effectiveVisitCount()).isZero();
        assertThat(management.totals().pendingReviewVisitCount()).isEqualTo(2);
        assertThat(management.totals().assignedStoreCount()).isEqualTo(2);
        assertThat(management.people()).singleElement()
                .satisfies(person -> {
                    assertThat(person.salesNo()).isEqualTo("S-VISIT-001");
                    assertThat(person.assignedStoreCount()).isEqualTo(2);
                });

        var reviewQueue = managementService.reviewQueue(workDay.businessDate(), workDay.businessDate(), 1, 20);
        assertThat(reviewQueue.total()).isEqualTo(2);
        assertThat(reviewQueue.items()).extracting(item -> item.storeName())
                .containsExactly("我的测试门店", "附近测试便利店");
        var review = managementService.reviewVisit(myStore.id(),
                new ReviewVisitCommand("EFFECTIVE", "EVIDENCE_CONFIRMED", "现场证据完整"));
        assertThat(review.decision()).isEqualTo("EFFECTIVE");
        assertThat(review.reasonCode()).isEqualTo("EVIDENCE_CONFIRMED");
        assertThat(managementService.reviewVisit(myStore.id(),
                new ReviewVisitCommand("EFFECTIVE", "EVIDENCE_CONFIRMED", "重复提交")).finalizedAt())
                .isEqualTo(review.finalizedAt());
        assertThatThrownBy(() -> managementService.reviewVisit(myStore.id(),
                new ReviewVisitCommand("INEFFECTIVE", "EVIDENCE_INSUFFICIENT", "冲突结论")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_ADMIN_INVALID));
        var reviewedManagement = managementService.dashboard(workDay.businessDate(), workDay.businessDate());
        assertThat(reviewedManagement.totals().effectiveVisitCount()).isEqualTo(1);
        assertThat(reviewedManagement.totals().pendingReviewVisitCount()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales_visit_review WHERE tenant_id=? "
                + "AND review_type='MANUAL'", tenantId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? "
                + "AND event_type='SalesVisitFinalized'", tenantId)).isEqualTo(1);

        // Outbox 回归：拜访创建/签退事件必须成功写入，聚合版本路由到 sales_visit 而不是 sales_work_day。
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? "
                + "AND aggregate_type='SALES_VISIT' AND event_type='SalesVisitCreated'", tenantId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? "
                + "AND aggregate_type='SALES_VISIT' AND event_type='SalesVisitCheckedOut'", tenantId)).isEqualTo(2);
    }

    @Test
    void managerPlanFlowsToOwnTodayPlanExecutionAndCompletion() {
        // 本场景创建并执行自然日计划；凌晨运行时不能继承04:00换日的跨夜外勤规则。
        assertThat(jdbc.update("UPDATE sales_field_policy_version SET business_day_cutoff='00:00:00' "
                + "WHERE tenant_id=? AND id=?", bin(tenantId), bin(fieldPolicyVersionId))).isEqualTo(1);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var planned = visitPlanService.create(new UpsertVisitPlanCommand(
                profileId, today, storeId, "核对陈列并确认下月补货计划", null));
        assertThat(planned.status()).isEqualTo("PLANNED");
        assertThatThrownBy(() -> visitPlanService.create(new UpsertVisitPlanCommand(
                profileId, today, storeId, "重复计划", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_ADMIN_INVALID));
        assertThat(visitPlanService.ownPlans(today).items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.planId()).isEqualTo(planned.planId());
                    assertThat(item.storeName()).isEqualTo("我的测试门店");
                    assertThat(item.visitId()).isNull();
                });

        WorkDayView workDay = attendanceService.checkIn(new CheckInCommand("plan-check-in-day-1",
                "plan-client-1", Instant.now(), location("120.1000000", "30.2000000"), null, "ONLINE"));
        assertThat(workDay.businessDate()).isEqualTo(today);
        CreateVisitCommand createCommand = new CreateVisitCommand("plan-visit-create-1",
                workDay.id(), planned.planId(), null, null, null,
                location("120.1000000", "30.2000000"), Instant.now(), "plan-device-1");
        VisitView visit = visitService.createVisit(createCommand);
        assertThat(visitService.createVisit(createCommand).id()).isEqualTo(visit.id());
        assertThat(visit.targetSnapshot().storeName()).isEqualTo("我的测试门店");
        assertThat(visitPlanService.ownPlans(today).items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("IN_PROGRESS");
                    assertThat(item.visitId()).isEqualTo(visit.id());
                });

        visitService.submitVisitResult(visit.id(),
                new VisitResultCommand("CONTACTED", "计划KP", "13800000006", "MEDIUM", "计划已执行"));
        CheckOutVisitCommand checkOutCommand = new CheckOutVisitCommand("plan-check-out-1",
                Instant.now(), location("120.1000000", "30.2000000"), "plan-device-out-1");
        VisitView completed = visitService.checkOutVisit(visit.id(), checkOutCommand);
        assertThat(visitService.checkOutVisit(visit.id(), checkOutCommand).id()).isEqualTo(completed.id());
        assertThat(visitPlanService.ownPlans(today).items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo("COMPLETED"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_outbox_event
                 WHERE tenant_id=? AND aggregate_id=?
                   AND event_type IN ('SalesVisitCreated','SalesVisitCheckedOut')
                   AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.visitPlanId'))=?
                """, Integer.class, bin(tenantId), bin(visit.id()), planned.planId().toString()))
                .isEqualTo(2);

        var cancellable = visitPlanService.create(new UpsertVisitPlanCommand(
                profileId, today.plusDays(1), storeId, "次日复访", null));
        var cancelled = visitPlanService.cancel(cancellable.planId(),
                new CancelVisitPlanCommand(cancellable.version()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(visitPlanService.create(new UpsertVisitPlanCommand(
                profileId, today.plusDays(1), storeId, "取消后重新安排", null)).status())
                .isEqualTo("PLANNED");
    }

    @Test
    void visitResultAndRecordingClipArePersisted() {
        WorkDayView workDay = attendanceService.checkIn(new CheckInCommand("result-check-in-day-1",
                "client-1", Instant.now(), location("120.1000000", "30.2000000"), null, "ONLINE"));
        VisitView visit = visitService.createVisit(new CreateVisitCommand("result-visit-create-1",
                workDay.id(), null, "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                Instant.now(), "device-result-visit-1"));

        // 拜访结果：进行中即可录入，签退后仍可补录。
        VisitView withResult = visitService.submitVisitResult(visit.id(),
                new VisitResultCommand("CONTACTED", "王店长", "13800001234", "HIGH", "有意向下月进货"));
        assertThat(withResult.kpName()).isEqualTo("王店长");
        assertThat(withResult.intentionLevel()).isEqualTo("HIGH");
        assertThat(withResult.resultSubmittedAt()).isNotNull();

        assertThatThrownBy(() -> visitService.submitVisitResult(visit.id(),
                new VisitResultCommand("CONTACTED", "王店长", "13800001234", "WRONG", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_VISIT_TARGET_INVALID));
        assertThatThrownBy(() -> visitService.submitVisitResult(visit.id(),
                new VisitResultCommand("CONTACTED", "", "", "HIGH", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        jdbc.update("UPDATE sales_visit_policy_version SET recording_enabled=1, minimum_recording_seconds=60 WHERE id=?",
                bin(visitPolicyVersionId));
        VisitView checkedOutBeforeRecording = visitService.checkOutVisit(visit.id(),
                new CheckOutVisitCommand("result-check-out-before-recording", Instant.now(),
                        location("120.1000000", "30.2000000"), "device-before-recording"));
        assertThat(checkedOutBeforeRecording.status()).isEqualTo("CHECKED_OUT");
        assertThat(checkedOutBeforeRecording.reviewStatus()).isEqualTo("PENDING_REVIEW");

        // 录音片段：上传登记会话与片段事实，字节经 FileStorage 落盘。
        Instant firstTo = Instant.now();
        // 4帧固定真实夹具重复323次为1292帧，解码时长约30000ms。
        byte[] firstBytes = RecordingMediaSamples.repeatM4a(323);
        var firstFile = new MockMultipartFile("file", "clip-0.aac", "audio/aac", firstBytes);

        Instant shortTo = Instant.now();
        assertThatThrownBy(() -> recordingService.uploadClip(visit.id(), firstFile,
                "device-short-upload", 20_000L, shortTo.minusSeconds(20), shortTo))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_RECORDING_INVALID));
        var discarded = recordingService.discardClip(visit.id(),
                new DiscardRecordingClipCommand("device-short-discard", 20_000L,
                        shortTo.minusSeconds(20), shortTo, "TOO_SHORT"));
        assertThat(discarded.disposition()).isEqualTo("DISCARDED_NOT_STORED");
        var discardedReplay = recordingService.discardClip(visit.id(),
                new DiscardRecordingClipCommand("device-short-discard", 20_000L,
                        shortTo.minusSeconds(20), shortTo, "TOO_SHORT"));
        assertThat(discardedReplay.recordedAt()).isEqualTo(discarded.recordedAt());
        assertThat(count("SELECT COUNT(*) FROM sales_recording_discard WHERE tenant_id=?", tenantId))
                .isEqualTo(1);

        var clip = recordingService.uploadClip(visit.id(),
                firstFile, "device-clip-0", 35_000L, firstTo.minusSeconds(35), firstTo);
        assertThat(clip.clipIndex()).isEqualTo(0);
        assertThat(clip.uploadStatus()).isEqualTo("RECEIVED");
        assertThat(clip.clientClipId()).isEqualTo("device-clip-0");

        // 同一客户端片段重试必须幂等返回，不得重复累计片段。
        var replayedClip = recordingService.uploadClip(visit.id(), firstFile,
                "device-clip-0", 35_000L, firstTo.minusSeconds(35), firstTo);
        assertThat(replayedClip.clipId()).isEqualTo(clip.clipId());

        // 攻击重放：相同字节换 clientClipId 仍只能保留一个片段，并将会话送复核。
        var changedIdReplay = recordingService.uploadClip(visit.id(), firstFile,
                "device-clip-replay", 35_000L, firstTo.minusSeconds(35), firstTo);
        assertThat(changedIdReplay.clipId()).isEqualTo(clip.clipId());
        assertThat(count("SELECT COUNT(*) FROM sales_recording_clip WHERE tenant_id=?", tenantId))
                .isEqualTo(1);

        // 攻击重放：仅改变容器元数据时文件SHA不同，但解码PCM指纹相同，仍不得累计。
        byte[] metadataChangedM4a = firstBytes.clone();
        metadataChangedM4a[15] ^= 0x01;
        var metadataReplay = recordingService.uploadClip(visit.id(),
                new MockMultipartFile("file", "clip-metadata-replay.m4a", "audio/mp4",
                        metadataChangedM4a),
                "device-clip-metadata-replay", 35_000L, firstTo.minusSeconds(35), firstTo);
        assertThat(metadataReplay.clipId()).isEqualTo(clip.clipId());

        // 攻击重放：同一AAC换为ID3+ADTS后字节/容器都不同，解码PCM仍必须去重。
        byte[] id3 = new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 3, 9, 8, 7};
        byte[] id3Adts = concat(id3, RecordingMediaSamples.repeatAdts(323));
        var id3Replay = recordingService.uploadClip(visit.id(),
                new MockMultipartFile("file", "clip-id3-replay.aac", "application/octet-stream",
                        id3Adts),
                "device-clip-id3-replay", 35_000L, firstTo.minusSeconds(35), firstTo);
        assertThat(id3Replay.clipId()).isEqualTo(clip.clipId());

        Instant secondTo = firstTo.plusSeconds(35);
        byte[] secondBytes = RecordingMediaSamples.repeatAlternateM4a(323);
        var second = recordingService.uploadClip(visit.id(),
                new MockMultipartFile("file", "clip-1.m4a", "audio/m4a", secondBytes),
                "device-clip-1", 30_000L, secondTo.minusSeconds(30), secondTo);
        assertThat(second.clipIndex()).isEqualTo(1);

        var session = recordingService.recordings(visit.id());
        assertThat(session.clipCount()).isEqualTo(2);
        assertThat(session.clips()).hasSize(2);
        assertThat(session.uploadedTotalDurationMs()).isEqualTo(65_000L);
        assertThat(session.minimumClipSeconds()).isEqualTo(30);
        assertThat(session.verifiedTotalDurationMs()).isEqualTo(60_000L);
        assertThat(session.evidenceStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(count("""
                SELECT COUNT(*) FROM sales_recording_clip
                 WHERE tenant_id=? AND perceptual_hash LIKE 'pcm-sha256-v1:%'
                """, tenantId)).isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(DISTINCT perceptual_hash) FROM sales_recording_clip
                 WHERE tenant_id=?
                """, tenantId)).isEqualTo(2);

        VisitView checkedOut = visitService.visit(visit.id());
        assertThat(checkedOut.kpName()).isEqualTo("王店长");
        var managementRecordings = managementService.reviewRecordings(visit.id());
        assertThat(managementRecordings.clipCount()).isEqualTo(2);
        assertThat(managementRecordings.uploadedTotalDurationMs()).isEqualTo(65_000L);
        var recordingContent = managementService.reviewRecordingClip(visit.id(), clip.clipId());
        assertThat(recordingContent.mediaType()).isEqualTo("audio/m4a");
        assertThat(recordingContent.bytes()).isEqualTo(firstBytes);

        // 签退后补录结果仍允许。
        VisitView updated = visitService.submitVisitResult(visit.id(),
                new VisitResultCommand("CONTACTED", "王店长", "13800001234", "MEDIUM", "复访跟进"));
        assertThat(updated.intentionLevel()).isEqualTo("MEDIUM");

        // 关门/KP不在等未接触场景不伪造KP资料，保存现场说明后可直接离店。
        VisitView closedStoreVisit = visitService.createVisit(new CreateVisitCommand("closed-store-create-1",
                workDay.id(), null, "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                Instant.now(), "device-closed-store-create-1"));
        VisitView closedStoreResult = visitService.submitVisitResult(closedStoreVisit.id(),
                new VisitResultCommand("STORE_CLOSED", null, null, null, "门店卷帘门关闭，现场无人"));
        assertThat(closedStoreResult.contactOutcome()).isEqualTo("STORE_CLOSED");
        assertThat(closedStoreResult.kpName()).isNull();
        assertThat(visitService.checkOutVisit(closedStoreVisit.id(),
                new CheckOutVisitCommand("closed-store-check-out-1", Instant.now(),
                        location("120.1000000", "30.2000000"), "device-closed-store-out-1"))
                .status()).isEqualTo("CHECKED_OUT");
    }

    @Test
    void storefrontPhotoRequiresCameraValidImageGeofenceAndIsIdempotent() {
        WorkDayView workDay = attendanceService.checkIn(new CheckInCommand("photo-check-in-day-1",
                "client-photo-1", Instant.now(), location("120.1000000", "30.2000000"), null, "ONLINE"));
        VisitView visit = visitService.createVisit(new CreateVisitCommand("photo-visit-create-1",
                workDay.id(), null, "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                Instant.now(), "device-photo-visit-1"));

        byte[] jpeg = jpegBytes(new Color(42, 103, 180));
        var file = new MockMultipartFile("file", "storefront.jpg", "image/jpeg", jpeg);
        Instant capturedAt = Instant.now();
        var uploaded = evidenceService.uploadStorefrontPhoto(visit.id(), file, "client-photo-evidence-1",
                "FEISHU_CAMERA", capturedAt, new BigDecimal("120.1000000"),
                new BigDecimal("30.2000000"), new BigDecimal("12.00"));
        assertThat(uploaded.evidenceRole()).isEqualTo("STOREFRONT");
        assertThat(uploaded.captureSource()).isEqualTo("FEISHU_CAMERA");
        assertThat(uploaded.evidenceStatus()).isEqualTo("TECHNICALLY_VERIFIED");

        var summary = evidenceService.evidence(visit.id());
        assertThat(summary.requiredStorefrontPhotoCount()).isEqualTo(1);
        assertThat(summary.storefrontPhotoCount()).isEqualTo(1);
        assertThat(summary.storefrontPhotoSatisfied()).isTrue();

        var replayed = evidenceService.uploadStorefrontPhoto(visit.id(), file, "client-photo-evidence-1",
                "FEISHU_CAMERA", capturedAt, new BigDecimal("120.1000000"),
                new BigDecimal("30.2000000"), new BigDecimal("12.00"));
        assertThat(replayed.evidenceId()).isEqualTo(uploaded.evidenceId());
        assertThat(count("SELECT COUNT(*) FROM sales_visit_evidence WHERE tenant_id=?", tenantId))
                .isEqualTo(1);

        var differentJpeg = new MockMultipartFile("file", "different.jpg", "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01});
        assertThatThrownBy(() -> evidenceService.uploadStorefrontPhoto(visit.id(), differentJpeg,
                "client-photo-evidence-1", "FEISHU_CAMERA", capturedAt,
                new BigDecimal("120.1000000"), new BigDecimal("30.2000000"), new BigDecimal("12.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_EVIDENCE_INVALID));
        assertThatThrownBy(() -> evidenceService.uploadStorefrontPhoto(visit.id(), file,
                "client-photo-album", "ALBUM", capturedAt,
                new BigDecimal("120.1000000"), new BigDecimal("30.2000000"), new BigDecimal("12.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_EVIDENCE_INVALID));
        assertThatThrownBy(() -> evidenceService.uploadStorefrontPhoto(visit.id(), file,
                "client-photo-offsite", "FEISHU_CAMERA", capturedAt,
                new BigDecimal("121.5000000"), new BigDecimal("31.5000000"), new BigDecimal("12.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_VISIT_OUTSIDE_RADIUS));
    }

    @Test
    void structurallyFramedAacCannotAutoConfirmAndAnomaliesStayForSupervisor() {
        jdbc.update("""
                UPDATE sales_visit_policy_version
                   SET recording_enabled=1, minimum_recording_seconds=30,
                       ai_asr_enabled=0, ai_relevance_enabled=0, ai_duplicate_enabled=0
                 WHERE tenant_id=? AND id=?
                """, bin(tenantId), bin(visitPolicyVersionId));
        WorkDayView workDay = attendanceService.checkIn(new CheckInCommand("auto-day-1",
                "auto-client-1", Instant.now(), location("120.1000000", "30.2000000"), null, "ONLINE"));
        VisitView completeVisit = visitService.createVisit(new CreateVisitCommand("auto-visit-1",
                workDay.id(), null, "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                Instant.now(), "auto-device-1"));
        byte[] jpeg = jpegBytes(new Color(42, 103, 180));
        var uploadedPhoto = evidenceService.uploadStorefrontPhoto(completeVisit.id(),
                new MockMultipartFile("file", "storefront.jpg", "image/jpeg", jpeg),
                "auto-photo-1", "FEISHU_CAMERA", Instant.now(),
                new BigDecimal("120.1000000"), new BigDecimal("30.2000000"), new BigDecimal("10"));

        int frameCount = 1_508;
        long verifiedDurationMs = Math.round(frameCount * 1024d * 1_000d / 44_100d);
        Instant recordedTo = Instant.now();
        assertThatThrownBy(() -> recordingService.uploadClip(completeVisit.id(),
                new MockMultipartFile("file", "forged.aac", "audio/aac", adtsFrames(frameCount)),
                "auto-recording-1", verifiedDurationMs,
                recordedTo.minusMillis(verifiedDurationMs), recordedTo))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.SALES_RECORDING_INVALID));
        visitService.submitVisitResult(completeVisit.id(),
                new VisitResultCommand("CONTACTED", "自动判定KP", "13800000002", "HIGH", "证据完整"));
        VisitView pendingRecording = visitService.checkOutVisit(completeVisit.id(),
                new CheckOutVisitCommand("auto-check-out-1", Instant.now(),
                        location("120.1000000", "30.2000000"), "auto-device-out-1"));
        assertThat(pendingRecording.reviewStatus()).isEqualTo("PENDING_REVIEW");
        var recordingSession = recordingService.recordings(completeVisit.id());
        assertThat(recordingSession.evidenceStatus()).isEqualTo("PENDING");
        assertThat(recordingSession.verifiedTotalDurationMs()).isZero();
        var managementEvidence = managementService.reviewEvidence(completeVisit.id());
        assertThat(managementEvidence.verifiedStorefrontPhotoCount()).isEqualTo(1);
        assertThat(managementService.reviewEvidencePhoto(completeVisit.id(), uploadedPhoto.evidenceId()).bytes())
                .isEqualTo(jpeg);
        assertThat(count("SELECT COUNT(*) FROM sales_visit_review WHERE tenant_id=? "
                + "AND visit_id=? AND review_type='AUTOMATIC_ASSESSMENT' AND review_status='PENDING' "
                + "AND reason_code='RECORDING_UNVERIFIED'", tenantId, completeVisit.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? "
                + "AND event_type='SalesVisitFinalized'", tenantId)).isZero();

        jdbc.update("""
                UPDATE sales_visit_policy_version
                   SET recording_enabled=0, minimum_recording_seconds=0
                 WHERE tenant_id=? AND id=?
                """, bin(tenantId), bin(visitPolicyVersionId));
        VisitView missingPhoto = visitService.createVisit(new CreateVisitCommand("anomaly-visit-1",
                workDay.id(), null, "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                Instant.now(), "anomaly-device-1"));
        visitService.submitVisitResult(missingPhoto.id(),
                new VisitResultCommand("CONTACTED", "异常KP", "13800000003", "LOW", "缺少门头照"));
        VisitView pending = visitService.checkOutVisit(missingPhoto.id(),
                new CheckOutVisitCommand("anomaly-check-out-1", Instant.now(),
                        location("120.1000000", "30.2000000"), "anomaly-device-out-1"));
        assertThat(pending.reviewStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(count("SELECT COUNT(*) FROM sales_visit_review WHERE tenant_id=? "
                + "AND review_type='AUTOMATIC_ASSESSMENT' AND review_status='PENDING' "
                + "AND reason_code='STOREFRONT_PHOTO_MISSING'", tenantId)).isEqualTo(1);
        assertThat(managementService.reviewQueue(workDay.businessDate(), workDay.businessDate(), 1, 20)
                .items()).extracting(item -> item.visitId()).contains(missingPhoto.id());
    }

    @Test
    void cannotCreateVisitWithoutActiveWorkDay() {
        assertThatThrownBy(() -> visitService.createVisit(new CreateVisitCommand("visit-no-day-1",
                UUID.randomUUID(), null, "MY_STORE", storeId, null,
                location("120.1000000", "30.2000000"), Instant.now(), "device-visit-no-day-1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
    }

    private void seedFieldPolicy(Instant now) {
        jdbc.update("""
                INSERT INTO sales_field_policy
                    (id, tenant_id, policy_code, policy_name, status, version, created_at, updated_at)
                VALUES (?, ?, 'FIELD-VISIT-TEST', '测试外勤规则', 'ACTIVE', 1, ?, ?)
                """, bin(fieldPolicyId), bin(tenantId), timestamp(now), timestamp(now));
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
                """, bin(fieldPolicyVersionId), bin(tenantId), bin(fieldPolicyId),
                timestamp(now.minusSeconds(60)), bin(userId), timestamp(now.minusSeconds(60)),
                bin(userId), timestamp(now));
    }

    @Test
    void structuredCrmSubmissionIsValidatedPersistedAndEnqueuedAtomicallyAndRetryKeepsOriginalTime(){
        var policy=UUID.randomUUID();var capture=UUID.randomUUID();var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var day=attendanceService.checkIn(new CheckInCommand("structured-day","client",now,location("120.1","30.2"),null,"ONLINE"));
        captureStore.create(tenantId,profileId,day.id(),capture,new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context("200","100","300","TEST1",capture.toString(),policy.toString(),now,"测试店","地址",new BigDecimal("120.1"),new BigDecimal("30.2"),"GCJ02",BigDecimal.ZERO,1,"IN_PROGRESS",new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy(policy.toString(),500)),location("120.1","30.2"),null);
        var draft=new com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Draft(7,"OPEN",null,"TYPE","CONTACTED",List.of("4"),false,List.of(new com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Progress("LINE",null,"COOPERATED")),null,null,null,null);
        var command=new VisitResultCommand("CONTACTED",null,null,null,null,draft);
        assertThatThrownBy(()->visitService.submitVisitResult(capture,new VisitResultCommand("CONTACTED",null,null,"NONE","旧客户端"))).hasMessageContaining("完整拜访信息");
        assertThatThrownBy(()->visitService.submitVisitResult(capture,command)).hasMessageContaining("门头照");
        evidenceService.uploadStorefrontPhoto(capture,new MockMultipartFile("file","store.jpg","image/jpeg",jpegBytes(Color.GREEN)),"structured-photo","APP_CAMERA",now,new BigDecimal("120.1"),new BigDecimal("30.2"),BigDecimal.TEN);
        org.mockito.Mockito.when(crmVisit.validateFieldwork("100","200",draft)).thenThrow(new com.rigour.shared.core.exception.RequestValidationException("请选择接待人"));
        assertThatThrownBy(()->visitService.submitVisitResult(capture,command)).hasMessageContaining("接待人");
        assertThat(captureStore.fieldwork(tenantId,capture,false)).isNull();
        var accepted=new com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Accepted(draft,userId.toString(),"employee",Map.of("VISIT_CONTACT_OUTCOME:CONTACTED","已沟通","CRM_STORE_BUSINESS_STATUS:OPEN","营业中","STORE_BUSINESS_TYPE:TYPE","商业娱乐","BUSINESS_LINE:LINE","高德业务","CUSTOMER_COOP_STATUS:COOPERATED","已合作"),List.of());
        org.mockito.Mockito.doReturn(accepted).when(crmVisit).validateFieldwork("100","200",draft);
        var first=visitService.submitVisitResult(capture,command);var retry=visitService.submitVisitResult(capture,command);
        assertThat(retry.resultSubmittedAt()).isEqualTo(first.resultSubmittedAt());assertThat(captureStore.fieldwork(tenantId,capture,false)).isEqualTo(accepted);
        assertThat(latestCrmEvidence(capture).fieldwork()).isEqualTo(accepted);
        assertThat(latestCrmEvidence(capture).resultSummary()).contains("已沟通","营业中","高德业务：已合作").doesNotContain("负责人");
        assertThatThrownBy(()->visitService.submitVisitResult(capture,new VisitResultCommand("CONTACTED",null,null,null,"不能覆盖",draft))).hasMessageContaining("重复修改");
    }

    @Test
    void crmCaptureReusesPhotoAssessmentAndKeepsFinalBusinessFactOutOfSales() {
        var crmPolicyId=UUID.randomUUID();
        var day=attendanceService.checkIn(new CheckInCommand("crm-day-test","crm-test-client",Instant.now(),location("120.1000000","30.2000000"),null,"ONLINE"));
        var captureId=UUID.randomUUID();
        var captureContext=new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context("200","100","300","TEST1",captureId.toString(),crmPolicyId.toString(),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),"测试店","测试地址",new BigDecimal("120.1"),new BigDecimal("30.2"),"GCJ02",BigDecimal.ZERO,1,"IN_PROGRESS",new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy(crmPolicyId.toString(),500));
        captureStore.create(tenantId,profileId,day.id(),captureId,captureContext,location("120.1000000","30.2000000"),null);
        captureStore.create(tenantId,profileId,day.id(),captureId,captureContext,location("120.1000000","30.2000000"),null);
        assertThat(jdbc.queryForObject("SELECT source_system FROM sales_visit_policy_version WHERE tenant_id=? AND id=?",String.class,bin(tenantId),bin(crmPolicyId))).isEqualTo("CRM");
        assertThat(jdbc.queryForObject("SELECT publish_status FROM sales_visit_policy_version WHERE tenant_id=? AND id=?",String.class,bin(tenantId),bin(crmPolicyId))).isEqualTo("SNAPSHOT");
        var visit=visitService.visit(captureId);
        assertThat(captureStore.link(tenantId,captureId).visitId()).isEqualTo("200");
        org.mockito.Mockito.when(crmVisit.context("100","200")).thenReturn(new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context("200","100","300","TEST1",visit.id().toString(),crmPolicyId.toString(),Instant.now(),"测试店","测试地址",BigDecimal.ONE,BigDecimal.ONE,"GCJ02",BigDecimal.ZERO,1,"IN_PROGRESS"));
        var noPhone=visitService.submitVisitResult(visit.id(),preparedFieldwork(captureId,"100","200",null));
        assertThat(noPhone.kpPhone()).isNull();
        assertThat(noPhone.resultSubmittedAt()).isNotNull();
        org.mockito.Mockito.verify(crmVisit).validateFieldwork(org.mockito.ArgumentMatchers.eq("100"),org.mockito.ArgumentMatchers.eq("200"),org.mockito.ArgumentMatchers.any());
        visitService.checkOutVisit(visit.id(),new CheckOutVisitCommand("crm-checkout-test",Instant.now(),location("120.1000000","30.2000000"),"crm-device-out"));
        var pending=latestCrmEvidence(captureId);
        assertThat(pending.verifiedPhotoCount()).isEqualTo(1);assertThat(pending.submittedAt()).isNotNull();
        assertThat(managementService.reviewQueue(day.businessDate(),day.businessDate(),1,20).total()).isZero();
        evidenceService.uploadStorefrontPhoto(visit.id(),new MockMultipartFile("file","store.jpg","image/jpeg",jpegBytes(Color.BLUE)),"crm-photo-test","APP_CAMERA",Instant.now(),new BigDecimal("120.1"),new BigDecimal("30.2"),BigDecimal.TEN);
        var effective=latestCrmEvidence(captureId);
        assertThat(effective.verifiedPhotoCount()).isEqualTo(2);assertThat(effective.revision()).isGreaterThan(pending.revision());
        assertThat(effective.checkedInAt()).isEqualTo(pending.checkedInAt());
        org.mockito.Mockito.when(crmVisit.evidenceAccess("100","200")).thenReturn(visit.id().toString());
        assertThat(managementService.reviewEvidence(visit.id()).photos()).hasSize(2);
        assertThatThrownBy(()->managementService.reviewVisit(visit.id(),new ReviewVisitCommand("EFFECTIVE","TEST","测试"))).hasMessageContaining("CRM");
        org.mockito.Mockito.when(crmVisit.evidenceAccess("100","200")).thenThrow(new com.rigour.shared.context.AuthorizationDeniedException("crm:store:history-read"));
        assertThatThrownBy(()->managementService.reviewEvidence(visit.id())).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);

        assertThat(count("SELECT COUNT(*) FROM sales_visit WHERE tenant_id=? AND finalized_at IS NOT NULL",tenantId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? AND event_type='SalesVisitFinalized'",tenantId)).isZero();
    }

    @Test void crmEvidenceOutboxRollsBackWithVisitResult() {
        var day=attendanceService.checkIn(new CheckInCommand("crm-rollback-day","crm-test-client",Instant.now(),location("120.1000000","30.2000000"),null,"ONLINE"));
        var capture=UUID.randomUUID();
        var context=new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context("201","101","301","TEST1",capture.toString(),visitPolicyVersionId.toString(),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),"测试店","地址",new BigDecimal("120.1"),new BigDecimal("30.2"),"GCJ02",BigDecimal.ZERO,1,"IN_PROGRESS",new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy(visitPolicyVersionId.toString(),500));
        captureStore.create(tenantId,profileId,day.id(),capture,context,location("120.1000000","30.2000000"),null);
        var command=preparedFieldwork(capture,"101","201",null);
        jdbc.update("DELETE FROM sales_outbox_event WHERE tenant_id=?",bin(tenantId));
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(()->tx.execute(status->{
            visitService.submitVisitResult(capture,command);
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(jdbc.queryForObject("SELECT result_submitted_at FROM sales_visit WHERE tenant_id=? AND id=?",Timestamp.class,bin(tenantId),bin(capture))).isNull();
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? AND event_type='CrmVisitEvidenceChanged'",tenantId)).isZero();
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.rigour.sales.application.port.out.RecordingMediaVerifier mediaVerifier;

    @Test void uploadStartedBeforeCheckoutPublishesCommittedCheckoutAndVerifiedRecording() throws Exception {
        jdbc.update("UPDATE sales_visit_policy_version SET recording_enabled=1,minimum_recording_seconds=0 WHERE id=?",bin(visitPolicyVersionId));
        var now=Instant.now();
        var day=attendanceService.checkIn(new CheckInCommand("race-day","client",now,location("120.1000000","30.2000000"),null,"ONLINE"));
        var capture=UUID.randomUUID();
        var context=new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context("208","108","308","TEST1",capture.toString(),visitPolicyVersionId.toString(),now.minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS),"测试店","地址",new BigDecimal("120.1"),new BigDecimal("30.2"),"GCJ02",BigDecimal.ZERO,1,"IN_PROGRESS",new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy(visitPolicyVersionId.toString(),500));
        captureStore.create(tenantId,profileId,day.id(),capture,context,location("120.1000000","30.2000000"),null);
        visitService.submitVisitResult(capture,preparedFieldwork(capture,"108","208",null));
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{
            entered.countDown();
            if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("upload gate timed out");
            return com.rigour.sales.application.port.out.RecordingMediaVerifier.Verification.verified(35000L,"audio/m4a","pcm-sha256-v1:"+"d".repeat(64));
        }).when(mediaVerifier).verify(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var upload=executor.submit(()->{
                try {setCaller();return recordingService.uploadClip(capture,new MockMultipartFile("file","race.m4a","audio/m4a",new byte[]{1,2,3}),"race-clip",35000L,now.minusSeconds(35),now);}
                finally {clearCaller();}
            });
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                visitService.checkOutVisit(capture,new CheckOutVisitCommand("race-checkout",Instant.now(),location("120.1000000","30.2000000"),"race-out"));
            } finally {release.countDown();}
            upload.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }
        var payload=latestCrmEvidence(capture);
        assertThat(payload.checkedOutAt()).isNotNull();
        assertThat(payload.submittedAt()).isNotNull();
        assertThat(payload.recordingEvidenceStatus()).isEqualTo("TECHNICALLY_VERIFIED");
        assertThat(payload.verifiedRecordingDurationMs()).isEqualTo(35000);
    }

    @Autowired private com.rigour.sales.application.port.out.CrmVisitEvidenceOutbox crmEvidenceOutbox;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Test void crmEvidenceDeliveryRetriesWithoutRecreatingVisit() throws Exception {
        var day=attendanceService.checkIn(new CheckInCommand("crm-delivery-day","crm-test-client",Instant.now(),location("120.1000000","30.2000000"),null,"ONLINE"));
        var capture=UUID.randomUUID();
        var context=new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context("202","102","302","TEST1",capture.toString(),visitPolicyVersionId.toString(),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),"测试店","地址",new BigDecimal("120.1"),new BigDecimal("30.2"),"GCJ02",BigDecimal.ZERO,1,"IN_PROGRESS",new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy(visitPolicyVersionId.toString(),500));
        captureStore.create(tenantId,profileId,day.id(),capture,context,location("120.1000000","30.2000000"),null);
        visitService.cancelVisit(capture,new com.rigour.sales.api.v1.model.SalesWorkApiModels.CancelVisitCommand("误入门店"));
        var status=new java.util.concurrent.atomic.AtomicInteger(503);
        var received=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/crm/store-facts/visits",exchange->{received.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] body="{\"code\":\"OK\",\"data\":null}".getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {
            var keys=new com.rigour.shared.context.ContextTrustProperties();keys.setKeysBase64(java.util.Map.of("v1",java.util.Base64.getEncoder().encodeToString(new byte[32])));
            var worker=new com.rigour.sales.infrastructure.integration.CrmVisitDeliveryScheduler(jdbc,transactionManager,crmEvidenceOutbox,org.springframework.web.client.RestClient.builder(),new com.rigour.shared.context.TrustedContextSigner(keys),java.time.Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort());
            worker.tick();
            assertThat(jdbc.queryForObject("SELECT retry_count FROM sales_outbox_event WHERE tenant_id=? AND event_type='CrmVisitEvidenceChanged'",Integer.class,bin(tenantId))).isEqualTo(1);
            status.set(200);jdbc.update("UPDATE sales_outbox_event SET next_retry_at=NULL WHERE tenant_id=?",bin(tenantId));worker.tick();
            assertThat(jdbc.queryForObject("SELECT publish_status FROM sales_outbox_event WHERE tenant_id=? AND event_type='CrmVisitEvidenceChanged'",String.class,bin(tenantId))).isEqualTo("PUBLISHED");
            jdbc.update("UPDATE sales_visit SET crm_last_enqueued_at=? WHERE tenant_id=? AND id=?",timestamp(Instant.now().minusSeconds(60)),bin(tenantId),bin(capture));
            worker.tick();
            assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? AND event_type='CrmVisitEvidenceChanged'",tenantId)).isEqualTo(1);
            var own=received.stream().filter(value->value.contains(tenantId.toString())).toList();assertThat(own).hasSize(2);assertThat(own.get(0)).isEqualTo(own.get(1));
            assertThat(count("SELECT COUNT(*) FROM sales_visit WHERE tenant_id=?",tenantId)).isEqualTo(1);
        } finally { server.stop(0); }
    }


    private VisitResultCommand preparedFieldwork(UUID capture,String store,String visit,String action) {
        var now=Instant.now();
        evidenceService.uploadStorefrontPhoto(capture,new MockMultipartFile("file","store.jpg","image/jpeg",jpegBytes(Color.GREEN)),"prepared-photo-"+capture,"APP_CAMERA",now,new BigDecimal("120.1"),new BigDecimal("30.2"),BigDecimal.TEN);
        var draft=new com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Draft(7,"OPEN",null,"TYPE","CONTACTED",List.of("4"),true,List.of(),null,null,action,action==null?null:java.time.LocalDate.now().plusDays(1));
        var labels=new java.util.HashMap<String,String>();labels.put("VISIT_CONTACT_OUTCOME:CONTACTED","已沟通");if(action!=null)labels.put("VISIT_NEXT_ACTION:"+action,"发送方案");
        var accepted=new com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Accepted(draft,userId.toString(),"employee",labels,List.of());
        org.mockito.Mockito.doReturn(accepted).when(crmVisit).validateFieldwork(store,visit,draft);
        return new VisitResultCommand("CONTACTED",null,null,null,"现场沟通",draft);
    }

    private UUID createLifecycleCapture(String request) { return createLifecycleCapture(request,null,"200"); }

    private UUID createLifecycleCapture(String request,UUID plan,String crmVisit) {
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var day=plan==null?attendanceService.checkIn(new CheckInCommand(request,"client",now,location("120.1","30.2"),null,"ONLINE")):attendanceService.workDay(java.time.LocalDate.now());
        var capture=UUID.randomUUID();
        captureStore.create(tenantId,profileId,day.id(),capture,new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context(crmVisit,"100","300","TEST1",capture.toString(),visitPolicyVersionId.toString(),now,"跟进测试店","地址",new BigDecimal("120.1"),new BigDecimal("30.2"),"GCJ02",BigDecimal.ZERO,1,"IN_PROGRESS",new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy(visitPolicyVersionId.toString(),500)),location("120.1","30.2"),plan);
        return capture;
    }

    @Test void cancelWithoutFormIsIdempotentAndReleasesTheActiveVisit() {
        var capture=createLifecycleCapture("cancel-day");
        var command=new com.rigour.sales.api.v1.model.SalesWorkApiModels.CancelVisitCommand("误入门店");
        var cancelled=visitService.cancelVisit(capture,command);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");assertThat(cancelled.resultSubmittedAt()).isNull();
        var summary=visitService.activitySummary(java.time.LocalDate.now().minusDays(1),java.time.LocalDate.now().plusDays(1));
        assertThat(summary.totalVisitCount()).isZero();assertThat(summary.completedVisitCount()).isZero();
        var event=latestCrmEvidence(capture);assertThat(event.cancelledAt()).isNotNull();assertThat(event.cancellationReason()).isEqualTo("误入门店");
        visitService.cancelVisit(capture,command);
        assertThat(latestCrmEvidence(capture).revision()).isEqualTo(event.revision());
        assertThat(count("SELECT COUNT(*) FROM sales_visit WHERE tenant_id=? AND status='CHECKED_IN'",tenantId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM sales_visit_plan WHERE tenant_id=?",tenantId)).isZero();
        evidenceService.uploadStorefrontPhoto(capture,new MockMultipartFile("file","saved.jpg","image/jpeg",jpegBytes(Color.BLUE)),"cancelled-photo","APP_CAMERA",cancelled.checkedOutAt(),new BigDecimal("120.1"),new BigDecimal("30.2"),BigDecimal.TEN);
        var discarded=new com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipCommand("cancelled-tail",1000L,cancelled.checkedInAt(),cancelled.checkedInAt().plusSeconds(1),"TOO_SHORT");
        recordingService.discardClip(capture,discarded);
        assertThat(visitService.visit(capture).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->visitService.submitVisitResult(capture,new VisitResultCommand("CONTACTED",null,null,null,null))).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
    }

    @Test void completeRollsBackOnInvalidLocationAndCreatesOneExecutableFollowupOnRetry() {
        var capture=createLifecycleCapture("complete-day");var command=preparedFieldwork(capture,"100","200","PROPOSAL");
        var invalid=new CheckOutVisitCommand("complete-far",Instant.now(),location("121.1","31.2"),"far-event");
        assertThatThrownBy(()->visitService.completeVisit(capture,new com.rigour.sales.api.v1.model.SalesWorkApiModels.CompleteVisitCommand(command,invalid))).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assertThat(captureStore.fieldwork(tenantId,capture,false)).isNull();
        assertThat(count("SELECT COUNT(*) FROM sales_visit_plan WHERE tenant_id=?",tenantId)).isZero();
        var checkout=new CheckOutVisitCommand("complete-near",Instant.now(),location("120.1","30.2"),"near-event");
        var complete=new com.rigour.sales.api.v1.model.SalesWorkApiModels.CompleteVisitCommand(command,checkout);
        var first=visitService.completeVisit(capture,complete);var retry=visitService.completeVisit(capture,complete);
        assertThat(first.status()).isEqualTo("CHECKED_OUT");assertThat(retry.checkedOutAt()).isEqualTo(first.checkedOutAt());
        var plans=visitPlanService.ownPlans(command.fieldwork().nextContactDate()).items();assertThat(plans).hasSize(1);
        var plan=plans.getFirst();assertThat(plan.crmStoreId()).isEqualTo("100");assertThat(plan.actionCode()).isEqualTo("PROPOSAL");assertThat(plan.storeName()).isEqualTo("跟进测试店");
        assertThat(visitPlanService.completeOwnAction(plan.planId()).status()).isEqualTo("COMPLETED");
        assertThat(visitPlanService.completeOwnAction(plan.planId()).version()).isEqualTo(1);
        assertThatThrownBy(()->visitService.cancelVisit(capture,new com.rigour.sales.api.v1.model.SalesWorkApiModels.CancelVisitCommand("取消"))).hasMessageContaining("尚未提交");
    }

    @Test void physicalFollowupReturnsToPlannedOnCancellationAndCompletesWithItsOwnVisit() {
        var origin=createLifecycleCapture("plan-day");var result=preparedFieldwork(origin,"100","200","VISIT");
        visitService.completeVisit(origin,new com.rigour.sales.api.v1.model.SalesWorkApiModels.CompleteVisitCommand(result,new CheckOutVisitCommand("origin-end",Instant.now(),location("120.1","30.2"),"origin-end")));
        var plan=visitPlanService.ownPlans(result.fieldwork().nextContactDate()).items().getFirst();
        assertThatThrownBy(()->visitPlanService.completeOwnAction(plan.planId())).hasMessageContaining("到店");
        assertThatThrownBy(()->createLifecycleCapture("plan-day",UUID.randomUUID(),"201")).isInstanceOf(com.rigour.shared.core.exception.StateConflictException.class);
        var cancelled=createLifecycleCapture("plan-day",plan.planId(),"201");
        assertThat(visitPlanService.ownPlans(plan.plannedDate()).items().getFirst().status()).isEqualTo("IN_PROGRESS");
        jdbc.update("UPDATE sales_visit_policy_version SET capture_rules_json=? WHERE id=?", "{\"automaticCheckout\":true,\"automaticCheckoutRadiusMeters\":800,\"maximumLocationAccuracyMeters\":100,\"requireLocationOnOpen\":false,\"automaticRecording\":false,\"allowPhotoImport\":false,\"allowRecordingImport\":false}",bin(visitPolicyVersionId));
        visitService.autoCheckOutVisit(cancelled,new CheckOutVisitCommand("auto-leave",Instant.now(),new LocationEvidence(new BigDecimal("120.12"),new BigDecimal("30.2"),BigDecimal.TEN,"APP_NATIVE"),"auto-leave"));
        visitService.cancelVisit(cancelled,new com.rigour.sales.api.v1.model.SalesWorkApiModels.CancelVisitCommand("误选"));
        assertThat(visitPlanService.ownPlans(plan.plannedDate()).items().getFirst().status()).isEqualTo("PLANNED");
        var retry=createLifecycleCapture("plan-day",plan.planId(),"202");
        var complete=new com.rigour.sales.api.v1.model.SalesWorkApiModels.CompleteVisitCommand(preparedFieldwork(retry,"100","202",null),new CheckOutVisitCommand("plan-end",Instant.now(),location("120.1","30.2"),"plan-end"));
        visitService.completeVisit(retry,complete);
        assertThat(visitPlanService.ownPlans(plan.plannedDate()).items().getFirst().status()).isEqualTo("COMPLETED");
    }

    private com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence latestCrmEvidence(UUID capture) {
        String payload=jdbc.queryForObject("SELECT payload_json FROM sales_outbox_event WHERE tenant_id=? AND aggregate_id=? AND event_type='CrmVisitEvidenceChanged' ORDER BY aggregate_version DESC LIMIT 1",String.class,bin(tenantId),bin(capture));
        return new tools.jackson.databind.ObjectMapper().readValue(payload,com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence.class);
    }

    private void seedVisitPolicy(Instant now) {
        jdbc.update("""
                INSERT INTO sales_visit_policy
                    (id, tenant_id, policy_code, policy_name, status, version, created_at, updated_at)
                VALUES (?, ?, 'VISIT-TEST', '测试拜访规则', 'ACTIVE', 1, ?, ?)
                """, bin(visitPolicyId), bin(tenantId), timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO sales_visit_policy_version
                    (id, tenant_id, policy_id, version_no, publish_status,
                     require_assigned_target, allow_prospect_target, check_in_radius_meters,
                     minimum_dwell_minutes, required_photo_count, recording_enabled,
                     minimum_recording_seconds, maximum_clip_gap_seconds,
                     ai_asr_enabled, ai_relevance_enabled, ai_duplicate_enabled,
                     ai_auto_confirm_threshold, effective_from, effective_to,
                     approved_by, approved_at, created_by, created_at)
                VALUES (?, ?, ?, 1, 'PUBLISHED', 1, 1, 500, 0, 1, 0, 0, 30,
                        1, 1, 1, NULL, ?, NULL, NULL, NULL, ?, ?)
                """, bin(visitPolicyVersionId), bin(tenantId), bin(visitPolicyId),
                timestamp(now.minusSeconds(60)), bin(userId), timestamp(now));
    }

    private void setAnalysisCaller(UUID tenant, UUID user, Set<String> permissions) {
        try {
            var caller=new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of("SALES"),permissions);
            Method set=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);
            set.setAccessible(true);
            set.invoke(null,caller);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private void setCaller() {
        try {
            CallerIdentity caller = new CallerIdentity("TENANT", userId, tenantId, userId, null,
                    UUID.randomUUID(), 1, 1, 1, Set.of("SALES"), Set.of(
                    "sales:context:read", "sales:visit-target:read", "sales:work-day:write",
                    "sales:location:write", "sales:visit:own:read", "sales:visit:own:write", "sales:poi:read",
                    "sales:recording:own:read", "sales:recording:own:write", "sales:track:own:read",
                    "sales:evidence:own:read", "sales:evidence:own:write", "sales:evidence:sensitive:read",
                    "sales:dashboard:read", "sales:visit:review", "sales:risk:read", "sales:risk:write", "sales:recording:sensitive:play",
                    "sales:location:sensitive:read", "sales:visit-plan:own:read",
                    "sales:visit-plan:read", "sales:visit-plan:write"));
            Method set = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
            set.setAccessible(true);
            set.invoke(null, caller);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法设置测试调用人", error);
        }
    }

    private int count(String sql, UUID tenant) {
        Integer value = jdbc.queryForObject(sql, Integer.class, bin(tenant));
        return value == null ? 0 : value;
    }

    private int count(String sql, UUID tenant, UUID second) {
        Integer value = jdbc.queryForObject(sql, Integer.class, bin(tenant), bin(second));
        return value == null ? 0 : value;
    }

    private int count(String sql, UUID tenant, UUID profile, UUID store) {
        Integer value = jdbc.queryForObject(sql, Integer.class, bin(tenant), bin(profile), bin(store));
        return value == null ? 0 : value;
    }

    private static LocationEvidence location(String longitude, String latitude) {
        return new LocationEvidence(new BigDecimal(longitude), new BigDecimal(latitude),
                new BigDecimal("15"), "FEISHU");
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static byte[] adtsFrames(int frameCount) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(frameCount * 9);
        int frameLength = 9;
        for (int index = 0; index < frameCount; index++) {
            output.write(0xff);
            output.write(0xf1);
            output.write((1 << 6) | (4 << 2));
            output.write((2 << 6) | ((frameLength >>> 11) & 0x03));
            output.write((frameLength >>> 3) & 0xff);
            output.write(((frameLength & 0x07) << 5) | 0x1f);
            output.write(0xfc);
            output.write(index & 0xff);
            output.write((index >>> 8) & 0xff);
        }
        return output.toByteArray();
    }

    private static byte[] jpegBytes(Color color) {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpg", output)) {
                throw new IllegalStateException("测试环境缺少JPEG编码器");
            }
            return output.toByteArray();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("测试JPEG生成失败", error);
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.writeBytes(part);
        return output.toByteArray();
    }

    private static byte[] bin(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static String utcJdbcUrl(MySQLContainer container) {
        return container.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
