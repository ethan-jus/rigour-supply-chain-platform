package com.rigour.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationPointCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayTrackView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayView;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    void checkInAfterCheckOutReopensWorkDayAndTrackIsQueryable() {
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
                        new BigDecimal("30.2001000"), new BigDecimal("12"), Instant.now(), "FEISHU"))));

        WorkDayView finished = attendanceService.checkOut(first.id(),
                new CheckOutCommand("reopen-check-out-1", Instant.now(), location, null, "ONLINE"));
        assertThat(finished.status()).isEqualTo("FINISHED");
        int firstSpanMinutes = finished.verifiedWorkMinutes();

        // 关键回归：签退后同一业务日允许重新签到，重开同一工作日而不是报错。
        WorkDayView reopened = attendanceService.checkIn(
                new CheckInCommand("reopen-check-in-2", "client-1", Instant.now(), location,
                        null, "ONLINE"));
        assertThat(reopened.id()).isEqualTo(first.id());
        assertThat(reopened.status()).isEqualTo("ACTIVE");
        assertThat(reopened.checkedInAt()).isEqualTo(first.checkedInAt());
        assertThat(reopened.locationSessionId()).isNotEqualTo(first.locationSessionId());

        // 重开后的定位点进入新会话，轨迹查询能看到两段事实。
        var secondBatch = attendanceService.uploadLocationPoints(first.id(),
                new LocationBatchCommand("reopen-location-2",
                        List.of(new LocationPointCommand("reopen-point-2", new BigDecimal("120.1002000"),
                                new BigDecimal("30.2002000"), new BigDecimal("18"), Instant.now(), "FEISHU"))));
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
        assertThat(track.totalDistanceMeters()).isGreaterThan(0);
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
