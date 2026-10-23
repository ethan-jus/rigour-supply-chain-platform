package com.rigour.sales;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.*;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource;
import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource.Employee;
import com.rigour.sales.application.service.*;
import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 一次性 MySQL；IAM/HR 在端口处替身，真实 HTTP 签名另由协议测试覆盖。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SalesEmployeeProfileIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("sales_employee_profile").withUsername("test").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        r.add("spring.flyway.enabled", () -> true);
    }
    @Autowired SalesEmployeeProfileService profiles;
    @Autowired SalesWorkContextService context;
    @Autowired SalesWorkAdminService admin;
    @Autowired SalesWorkAttendanceService attendance;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SalesEmployeeIdentitySource source;
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final Employee employee = new Employee(9007199254740993L, "EMP-01", "测试员工一", 3, 4, 5);
    final Set<String> permissions = Set.of("sales:context:read", "sales:visit-target:read", "sales:work-day:write");

    @BeforeEach void setup() throws Exception {
        caller(user, permissions);
        when(source.current(any())).thenReturn(employee);
    }
    @AfterEach void clear() throws Exception { setCaller(null); }

    @Test void readDoesNotRegisterAndExplicitRegistrationIsIdempotentAndAudited() {
        assertThat(profiles.profile().status()).isEqualTo("UNREGISTERED");
        assertThat(count("sales_profile")).isZero();
        var profile = profiles.register();
        assertThat(profile.hrEmployeeId()).isEqualTo("9007199254740993");
        assertThat(profiles.register().salesProfileId()).isEqualTo(profile.salesProfileId());
        assertThat(count("sales_profile")).isEqualTo(1);
        assertThat(count("sales_identity_projection")).isZero();
        assertThat(count("sales_work_day")).isZero();
        assertThat(count("sales_audit_log")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT employee_id FROM sales_profile WHERE tenant_id=?", byte[].class, bin(tenant))).isNull();
        assertThat(context.visitTargets(null, 1, 20).total()).isZero();
        verify(source, times(4)).current(any());
    }

    @Test void concurrentRegistrationCreatesOneProfile() throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<UUID> task = () -> {
                caller(user, permissions);
                try { gate.await(); return profiles.register().salesProfileId(); }
                finally { setCaller(null); }
            };
            var first = pool.submit(task); var second = pool.submit(task); gate.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        }
        assertThat(count("sales_profile")).isEqualTo(1);
    }

    @Test void sameEmployeeCannotBeClaimedByAnotherAccount() throws Exception {
        profiles.register();
        caller(UUID.randomUUID(), permissions);
        fails(profiles::register, ErrorCode.SALES_EMPLOYEE_BINDING_CONFLICT);
        assertThat(count("sales_profile")).isEqualTo(1);
        assertThat(count("sales_audit_log")).isEqualTo(1);
    }

    @Test void disabledProfileCannotBeReactivatedByRegistration() {
        profiles.register();
        jdbc.update("UPDATE sales_profile SET status='INACTIVE' WHERE tenant_id=?", bin(tenant));
        fails(profiles::register, ErrorCode.SALES_PROFILE_INACTIVE);
        fails(() -> context.visitTargets(null, 1, 20), ErrorCode.SALES_PROFILE_INACTIVE);
        assertThat(jdbc.queryForObject("SELECT status FROM sales_profile WHERE tenant_id=?", String.class, bin(tenant))).isEqualTo("INACTIVE");
    }

    @Test void changedIamEmployeeDoesNotInheritPreviousProfileOrTargets() {
        profiles.register();
        when(source.current(any())).thenReturn(new Employee(102, "EMP-02", "测试员工二", 1, 4, 5));
        fails(profiles::register, ErrorCode.SALES_EMPLOYEE_BINDING_CONFLICT);
        fails(() -> context.visitTargets(null, 1, 20), ErrorCode.SALES_EMPLOYEE_BINDING_CONFLICT);
        assertThat(count("sales_profile")).isEqualTo(1);
    }

    @Test void hrDisabledOrUnavailableIsNeverReplacedByCachedActiveProfile() {
        profiles.register();
        when(source.current(any())).thenThrow(new BusinessException(ErrorCode.SALES_PROFILE_INACTIVE));
        fails(() -> context.visitTargets(null, 1, 20), ErrorCode.SALES_PROFILE_INACTIVE);
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(source).current(any());
        assertThatThrownBy(() -> context.visitTargets(null, 1, 20))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void accountWithoutPermissionCannotRegister() throws Exception {
        caller(user, Set.of());
        assertThatThrownBy(profiles::register).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(source);
        assertThat(count("sales_profile")).isZero();
    }

    @Test void historicalUuidBindingRequiresExplicitReconciliation() throws Exception {
        caller(user, Set.of("sales:identity:bind"));
        UUID legacyEmployee = UUID.randomUUID();
        admin.bindIdentity(new IdentityBindingCommand(user, legacyEmployee));
        caller(user, permissions);
        fails(profiles::register, ErrorCode.SALES_EMPLOYEE_BINDING_CONFLICT);
        assertThat(count("sales_profile")).isZero();
        assertThat(jdbc.queryForObject("SELECT employee_id FROM sales_identity_projection WHERE tenant_id=?",
                byte[].class, bin(tenant))).isEqualTo(bin(legacyEmployee));
    }

    @Test void twoAccountsOnlySeeAssignedTargetsAndWorkDayKeepsRealHrId() throws Exception {
        var first = profiles.register();
        UUID other = UUID.randomUUID();
        var otherEmployee = new Employee(102, "EMP-02", "测试员工二", 1, 4, 5);
        when(source.current(any())).thenAnswer(inv -> ((CallerIdentity) inv.getArgument(0)).userId().equals(user) ? employee : otherEmployee);
        caller(other, permissions);
        profiles.register();
        caller(user, Set.of("sales:policy:write", "sales:policy:publish", "sales:store-projection:write", "sales:assignment:write"));
        admin.upsertFieldPolicy(new FieldPolicyCommand("DEFAULT", "外勤", "Asia/Shanghai", LocalTime.MIDNIGHT,
                null, null, null, null, 480, 240, false, false, null, false, 20, null, 120, "ALL", null, true));
        UUID store = UUID.randomUUID();
        admin.upsertStoreProjection(new StoreProjectionCommand(store, null, "客户", "门店一", "地址", null, null, "ACTIVE"));
        admin.upsertAssignment(new AssignmentCommand(first.salesProfileId(), store, null, "PRIMARY"));
        caller(other, permissions);
        assertThat(context.visitTargets(null, 1, 20).total()).isZero();
        caller(user, permissions);
        assertThat(context.visitTargets(null, 1, 20).items()).extracting(t -> t.storeId()).containsExactly(store);
        assertThat(context.context().hrEmployeeId()).isEqualTo("9007199254740993");
        var day = attendance.checkIn(new CheckInCommand("test-checkin", "test-device", Instant.now(), null, null, null));
        assertThat(day.employeeId()).isNull();
        assertThat(day.hrEmployeeId()).isEqualTo("9007199254740993");
        assertThat(jdbc.queryForObject("SELECT hr_employee_id FROM sales_work_day WHERE tenant_id=?", Long.class, bin(tenant)))
                .isEqualTo(employee.id());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Long.class, bin(tenant));
    }
    private static void fails(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    private void caller(UUID userId, Set<String> p) throws Exception {
        setCaller(new CallerIdentity("TENANT", userId, tenant, userId, null, UUID.randomUUID(), 1, 1, 1, Set.of(), p));
    }
    private static void setCaller(CallerIdentity caller) throws Exception {
        var method = caller == null ? AuthorizationContext.class.getDeclaredMethod("clear")
                : AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        method.setAccessible(true);
        if (caller == null) method.invoke(null); else method.invoke(null, caller);
    }
    private static byte[] bin(UUID value) { return SalesUuidCodec.encode(value); }
}
