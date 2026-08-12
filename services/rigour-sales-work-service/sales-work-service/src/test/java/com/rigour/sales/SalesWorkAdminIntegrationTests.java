package com.rigour.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.AssignmentCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldPolicyCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.IdentityBindingCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.SalesProfileCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.StoreProjectionCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.VisitPolicyCommand;
import com.rigour.sales.application.service.SalesWorkAdminService;
import com.rigour.sales.application.service.SalesWorkContextService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalTime;
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

/** 验证销售管理维护 API 从空库到 H5 上下文可用的前置链路。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SalesWorkAdminIntegrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work_admin")
            .withUsername("sales_work_admin_test")
            .withPassword("rigour_sales_work_admin_test_password");

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
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID salesUserId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SalesWorkAdminService adminService;

    @Autowired
    private SalesWorkContextService contextService;

    @BeforeEach
    void setAdminCaller() {
        setCaller(adminUserId, Set.of(
                "sales:identity:bind", "sales:profile:write", "sales:policy:write",
                "sales:policy:publish", "sales:store-projection:write", "sales:assignment:write"));
    }

    @AfterEach
    void clearCaller() throws Exception {
        Method clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    @Test
    void adminMaintenanceMakesSalesContextAndTargetsResolvable() {
        var binding = adminService.bindIdentity(new IdentityBindingCommand(salesUserId, employeeId));
        assertThat(binding.status()).isEqualTo("ACTIVE");
        // 幂等覆盖：重复绑定同一用户不产生第二行。
        adminService.bindIdentity(new IdentityBindingCommand(salesUserId, employeeId));
        Integer bindingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_identity_projection WHERE tenant_id=?",
                Integer.class, bin(tenantId));
        assertThat(bindingCount).isEqualTo(1);
        Integer activeUserBindingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_identity_projection "
                        + "WHERE tenant_id=? AND platform_user_id=? AND status='ACTIVE' "
                        + "AND effective_from <= UTC_TIMESTAMP(6) "
                        + "AND (effective_to IS NULL OR effective_to > UTC_TIMESTAMP(6))",
                Integer.class, bin(tenantId), bin(salesUserId));
        assertThat(activeUserBindingCount).isEqualTo(1);

        var profile = adminService.upsertSalesProfile(
                new SalesProfileCommand(employeeId, "S-ADMIN-001", null));
        assertThat(profile.status()).isEqualTo("ACTIVE");

        var fieldPolicy = adminService.upsertFieldPolicy(new FieldPolicyCommand(
                "FIELD-ADMIN", "默认外勤规则", "Asia/Shanghai",
                LocalTime.of(4, 0), null, null, null, null,
                480, 240, true, true, 24, true, 20, new BigDecimal("100"), 120,
                "ALL", null, true));
        assertThat(fieldPolicy.publishStatus()).isEqualTo("PUBLISHED");
        assertThat(fieldPolicy.versionNo()).isEqualTo(1);

        var visitPolicy = adminService.upsertVisitPolicy(new VisitPolicyCommand(
                "VISIT-ADMIN", "默认拜访规则", false, true, 500, 5, 1,
                true, 600, 30, true, true, true, null, "ALL", null, true));
        assertThat(visitPolicy.publishStatus()).isEqualTo("PUBLISHED");

        // 同 policyCode 再发一版：版本号递增。
        var fieldPolicyV2 = adminService.upsertFieldPolicy(new FieldPolicyCommand(
                "FIELD-ADMIN", "默认外勤规则V2", "Asia/Shanghai",
                LocalTime.of(4, 0), null, null, null, null,
                480, 240, true, true, 24, true, 20, new BigDecimal("100"), 120,
                "ALL", null, true));
        assertThat(fieldPolicyV2.versionNo()).isEqualTo(2);

        adminService.upsertStoreProjection(new StoreProjectionCommand(
                storeId, customerId, "测试客户", "测试门店", "测试地址",
                new BigDecimal("120.1000000"), new BigDecimal("30.2000000"), "ACTIVE"));
        var assignment = adminService.upsertAssignment(
                new AssignmentCommand(profile.id(), storeId, customerId, "PRIMARY"));
        assertThat(assignment.status()).isEqualTo("ACTIVE");
        // 重复归属不重复插入。
        adminService.upsertAssignment(new AssignmentCommand(profile.id(), storeId, customerId, "PRIMARY"));
        Integer assignmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_sales_assignment_projection WHERE tenant_id=?",
                Integer.class, bin(tenantId));
        assertThat(assignmentCount).isEqualTo(1);

        // 切换为销售本人：上下文、目标和规则解析全部可用（最新发布的 V2 生效）。
        setCaller(salesUserId, Set.of("sales:context:read", "sales:visit-target:read"));
        var context = contextService.context();
        assertThat(context.salesProfileId()).isEqualTo(profile.id());
        assertThat(context.fieldPolicy().versionNo()).isEqualTo(2);
        assertThat(contextService.visitTargets(null, 1, 20).total()).isEqualTo(1);

        // 审计链路：管理写入全部留痕。
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_audit_log WHERE tenant_id=?",
                Integer.class, bin(tenantId));
        assertThat(auditCount).isGreaterThanOrEqualTo(5);
    }

    @Test
    void publishWithoutPublishPermissionIsRejected() {
        setCaller(adminUserId, Set.of("sales:policy:write"));
        assertThatThrownBy(() -> adminService.upsertFieldPolicy(new FieldPolicyCommand(
                "FIELD-NO-PUBLISH", "无发布权限规则", "Asia/Shanghai",
                LocalTime.of(4, 0), null, null, null, null,
                480, 240, true, true, 24, true, 20, null, 120, "ALL", null, true)))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void enabledRecordingPolicyRejectsZeroMinimumDuration() {
        assertThatThrownBy(() -> adminService.upsertVisitPolicy(new VisitPolicyCommand(
                "VISIT-ZERO-RECORDING", "错误录音规则", false, true, 500, 5, 1,
                true, 0, 30, true, true, true, null, "ALL", null, true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_ADMIN_INVALID));
    }

    @Test
    void assignmentRequiresExistingProfile() {
        assertThatThrownBy(() -> adminService.upsertAssignment(
                new AssignmentCommand(UUID.randomUUID(), storeId, customerId, "PRIMARY")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.SALES_ADMIN_TARGET_NOT_FOUND));
    }

    private void setCaller(UUID userId, Set<String> permissions) {
        try {
            CallerIdentity caller = new CallerIdentity("TENANT", userId, tenantId, userId, null,
                    UUID.randomUUID(), 1, 1, 1, Set.of("SALES_ADMIN"), permissions);
            Method set = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
            set.setAccessible(true);
            set.invoke(null, caller);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法设置测试调用人", error);
        }
    }

    private static byte[] bin(UUID value) {
        return java.nio.ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static String utcJdbcUrl(MySQLContainer container) {
        return container.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
