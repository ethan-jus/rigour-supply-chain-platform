package com.rigour.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.InterruptionCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationPointCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayView;
import com.rigour.sales.application.service.SalesWorkAttendanceService;
import com.rigour.sales.application.service.SalesWorkContextService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.lang.reflect.Method;
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

/** 使用隔离MySQL验证阶段2从身份投影到日结候选的真实事务闭环。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SalesWorkAttendanceIntegrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work_attendance")
            .withUsername("sales_work_att_test")
            .withPassword("rigour_sales_work_attendance_test_password");

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
    private final UUID customerId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID storeProjectionId = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SalesWorkAttendanceService attendanceService;

    @Autowired
    private SalesWorkContextService contextService;

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
                VALUES (?, ?, ?, 'S-TEST-001', NULL, 'ACTIVE', UTC_TIMESTAMP(6) - INTERVAL 60 SECOND,
                        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bin(profileId), bin(tenantId), bin(employeeId));
        jdbc.update("""
                INSERT INTO sales_field_policy
                    (id, tenant_id, policy_code, policy_name, status, version, created_at, updated_at)
                VALUES (?, ?, 'FIELD-TEST', '测试外勤规则', 'ACTIVE', 1, ?, ?)
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
        jdbc.update("""
                INSERT INTO crm_store_projection
                    (id, tenant_id, customer_id, store_id, customer_name, store_name,
                     store_address, longitude, latitude, store_status, source_version,
                     source_updated_at, projected_at)
                VALUES (?, ?, ?, ?, '测试客户', '测试门店', '测试地址', 120.1000000, 30.2000000,
                        'ACTIVE', 1, ?, ?)
                """, bin(storeProjectionId), bin(tenantId), bin(customerId), bin(storeId),
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
    void contextTargetsAndAttendanceFactsAreRealAndIdempotent() {
        assertThat(contextService.context().salesProfileId()).isEqualTo(profileId);
        assertThat(contextService.visitTargets(null, 1, 20).total()).isEqualTo(1);

        Instant clientTime = Instant.now();
        LocationEvidence location = new LocationEvidence(
                new java.math.BigDecimal("120.1000000"), new java.math.BigDecimal("30.2000000"),
                new java.math.BigDecimal("15"), "FEISHU");
        CheckInCommand checkIn = new CheckInCommand("check-in-1", "client-1", clientTime, location,
                null, "ONLINE");
        WorkDayView active = attendanceService.checkIn(checkIn);
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.fieldPolicyVersionId()).isEqualTo(policyVersionId);

        WorkDayView replayedCheckIn = attendanceService.checkIn(checkIn);
        assertThat(replayedCheckIn.id()).isEqualTo(active.id());

        LocationBatchCommand points = new LocationBatchCommand("location-1", List.of(
                new LocationPointCommand("device-point-1", new java.math.BigDecimal("120.1001000"),
                        new java.math.BigDecimal("30.2001000"), new java.math.BigDecimal("12"), clientTime, "FEISHU"),
                new LocationPointCommand("device-point-2", new java.math.BigDecimal("120.1002000"),
                        new java.math.BigDecimal("30.2002000"), new java.math.BigDecimal("18"), clientTime, "FEISHU")));
        var locationResult = attendanceService.uploadLocationPoints(active.id(), points);
        assertThat(locationResult.acceptedCount()).isEqualTo(2);
        assertThat(attendanceService.uploadLocationPoints(active.id(), points).acceptedCount()).isEqualTo(2);

        WorkDayView interrupted = attendanceService.reportInterruption(active.id(),
                new InterruptionCommand("interruption-1", "PAGE_HIDDEN", clientTime,
                        clientTime.plusSeconds(5), null, "测试中断"));
        assertThat(interrupted.interruptionCount()).isEqualTo(1);

        CheckOutCommand checkOut = new CheckOutCommand("check-out-1", clientTime.plusSeconds(3600), location,
                null, "ONLINE");
        WorkDayView finished = attendanceService.checkOut(active.id(), checkOut);
        assertThat(finished.status()).isEqualTo("FINISHED");
        assertThat(finished.locationPointCount()).isEqualTo(2);
        assertThat(finished.interruptionCount()).isEqualTo(1);
        assertThat(finished.verifiedWorkMinutes()).isGreaterThanOrEqualTo(0);
        assertThat(attendanceService.checkOut(active.id(), checkOut).id()).isEqualTo(active.id());

        assertThat(count("SELECT COUNT(*) FROM sales_punch_event WHERE tenant_id=?", tenantId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sales_location_point WHERE tenant_id=?", tenantId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sales_work_interruption WHERE tenant_id=?", tenantId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales_work_day_summary WHERE tenant_id=?", tenantId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales_outbox_event WHERE tenant_id=?", tenantId)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM sales_audit_log WHERE tenant_id=?", tenantId)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM sales_idempotency_record WHERE tenant_id=?", tenantId)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM sales_location_session WHERE tenant_id=? AND status='CLOSED'", tenantId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales_work_day WHERE tenant_id=? AND status='FINISHED'", tenantId)).isEqualTo(1);

        CheckInCommand changedReplay = new CheckInCommand("check-in-1", "client-1", clientTime,
                new LocationEvidence(new java.math.BigDecimal("120.1000010"),
                        new java.math.BigDecimal("30.2000000"), new java.math.BigDecimal("15"), "FEISHU"),
                null, "ONLINE");
        assertThatThrownBy(() -> attendanceService.checkIn(changedReplay))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_IDEMPOTENCY_CONFLICT));
        LocationBatchCommand afterCheckout = new LocationBatchCommand("location-after-checkout", points.points());
        assertThatThrownBy(() -> attendanceService.uploadLocationPoints(active.id(), afterCheckout))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_WORK_DAY_INVALID_STATE));
    }

    private void setCaller() {
        try {
            CallerIdentity caller = new CallerIdentity("TENANT", userId, tenantId, userId, null,
                    UUID.randomUUID(), 1, 1, 1, Set.of("SALES"), Set.of(
                    "sales:context:read", "sales:visit-target:read", "sales:work-day:write", "sales:location:write"));
            Method set = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
            set.setAccessible(true);
            set.invoke(null, caller);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法设置测试调用人", error);
        }
    }

    private int count(String sql, UUID tenant) {
        Integer count = jdbc.queryForObject(sql, Integer.class, bin(tenant));
        return count == null ? 0 : count;
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
