package com.rigour.merchant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.api.v1.CustomerMemberResponsibilityApi.*;
import com.rigour.merchant.api.v1.CustomerResponsibilityApi.Change;
import com.rigour.merchant.application.port.out.*;
import com.rigour.merchant.infrastructure.persistence.CrmUuidCodec;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CustomerMemberResponsibilityIntegrationTests {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("crm_assignment")
                    .withUsername("crm_test")
                    .withPassword("crm_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.flyway.url", MYSQL::getJdbcUrl);
        r.add("spring.flyway.user", MYSQL::getUsername);
        r.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerMemberResponsibilityStore store;
    @Autowired CustomerResponsibilityStore single;
    @Autowired CrmInternalCustomerStore customers;
    @MockitoBean CustomerAssignmentTargetClient targets;
    @MockitoBean CrmEmployeeClient employees;
    @MockitoBean SupplyAuthorizationClient authorizations;

    @Test
    void candidatesAreScopedBeforeCountAndPagingAndOwnedKeepsOutOfLimitCustomers() {
        try (var f = new Fixture()) {
            long hz = f.customer("杭州客户", "HZ", null), child = f.customer("杭州下级", "HZ_CHILD", null);
            f.customer("宁波客户", "NB", null);
            long owned = f.customer("待移交越界客户", "NB", "EMP-TARGET");
            var p =
                    store.customers(
                            f.tenant.toString(),
                            f.user,
                            "CANDIDATES",
                            null,
                            null,
                            null,
                            null,
                            1,
                            1,
                            "crm:customer:assign-owner");
            assertThat(p.filters().regions())
                    .extracting(Option::code)
                    .containsExactlyInAnyOrder("HZ", "HZ_CHILD");
            assertThat(p.total()).isEqualTo(2);
            assertThat(p.items()).hasSize(1);
            assertThat(p.items().getFirst().customerId()).isEqualTo(hz);
            assertThat(
                            store.customers(
                                            f.tenant.toString(),
                                            f.user,
                                            "CANDIDATES",
                                            null,
                                            null,
                                            null,
                                            null,
                                            2,
                                            1,
                                            "crm:customer:assign-owner")
                                    .items()
                                    .getFirst()
                                    .customerId())
                    .isEqualTo(child);
            var actual =
                    store.customers(
                            f.tenant.toString(),
                            f.user,
                            "OWNED",
                            null,
                            null,
                            null,
                            null,
                            1,
                            20,
                            "crm:customer:read");
            assertThat(actual.items()).extracting(Customer::customerId).containsExactly(owned);
            f.actorRegion("HZ");
            assertThat(
                            store.customers(
                                            f.tenant.toString(),
                                            f.user,
                                            "OWNED",
                                            null,
                                            null,
                                            null,
                                            null,
                                            1,
                                            20,
                                            "crm:customer:read")
                                    .total())
                    .isZero();
        }
    }

    @Test
    void assignAndReleaseAreExplicitAuditedAndTokenCannotBeReused() {
        try (var f = new Fixture()) {
            long one = f.customer("甲", "HZ", "EMP-OLD"), two = f.customer("乙", "HZ_CHILD", null);
            var p = f.preview("ASSIGN", one, two);
            assertThat(p.items().getFirst().oldEmployeeCode()).isEqualTo("EMP-OLD");
            assertThat(p.items().getFirst().newEmployeeCode()).isEqualTo("EMP-TARGET");
            assertThat(p.items().getFirst().regionName()).isEqualTo("地区HZ");
            assertThat(f.owner(one)).isEqualTo("EMP-OLD");
            assertThat(f.apply(p).affectedCount()).isEqualTo(2);
            assertThat(f.owner(one)).isEqualTo("EMP-TARGET");
            assertThat(f.history()).isEqualTo(2);
            assertThatThrownBy(() -> f.apply(p)).hasMessageContaining("已提交");
            assertThat(f.history()).isEqualTo(2);
            long out = f.customer("越界待清理", "NB", "EMP-TARGET");
            var release = f.preview("RELEASE", out);
            f.apply(release);
            assertThat(f.owner(out)).isNull();
            assertThat(f.history()).isEqualTo(3);
        }
    }

    @Test
    void staleCustomerRevisionRejectsWholeBatchAndKeepsPreviewUnconsumed() {
        try (var f = new Fixture()) {
            long one = f.customer("甲", "HZ", "OLD"), two = f.customer("乙", "HZ", "OLD");
            var p = f.preview("ASSIGN", one, two);
            jdbc.update("UPDATE crm_customer SET revision=revision+1 WHERE id=?", two);
            assertThatThrownBy(() -> f.apply(p)).hasMessageContaining("修改");
            assertThat(f.owner(one)).isEqualTo("OLD");
            assertThat(f.owner(two)).isEqualTo("OLD");
            assertThat(f.history()).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM crm_member_responsibility_preview WHERE"
                                            + " token=? AND consumed_at IS NULL",
                                    Integer.class,
                                    p.previewToken()))
                    .isEqualTo(1);
        }
    }

    @Test
    void targetAuthorizationChangeAndActorScopeRevocationRejectOldPreview() {
        try (var f = new Fixture()) {
            long one = f.customer("甲", "HZ", "OLD");
            var p = f.preview("ASSIGN", one);
            when(targets.byUser(f.tenant.toString(), f.user))
                    .thenReturn(f.target(2, new Limit("SPECIFIED", List.of("NB"))));
            assertThatThrownBy(() -> f.apply(p)).hasMessageContaining("授权已变化");
            assertThat(f.owner(one)).isEqualTo("OLD");
            when(targets.byUser(f.tenant.toString(), f.user))
                    .thenReturn(f.target(1, new Limit("SPECIFIED", List.of("HZ"))));
            f.actorRegion("NB");
            assertThatThrownBy(() -> f.apply(p)).isInstanceOf(AuthorizationDeniedException.class);
            assertThat(f.history()).isZero();
        }
    }

    @Test
    void aLateTargetValidationFailureRollsBackEarlierCustomerAndAuditWrites() {
        try (var f = new Fixture()) {
            long one = f.customer("甲", "HZ", "OLD"), two = f.customer("乙", "HZ", "OLD");
            var p = f.preview("ASSIGN", one, two);
            when(targets.byUser(f.tenant.toString(), f.user))
                    .thenReturn(
                            f.target(1, new Limit("SPECIFIED", List.of("HZ"))),
                            f.target(2, new Limit("NONE", List.of())));
            assertThatThrownBy(() -> f.apply(p)).hasMessageContaining("提交过程中变化");
            assertThat(f.owner(one)).isEqualTo("OLD");
            assertThat(f.owner(two)).isEqualTo("OLD");
            assertThat(f.history()).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM crm_member_responsibility_preview WHERE"
                                            + " token=? AND consumed_at IS NULL",
                                    Integer.class,
                                    p.previewToken()))
                    .isEqualTo(1);
        }
    }

    @Test
    void singleCustomerTransferChecksLinkedUserLimitButAllowsEmployeeWithoutLogin() {
        try (var f = new Fixture()) {
            long one = f.customer("宁波客户", "NB", "OLD");
            assertThatThrownBy(
                            () ->
                                    single.transfer(
                                            f.tenant.toString(),
                                            one,
                                            new Change("EMP-TARGET", "NB", 1, "移交测试"),
                                            f.actor.toString()))
                    .isInstanceOf(AuthorizationDeniedException.class);
            when(employees.owner(f.tenant.toString(), "EMP-NO-LOGIN"))
                    .thenReturn(
                            new CrmEmployeeClient.Owner("EMP-NO-LOGIN", "无账号员工", true, null, 1));
            when(targets.byEmployee(f.tenant.toString(), "EMP-NO-LOGIN"))
                    .thenReturn(
                            new CustomerAssignmentTargetView(
                                    f.tenant,
                                    null,
                                    "EMP-NO-LOGIN",
                                    "无账号员工",
                                    null,
                                    true,
                                    null,
                                    0,
                                    new Limit("ALL", List.of())));
            single.transfer(
                    f.tenant.toString(),
                    one,
                    new Change("EMP-NO-LOGIN", "NB", 1, "无登录员工负责客户"),
                    f.actor.toString());
            assertThat(f.owner(one)).isEqualTo("EMP-NO-LOGIN");
        }
    }

    @Test
    void manuallyCreatingCustomerCannotBypassTheOwnersUserRegionLimit() {
        try (var f = new Fixture()) {
            var forbidden =
                    new com.rigour.merchant.api.v1.model.InternalCustomerCommand(
                            "跨地区新客户",
                            null,
                            null,
                            null,
                            "NB",
                            null,
                            null,
                            "EMP-TARGET",
                            "伪造姓名",
                            null,
                            null,
                            "ACTIVE",
                            null,
                            0);
            assertThatThrownBy(
                            () ->
                                    customers.create(
                                            f.tenant.toString(),
                                            "CREATE-NB",
                                            forbidden,
                                            f.actor.toString()))
                    .isInstanceOf(AuthorizationDeniedException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM crm_customer WHERE tenant_id=?",
                                    Integer.class,
                                    f.tenant.toString()))
                    .isZero();
            assertThat(f.history()).isZero();
            var allowed =
                    new com.rigour.merchant.api.v1.model.InternalCustomerCommand(
                            "杭州新客户",
                            null,
                            null,
                            null,
                            "HZ",
                            null,
                            null,
                            "EMP-TARGET",
                            "伪造姓名",
                            null,
                            null,
                            "ACTIVE",
                            null,
                            0);
            var created =
                    customers.create(f.tenant.toString(), "CREATE-HZ", allowed, f.actor.toString());
            assertThat(created.ownerEmployeeCode()).isEqualTo("EMP-TARGET");
            assertThat(created.ownerEmployeeNameSnapshot()).isEqualTo("新主责");
            assertThat(f.history()).isEqualTo(1);
        }
    }

    @Test
    void customerCreationCannotUseCreateScopeToBroadenAssignmentScope() {
        try (var f = new Fixture()) {
            var all = new Limit("ALL", List.of());
            var none = new Limit("NONE", List.of());
            when(targets.byEmployee(f.tenant.toString(), "EMP-TARGET"))
                    .thenReturn(f.target(1, all));
            // 创建权限仍为全部地区；主责分配使用自己的完整动作范围。
            when(authorizations.authorization(f.caller, "crm:customer:assign-owner"))
                    .thenReturn(
                            new SupplyAuthorizationView(
                                    "ACTIVE",
                                    f.tenant,
                                    f.actor,
                                    "EMP-ACTOR",
                                    1,
                                    1,
                                    1,
                                    1,
                                    f.caller.permissions(),
                                    "crm:customer:assign-owner",
                                    true,
                                    List.of(
                                            new Clause(
                                                    UUID.randomUUID(),
                                                    "CUSTOMER",
                                                    "SELF",
                                                    none,
                                                    all,
                                                    none,
                                                    true)),
                                    all,
                                    none));
            assertThatThrownBy(
                            () ->
                                    customers.create(
                                            f.tenant.toString(),
                                            "CREATE-SELF-BYPASS",
                                            createCommand("HZ", "EMP-TARGET"),
                                            f.actor.toString()))
                    .isInstanceOf(AuthorizationDeniedException.class);

            var hangzhou = new Limit("SPECIFIED", List.of("HZ"));
            when(authorizations.authorization(f.caller, "crm:customer:assign-owner"))
                    .thenReturn(
                            new SupplyAuthorizationView(
                                    "ACTIVE",
                                    f.tenant,
                                    f.actor,
                                    "EMP-ACTOR",
                                    1,
                                    1,
                                    1,
                                    1,
                                    f.caller.permissions(),
                                    "crm:customer:assign-owner",
                                    true,
                                    List.of(
                                            new Clause(
                                                    UUID.randomUUID(),
                                                    "CUSTOMER",
                                                    "REGION",
                                                    none,
                                                    hangzhou,
                                                    none,
                                                    true)),
                                    all,
                                    none));
            assertThatThrownBy(
                            () ->
                                    customers.create(
                                            f.tenant.toString(),
                                            "CREATE-REGION-BYPASS",
                                            createCommand("NB", "EMP-TARGET"),
                                            f.actor.toString()))
                    .isInstanceOf(AuthorizationDeniedException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM crm_customer WHERE tenant_id=?",
                                    Integer.class,
                                    f.tenant.toString()))
                    .isZero();
            assertThat(f.history()).isZero();

            var assigned =
                    customers.create(
                            f.tenant.toString(),
                            "CREATE-ALLOWED-OWNER",
                            createCommand("HZ", "EMP-TARGET"),
                            f.actor.toString());
            assertThat(assigned.ownerEmployeeCode()).isEqualTo("EMP-TARGET");
            var unassigned =
                    customers.create(
                            f.tenant.toString(),
                            "CREATE-WITHOUT-OWNER",
                            createCommand("NB", null),
                            f.actor.toString());
            assertThat(unassigned.ownerEmployeeCode()).isNull();
            assertThat(unassigned.regionCode()).isEqualTo("NB");
            assertThat(f.history()).isEqualTo(2);
        }
    }

    private static com.rigour.merchant.api.v1.model.InternalCustomerCommand createCommand(
            String region, String employee) {
        return new com.rigour.merchant.api.v1.model.InternalCustomerCommand(
                "新客户", null, null, null, region, null, null, employee, null, null, null, "ACTIVE",
                null, 0);
    }

    @Test
    void candidatesRejectInactiveRegionAncestorsEvenWhenUserAllowsAllRegions() {
        try (var f = new Fixture()) {
            f.customer("父地区停用客户", "HZ_CHILD", null);
            f.customer("正常地区客户", "NB", null);
            jdbc.update(
                    "UPDATE crm_customer_area SET status='DISABLED' WHERE tenant_id=? AND"
                            + " area_code='HZ'",
                    CrmUuidCodec.encode(f.tenant));
            when(targets.byUser(f.tenant.toString(), f.user))
                    .thenReturn(f.target(1, new Limit("ALL", List.of())));
            var page =
                    store.customers(
                            f.tenant.toString(),
                            f.user,
                            "CANDIDATES",
                            null,
                            null,
                            null,
                            null,
                            1,
                            20,
                            "crm:customer:assign-owner");
            assertThat(page.items()).extracting(Customer::regionCode).containsExactly("NB");
        }
    }

    @Test
    void forgedExpiredAndForeignActorPreviewCannotApply() {
        try (var f = new Fixture()) {
            long one = f.customer("甲", "HZ", "OLD");
            var p = f.preview("ASSIGN", one);
            assertThatThrownBy(
                            () ->
                                    store.apply(
                                            f.tenant.toString(),
                                            f.user,
                                            new ApplyCommand(p.previewToken()),
                                            UUID.randomUUID().toString()))
                    .hasMessageContaining("预览");
            jdbc.update(
                    "UPDATE crm_member_responsibility_preview SET"
                        + " expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE token=?",
                    p.previewToken());
            assertThatThrownBy(() -> f.apply(p)).hasMessageContaining("预览");
            assertThat(f.history()).isZero();
        }
    }

    private final class Fixture implements AutoCloseable {
        final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), actor = UUID.randomUUID();
        final CallerIdentity caller =
                new CallerIdentity(
                        "TENANT",
                        actor,
                        tenant,
                        actor,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(
                                "crm:customer:read",
                                "crm:customer:assign-owner",
                                "supply:user:read"));

        Fixture() {
            for (String code : List.of("HZ", "HZ_CHILD", "NB"))
                jdbc.update(
                        "INSERT INTO"
                            + " crm_customer_area(id,tenant_id,area_code,area_name,parent_area_code,status,created_time,updated_time)"
                            + " VALUES(?,?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                        CrmUuidCodec.encode(UUID.randomUUID()),
                        CrmUuidCodec.encode(tenant),
                        code,
                        "地区" + code,
                        "HZ_CHILD".equals(code) ? "HZ" : null);
            var target = target(1, new Limit("SPECIFIED", List.of("HZ")));
            when(targets.byUser(tenant.toString(), user)).thenReturn(target);
            when(targets.byEmployee(tenant.toString(), "EMP-TARGET")).thenReturn(target);
            when(employees.owner(tenant.toString(), "EMP-TARGET"))
                    .thenReturn(new CrmEmployeeClient.Owner("EMP-TARGET", "新主责", true, null, 1));
            actorRegion(null);
            TestAuthorizationContext.set(caller);
        }

        CustomerAssignmentTargetView target(long version, Limit region) {
            return new CustomerAssignmentTargetView(
                    tenant, user, "EMP-TARGET", "新主责", "ACTIVE", true, null, version, region);
        }

        void actorRegion(String region) {
            var limit =
                    new Limit(
                            region == null ? "ALL" : "SPECIFIED",
                            region == null ? List.of() : List.of(region));
            var none = new Limit("NONE", List.of());
            when(authorizations.authorization(eq(caller), anyString()))
                    .thenAnswer(
                            invocation ->
                                    new SupplyAuthorizationView(
                                            "ACTIVE",
                                            tenant,
                                            actor,
                                            "EMP-ACTOR",
                                            1,
                                            1,
                                            1,
                                            1,
                                            caller.permissions(),
                                            invocation.getArgument(1),
                                            true,
                                            List.of(
                                                    new Clause(
                                                            UUID.randomUUID(),
                                                            "CUSTOMER",
                                                            "REGION",
                                                            none,
                                                            limit,
                                                            none,
                                                            true)),
                                            limit,
                                            none));
        }

        long customer(String name, String region, String owner) {
            String code = UUID.randomUUID().toString();
            jdbc.update(
                    "INSERT INTO"
                        + " crm_customer(tenant_id,customer_code,customer_name,region_code,owner_employee_code,owner_employee_name_snapshot)"
                        + " VALUES(?,?,?,?,?,?)",
                    tenant.toString(),
                    code,
                    name,
                    region,
                    owner,
                    owner);
            return jdbc.queryForObject(
                    "SELECT id FROM crm_customer WHERE tenant_id=? AND customer_code=?",
                    Long.class,
                    tenant.toString(),
                    code);
        }

        Preview preview(String op, long... ids) {
            return store.preview(
                    tenant.toString(),
                    user,
                    new PreviewCommand(
                            op,
                            Arrays.stream(ids)
                                    .mapToObj(
                                            id ->
                                                    new Selection(
                                                            id,
                                                            jdbc.queryForObject(
                                                                    "SELECT revision FROM"
                                                                            + " crm_customer WHERE"
                                                                            + " id=?",
                                                                    Long.class,
                                                                    id)))
                                    .toList(),
                            "明确客户移交或解除"),
                    actor.toString());
        }

        Applied apply(Preview p) {
            return store.apply(
                    tenant.toString(), user, new ApplyCommand(p.previewToken()), actor.toString());
        }

        String owner(long id) {
            return jdbc.queryForObject(
                    "SELECT owner_employee_code FROM crm_customer WHERE tenant_id=? AND id=?",
                    String.class,
                    tenant.toString(),
                    id);
        }

        int history() {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM crm_customer_responsibility_history WHERE tenant_id=?",
                    Integer.class,
                    tenant.toString());
        }

        public void close() {
            TestAuthorizationContext.clear();
        }
    }
}
