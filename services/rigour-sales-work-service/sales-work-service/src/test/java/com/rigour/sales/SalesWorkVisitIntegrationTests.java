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
import com.rigour.sales.application.service.SalesWorkAttendanceService;
import com.rigour.sales.application.service.SalesWorkRecordingService;
import com.rigour.sales.application.service.SalesWorkManagementService;
import com.rigour.sales.application.service.SalesWorkVisitService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
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
        registry.add("rigour.amap.web-key", () -> "test-amap-key");
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
    private SalesWorkManagementService managementService;

    @BeforeEach
    void seed() {
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
    void visitFlowCoversMyStorePoiRadiusAndPoiProjection() {
        WorkDayView workDay = attendanceService.checkIn(new CheckInCommand("visit-check-in-day-1",
                "client-1", Instant.now(), location("120.1000000", "30.2000000"), null, "ONLINE"));
        assertThat(workDay.status()).isEqualTo("ACTIVE");

        Instant visitReceivedAt = Instant.now();
        VisitView myStore = visitService.createVisit(new CreateVisitCommand("visit-create-my-store-1",
                workDay.id(), "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                visitReceivedAt, "device-visit-check-in-1"));
        assertThat(myStore.status()).isEqualTo("CHECKED_IN");
        assertThat(myStore.targetSnapshot().storeId()).isEqualTo(storeId);
        assertThat(myStore.targetSnapshot().customerName()).isEqualTo("测试客户");

        VisitView replayed = visitService.createVisit(new CreateVisitCommand("visit-create-my-store-1",
                workDay.id(), "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
                visitReceivedAt, "device-visit-check-in-1"));
        assertThat(replayed.id()).isEqualTo(myStore.id());

        VisitView outside = null;
        try {
            outside = visitService.createVisit(new CreateVisitCommand("visit-create-my-store-outside",
                    workDay.id(), "MY_STORE", storeId, null,
                    location("121.5000000", "31.5000000"), Instant.now(), "device-visit-outside-1"));
        } catch (BusinessException error) {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_VISIT_OUTSIDE_RADIUS);
        }
        assertThat(outside).isNull();

        PoiTargetCommand poi = new PoiTargetCommand("TESTPOI-0001", "附近测试便利店", "科技园路88号",
                new BigDecimal("120.1100000"), new BigDecimal("30.2100000"), new BigDecimal("80"));
        assertThatThrownBy(() -> visitService.createVisit(new CreateVisitCommand("visit-create-poi-overlap",
                workDay.id(), "POI", null, poi, location("120.1100000", "30.2100000"),
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
                workDay.id(), "POI", null, poi, location("120.1100000", "30.2100000"),
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

        var track = attendanceService.track(workDay.businessDate());
        assertThat(track.visits()).hasSize(2);
        assertThat(track.visits().get(0).storeName()).isEqualTo("我的测试门店");
        assertThat(track.visits().get(1).storeName()).isEqualTo("附近测试便利店");
        assertThat(track.segments()).singleElement()
                .satisfies(segment -> assertThat(segment.distanceSource()).isEqualTo("STRAIGHT_LINE"));
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
        assertThat(count("SELECT COUNT(*) FROM sales_visit_review WHERE tenant_id=?", tenantId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? "
                + "AND event_type='SalesVisitFinalized'", tenantId)).isEqualTo(1);

        // Outbox 回归：拜访创建/签退事件必须成功写入，聚合版本路由到 sales_visit 而不是 sales_work_day。
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? "
                + "AND aggregate_type='SALES_VISIT' AND event_type='SalesVisitCreated'", tenantId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=? "
                + "AND aggregate_type='SALES_VISIT' AND event_type='SalesVisitCheckedOut'", tenantId)).isEqualTo(2);
    }

    @Test
    void visitResultAndRecordingClipArePersisted() {
        WorkDayView workDay = attendanceService.checkIn(new CheckInCommand("result-check-in-day-1",
                "client-1", Instant.now(), location("120.1000000", "30.2000000"), null, "ONLINE"));
        VisitView visit = visitService.createVisit(new CreateVisitCommand("result-visit-create-1",
                workDay.id(), "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
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
        var firstFile = new MockMultipartFile("file", "clip-0.m4a", "audio/m4a",
                "fake-audio-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));

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

        Instant secondTo = Instant.now();
        var second = recordingService.uploadClip(visit.id(),
                new MockMultipartFile("file", "clip-1.m4a", "audio/m4a",
                        "fake-audio-bytes-2".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "device-clip-1", 30_000L, secondTo.minusSeconds(30), secondTo);
        assertThat(second.clipIndex()).isEqualTo(1);

        var session = recordingService.recordings(visit.id());
        assertThat(session.clipCount()).isEqualTo(2);
        assertThat(session.clips()).hasSize(2);
        assertThat(session.uploadedTotalDurationMs()).isEqualTo(65_000L);
        assertThat(session.minimumClipSeconds()).isEqualTo(30);
        assertThat(session.verifiedTotalDurationMs()).isZero();

        VisitView checkedOut = visitService.visit(visit.id());
        assertThat(checkedOut.kpName()).isEqualTo("王店长");
        var managementRecordings = managementService.reviewRecordings(visit.id());
        assertThat(managementRecordings.clipCount()).isEqualTo(2);
        assertThat(managementRecordings.uploadedTotalDurationMs()).isEqualTo(65_000L);
        var recordingContent = managementService.reviewRecordingClip(visit.id(), clip.clipId());
        assertThat(recordingContent.mediaType()).isEqualTo("audio/m4a");
        assertThat(recordingContent.bytes()).isEqualTo("fake-audio-bytes"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 签退后补录结果仍允许。
        VisitView updated = visitService.submitVisitResult(visit.id(),
                new VisitResultCommand("CONTACTED", "王店长", "13800001234", "MEDIUM", "复访跟进"));
        assertThat(updated.intentionLevel()).isEqualTo("MEDIUM");

        // 关门/KP不在等未接触场景不伪造KP资料，保存现场说明后可直接离店。
        VisitView closedStoreVisit = visitService.createVisit(new CreateVisitCommand("closed-store-create-1",
                workDay.id(), "MY_STORE", storeId, null, location("120.1000000", "30.2000000"),
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
    void cannotCreateVisitWithoutActiveWorkDay() {
        assertThatThrownBy(() -> visitService.createVisit(new CreateVisitCommand("visit-no-day-1",
                UUID.randomUUID(), "MY_STORE", storeId, null,
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
                VALUES (?, ?, ?, 1, 'PUBLISHED', 1, 1, 500, 0, 0, 0, 0, 30,
                        1, 1, 1, NULL, ?, NULL, NULL, NULL, ?, ?)
                """, bin(visitPolicyVersionId), bin(tenantId), bin(visitPolicyId),
                timestamp(now.minusSeconds(60)), bin(userId), timestamp(now));
    }

    private void setCaller() {
        try {
            CallerIdentity caller = new CallerIdentity("TENANT", userId, tenantId, userId, null,
                    UUID.randomUUID(), 1, 1, 1, Set.of("SALES"), Set.of(
                    "sales:context:read", "sales:visit-target:read", "sales:work-day:write",
                    "sales:location:write", "sales:visit:own:read", "sales:visit:own:write", "sales:poi:read",
                    "sales:recording:own:read", "sales:recording:own:write", "sales:track:own:read",
                    "sales:dashboard:read", "sales:visit:review", "sales:recording:sensitive:play",
                    "sales:location:sensitive:read"));
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

    private static byte[] bin(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static String utcJdbcUrl(MySQLContainer container) {
        return container.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
