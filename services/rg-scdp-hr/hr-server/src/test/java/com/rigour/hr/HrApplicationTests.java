package com.rigour.hr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class HrApplicationTests {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("rigour_hr")
                    .withUsername("rigour_hr_test")
                    .withPassword("rigour_hr_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private com.rigour.hr.application.port.out.HrOrganizationStore organizations;

    @Autowired private com.rigour.hr.application.port.out.HrPositionStore positionStore;

    @Test
    void positionCrudKeepsReferencesAuditsOrderingAndTenantBoundaries() {
        String tenant = java.util.UUID.randomUUID().toString();
        var first = positionStore.create(tenant, "OPS", new com.rigour.hr.api.v1.model.HrPositionCommand("运营", "ACTIVE", "维护运营工作", 0, "OPS", 20), "creator");
        var second = positionStore.create(tenant, "SALES", new com.rigour.hr.api.v1.model.HrPositionCommand("业务员", "ACTIVE", null, 0, "SALES", 1), "creator");
        assertThat(positionStore.positions(tenant,0,20,null).items()).extracting(com.rigour.hr.api.v1.model.HrPositionView::positionCode).containsExactly("SALES","OPS");
        var edited = positionStore.update(tenant, first.id(), new com.rigour.hr.api.v1.model.HrPositionCommand("平台运营", "ACTIVE", "平台职责", 1, "PLATFORM", 0), "editor");
        assertThat(edited.createdBy()).isEqualTo("creator");
        assertThat(edited.updatedBy()).isEqualTo("editor");
        assertThat(edited.sortOrder()).isZero();
        assertThat(edited.positionCode()).isEqualTo("PLATFORM");
        assertThat(positionStore.position(java.util.UUID.randomUUID().toString(), first.id())).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> positionStore.update(tenant,first.id(),new com.rigour.hr.api.v1.model.HrPositionCommand("新名称","ACTIVE",null,1,"PLATFORM",0),"editor")).hasMessageContaining("刷新");
        var department=organizations.saveDepartment(tenant,null,new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"部门",0,"ACTIVE",0,null,null,null),"creator");
        organizations.saveEmployee(tenant,null,employee("测试员工",department.id(),"ACTIVE",0),"creator");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> positionStore.delete(tenant,second.id(),1,"editor")).hasMessageContaining("引用");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> positionStore.update(tenant,second.id(),new com.rigour.hr.api.v1.model.HrPositionCommand("业务员","ACTIVE",null,1,"NEW_CODE",1),"editor")).hasMessageContaining("不能修改编码");
        positionStore.update(tenant,second.id(),new com.rigour.hr.api.v1.model.HrPositionCommand("销售业务员","ACTIVE",null,1,"SALES",1),"editor");
        assertThat(jdbcTemplate.queryForObject("SELECT primary_position_name_snapshot FROM hr_employee WHERE tenant_id=?",String.class,tenant)).isEqualTo("销售业务员");
        positionStore.delete(tenant, first.id(), edited.revision(), "editor");
        assertThat(positionStore.position(tenant,first.id())).isEmpty();
    }

    @Test
    void externalStatusChangesKeepHistoryAndNeverMergeAnUnboundNamesake() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO"
                    + " hr_position(tenant_id,position_code,position_name,status_code)"
                    + " VALUES(?,'SALES','业务员','ACTIVE')",
                tenant);
        var department =
                organizations.saveDepartment(
                        tenant,
                        null,
                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                null, "杭州", 0, "ACTIVE", 0, null, null, null),
                        "admin");
        long local =
                organizations.saveEmployee(
                        tenant, null, employee("张三", department.id(), "ACTIVE", 0), "admin");
        var codes = new com.rigour.shared.core.code.BusinessCodeGenerator();
        var first =
                employeeStore.syncExternalEmployees(
                        tenant,
                        "DINGHUOBAO",
                        java.util.List.of(sourceEmployee("ACTIVE", "first")),
                        "sync",
                        codes);
        assertThat(first.failed()).isZero();
        assertThat(first.created()).isEqualTo(1);
        String code = first.rows().getFirst().employeeCode();
        long imported =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM hr_employee WHERE tenant_id=? AND employee_code=?",
                        Long.class,
                        tenant,
                        code);
        assertThat(imported).isNotEqualTo(local);
        assertThat(organizations.identity(tenant, code).orElseThrow().usable()).isFalse();
        organizations.saveEmployee(
                tenant, imported, employee("张三", department.id(), "ACTIVE", 1), "admin");
        var initial = organizations.identity(tenant, code).orElseThrow();
        assertThat(initial.usable()).isTrue();
        var departed =
                employeeStore.syncExternalEmployees(
                        tenant,
                        "DINGHUOBAO",
                        java.util.List.of(sourceEmployee("INACTIVE", "second")),
                        "sync",
                        codes);
        assertThat(departed.failed()).isZero();
        var inactive = organizations.identity(tenant, code).orElseThrow();
        assertThat(inactive.usable()).isFalse();
        assertThat(inactive.accessVersion()).isGreaterThan(initial.accessVersion());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM hr_employee_assignment WHERE tenant_id=? AND"
                                    + " employee_code=? AND effective_to IS NULL",
                                Integer.class,
                                tenant,
                                code))
                .isZero();
        var restored =
                employeeStore.syncExternalEmployees(
                        tenant,
                        "DINGHUOBAO",
                        java.util.List.of(sourceEmployee("ACTIVE", "third")),
                        "sync",
                        codes);
        assertThat(restored.failed()).isZero();
        var active = organizations.identity(tenant, code).orElseThrow();
        assertThat(active.usable()).isTrue();
        assertThat(active.departmentId()).isEqualTo(department.id());
        assertThat(active.accessVersion()).isEqualTo(inactive.accessVersion());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM hr_employee_assignment WHERE tenant_id=? AND"
                                    + " employee_code=?",
                                Integer.class,
                                tenant,
                                code))
                .isEqualTo(2);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM hr_organization_change WHERE tenant_id=? AND"
                                    + " object_ref=? AND event_type='EMPLOYEE_SOURCE_UPDATE'",
                                Integer.class,
                                tenant,
                                code))
                .isEqualTo(2);
    }

    @Test
    void verifiedRosterIsNotOverwrittenByExternalSync() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')", tenant);
        var codes = new com.rigour.shared.core.code.BusinessCodeGenerator();
        var initial = employeeStore.syncExternalEmployees(tenant, "FEISHU", java.util.List.of(sourceEmployee("ACTIVE", "roster-first")), "sync", codes);
        assertThat(initial.created()).isEqualTo(1);
        String code = initial.rows().getFirst().employeeCode();
        jdbcTemplate.update("UPDATE hr_employee SET local_profile_authoritative=1,employee_name='核定姓名',mobile='00000000000',job_grade='S2' WHERE tenant_id=? AND employee_code=?", tenant, code);
        var result = employeeStore.syncExternalEmployees(tenant, "FEISHU", java.util.List.of(sourceEmployee("INACTIVE", "roster-later")), "sync", codes);
        assertThat(result.unchanged()).isEqualTo(1);
        var saved = jdbcTemplate.queryForMap("SELECT employee_name,mobile,job_grade,employment_status FROM hr_employee WHERE tenant_id=? AND employee_code=?", tenant, code);
        assertThat(saved).containsEntry("employee_name", "核定姓名").containsEntry("mobile", "00000000000").containsEntry("job_grade", "S2").containsEntry("employment_status", "ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT source_payload_hash FROM hr_employee_source_binding WHERE tenant_id=? AND employee_code=?", String.class, tenant, code)).isEqualTo("roster-later");
        jdbcTemplate.update("UPDATE hr_employee SET deleted=1 WHERE tenant_id=? AND employee_code=?", tenant, code);
        var deleted = employeeStore.syncExternalEmployees(tenant, "FEISHU", java.util.List.of(sourceEmployee("ACTIVE", "after-delete")), "sync", codes);
        assertThat(deleted.unchanged()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee WHERE tenant_id=? AND deleted=0", Integer.class, tenant)).isZero();
    }

    private static com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand sourceEmployee(
            String status, String hash) {
        return new com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand(
                null,
                "DEFAULT",
                "staff-1",
                "zhangsan",
                "张三",
                null,
                "来源岗位",
                "来源部门",
                null,
                null,
                null,
                "13800000001",
                null,
                status,
                null,
                null,
                null,
                null,
                hash,
                "{}");
    }

    @Test
    void departmentMaintenancePersistsAndReloadsTreeWithoutCrossTenantLeaks() {
        String tenant = java.util.UUID.randomUUID().toString();
        var root = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "销售部", 0, "ACTIVE", 0, null, null, null), "admin");
        var child = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(root.id(), "杭州", 10, "ACTIVE", 0, null, null, null), "admin");
        assertThat(organizations.departments(tenant)).containsExactly(root, child);
        assertThat(organizations.departments("other-tenant")).isEmpty();
        var edited = organizations.saveDepartment(tenant, child.id(),
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "杭州区域", 5, "ACTIVE", child.revision(), null, null, null), "admin");
        assertThat(edited.parentId()).isNull();
        assertThat(edited.departmentName()).isEqualTo("杭州区域");
        assertThat(edited.departmentCode()).isEqualTo(child.departmentCode());
        assertThat(organizations.departments(tenant)).containsExactly(root, edited);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.saveDepartment(tenant, child.id(),
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "旧版本覆盖", 5, "ACTIVE", child.revision(), null, null, null), "admin"))
                .isInstanceOf(IllegalStateException.class);
        var inactive = organizations.saveDepartment(tenant, child.id(),
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, edited.departmentName(), 5, "INACTIVE", edited.revision(), null, null, null), "admin");
        organizations.deleteDepartment(tenant, child.id(), inactive.revision(), "admin");
        assertThat(organizations.departments(tenant)).containsExactly(root);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hr_department WHERE tenant_id=? AND id=?", Integer.class, tenant, child.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_department_closure WHERE tenant_id=? AND descendant_id=?", Integer.class, tenant, child.id())).isZero();
    }

    @Test
    void departmentDetailsKeepCreationAuditAndReplaceOnlyModificationAudit() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_employee(tenant_id,employee_code,employee_name,employment_status) VALUES(?,'EMPTEST1','张三','ACTIVE'),(?,'EMPTEST2','李四','ACTIVE')",tenant,tenant);
        var created = organizations.saveDepartment(tenant, null,
            new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "杭州销售部", 3, "ACTIVE", 0,
                "EMPTEST1", "0571-12345678", java.time.LocalDate.of(2020, 5, 6)), "creator-a");
        assertThat(created.leaderName()).isEqualTo("张三");
        assertThat(created.contactPhone()).isEqualTo("0571-12345678");
        assertThat(created.establishedDate()).isEqualTo(java.time.LocalDate.of(2020, 5, 6));
        assertThat(created.createdBy()).isEqualTo("creator-a");
        assertThat(created.updatedBy()).isEqualTo("creator-a");
        assertThat(created.createdTime()).isNotNull();
        assertThat(created.updatedTime()).isEqualTo(created.createdTime());
        var changed = organizations.saveDepartment(tenant, created.id(),
            new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "杭州销售部", 4, "ACTIVE", created.revision(),
                "EMPTEST2", null, java.time.LocalDate.of(2021, 1, 1)), "editor-b");
        assertThat(changed.createdBy()).isEqualTo(created.createdBy());
        assertThat(changed.createdTime()).isEqualTo(created.createdTime());
        assertThat(changed.updatedBy()).isEqualTo("editor-b");
        assertThat(changed.updatedTime()).isAfterOrEqualTo(created.updatedTime());
        assertThat(changed.contactPhone()).isNull();
        assertThat(changed.leaderName()).isEqualTo("李四");
        assertThat(changed.establishedDate()).isEqualTo(java.time.LocalDate.of(2021, 1, 1));
        assertThat(organizations.departments(tenant)).containsExactly(changed);
    }

    @Test
    void departmentChangesKeepAssignmentHistoryAndRevocationVersion() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO"
                    + " hr_position(tenant_id,position_code,position_name,status_code)"
                    + " VALUES(?, 'SALES', '业务员', 'ACTIVE')",
                tenant);
        var root =
                organizations.saveDepartment(
                        tenant,
                        null,
                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                null, "华东", 0, "ACTIVE", 0, null, null, null),
                        "admin");
        var hz =
                organizations.saveDepartment(
                        tenant,
                        null,
                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                root.id(), "杭州", 0, "ACTIVE", 0, null, null, null),
                        "admin");
        var jh =
                organizations.saveDepartment(
                        tenant,
                        null,
                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                root.id(), "金华", 1, "ACTIVE", 0, null, null, null),
                        "admin");
        long employee =
                organizations.saveEmployee(
                        tenant, null, employee("张三", hz.id(), "ACTIVE", 0), "admin");
        String code =
                jdbcTemplate.queryForObject(
                        "SELECT employee_code FROM hr_employee WHERE id=?", String.class, employee);
        var first = organizations.identity(tenant, code).orElseThrow();
        assertThat(first.usable()).isTrue();
        assertThat(first.departmentAncestorIds()).containsExactlyInAnyOrder(hz.id(), root.id());
        organizations.saveEmployee(
                tenant,
                employee,
                employee("张三", jh.id(), "ACTIVE", first.employeeRevision()),
                "admin");
        var second = organizations.identity(tenant, code).orElseThrow();
        assertThat(second.departmentId()).isEqualTo(jh.id());
        assertThat(organizations.assignments(tenant, employee)).hasSize(2);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM hr_employee_assignment WHERE tenant_id=? AND"
                                    + " department_id=? AND effective_to IS NOT NULL",
                                Integer.class,
                                tenant,
                                hz.id()))
                .isEqualTo(1);
        organizations.saveEmployee(
                tenant,
                employee,
                employee("张三", jh.id(), "INACTIVE", second.employeeRevision()),
                "admin");
        var departed = organizations.identity(tenant, code).orElseThrow();
        assertThat(departed.usable()).isFalse();
        assertThat(departed.accessVersion()).isEqualTo(first.accessVersion() + 1);
        organizations.saveEmployee(
                tenant,
                employee,
                employee("张三", jh.id(), "ACTIVE", departed.employeeRevision()),
                "admin");
        assertThat(organizations.identity(tenant, code).orElseThrow().accessVersion())
                .isEqualTo(departed.accessVersion());
        assertThat(organizations.identity("other-tenant", code)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                organizations.saveDepartment(
                                        tenant,
                                        root.id(),
                                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                                hz.id(), "循环", 0, "ACTIVE", root.revision(), null, null, null),
                                        "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        organizations.departments(tenant).stream()
                                .filter(d -> d.id().equals(root.id()))
                                .findFirst()
                                .orElseThrow()
                                .parentId())
                .isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                organizations.saveDepartment(
                                        "other-tenant",
                                        null,
                                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                                hz.id(), "跨租户", 0, "ACTIVE", 0, null, null, null),
                                        "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void employeeProfileIsDetailOnlyAndDepartmentFilterIncludesDescendantsBeforePaging() {
        String tenant=java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','销售','ACTIVE')",tenant);
        var root=organizations.saveDepartment(tenant,null,new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"总部",0,"ACTIVE",0,null,null,null),"creator");
        var child=organizations.saveDepartment(tenant,null,new com.rigour.hr.api.v1.model.HrDepartmentCommand(root.id(),"杭州",0,"ACTIVE",0,null,null,null),"creator");
        var outside=organizations.saveDepartment(tenant,null,new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"其他",0,"ACTIVE",0,null,null,null),"creator");
        var profile=new com.rigour.hr.api.v1.model.HrEmployeeProfile("11010519491231002X",java.time.LocalDate.of(2027,1,1),"本科","测试户籍","居民户口","测试住址","0000000000000000","测试银行","公积金","测试联系人","00000000000","测试大学","测试专业","8000+8000","8000","3个月");
        var entry=java.time.Instant.parse("2020-01-01T00:00:00Z");
        long first=organizations.saveEmployee(tenant,null,new com.rigour.hr.api.v1.model.HrEmployeeCommand("测试甲",child.id(),"SALES","ACTIVE","00000000000",null,entry,null,"测试备注",0,profile,"S1"),"creator");
        long second=organizations.saveEmployee(tenant,null,employee("测试乙",root.id(),"ACTIVE",0),"creator");
        organizations.saveEmployee(tenant,null,employee("测试丙",outside.id(),"ACTIVE",0),"creator");
        var detail=employeeStore.employee(tenant,first).orElseThrow();
        assertThat(detail.profile()).isEqualTo(profile);
        assertThat(detail.jobGrade()).isEqualTo("S1");
        var gradeFilter = new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(
                null,null,null,null,null,null,null,null,null,null,root.id(),"SALES","S1");
        assertThat(employeeStore.employees(tenant,0,1,gradeFilter).total()).isEqualTo(1);
        assertThat(employeeStore.employees(tenant,0,1,gradeFilter).items()).extracting(e -> e.employeeName()).containsExactly("测试甲");
        var otherPosition = new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(
                null,null,null,null,null,null,null,null,null,null,root.id(),"OTHER","S1");
        assertThat(employeeStore.employees(tenant,0,20,otherPosition).total()).isZero();
        assertThat(employeeStore.employees("other-tenant",0,20,gradeFilter).total()).isZero();
        assertThat(detail.departmentId()).isEqualTo(child.id());
        var filter=new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(null,null,null,null,null,null,null,null,null,null,root.id(),null,null);
        var page=employeeStore.employees(tenant,0,1,filter);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1).allMatch(e->e.profile()==null);
        assertThat(employeeStore.employees(tenant,1,1,filter).items()).hasSize(1);
        assertThat(employeeStore.employees("another-tenant",0,20,filter).total()).isZero();
        var leader=employeeStore.employee(tenant,second).orElseThrow();
        organizations.saveDepartment(tenant,child.id(),new com.rigour.hr.api.v1.model.HrDepartmentCommand(root.id(),"杭州",0,"ACTIVE",child.revision(),leader.employeeCode(),null,null),"editor");
        assertThat(employeeStore.employee(tenant,first).orElseThrow().departmentLeaderName()).isEqualTo("测试乙");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->organizations.saveDepartment("another-tenant",null,new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"越租户负责人",0,"ACTIVE",0,leader.employeeCode(),null,null),"editor"))
                .hasMessageContaining("当前企业");
        organizations.saveEmployee(tenant,first,new com.rigour.hr.api.v1.model.HrEmployeeCommand("测试甲",child.id(),"SALES","LEFT",null,null,entry,java.time.Instant.parse("2026-01-01T00:00:00Z"),null,detail.revision(),profile,"S1"),"editor");
        var updated=employeeStore.employee(tenant,first).orElseThrow();
        assertThat(updated.createdBy()).isEqualTo("creator");
        assertThat(updated.createdTime()).isEqualTo(detail.createdTime());
        assertThat(updated.updatedBy()).isEqualTo("editor");
        assertThat(updated.profile()).isEqualTo(profile);
    }

    private static com.rigour.hr.api.v1.model.HrEmployeeCommand employee(
            String name, Long department, String status, int revision) {
        return new com.rigour.hr.api.v1.model.HrEmployeeCommand(
                name, department, "SALES", status, null, null, null, null, null, revision, null, null);
    }

    @Autowired private com.rigour.hr.application.port.out.HrEmployeeStore employeeStore;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rigour.tenant.iam.client.SupplyAuthorizationClient authorizations;

    @Test
    void employeeDepartmentScopeFiltersBeforePaginationAndProtectsDetails() {
        var tenantId = java.util.UUID.randomUUID();
        String tenant = tenantId.toString();
        jdbcTemplate.update(
                "INSERT INTO"
                    + " hr_position(tenant_id,position_code,position_name,status_code)"
                    + " VALUES(?, 'SALES', '业务员', 'ACTIVE')",
                tenant);
        var parent =
                organizations.saveDepartment(
                        tenant,
                        null,
                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                null, "管理范围", 0, "ACTIVE", 0, null, null, null),
                        "admin");
        var child =
                organizations.saveDepartment(
                        tenant,
                        null,
                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                parent.id(), "下级部门", 0, "ACTIVE", 0, null, null, null),
                        "admin");
        var other =
                organizations.saveDepartment(
                        tenant,
                        null,
                        new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                                null, "范围之外", 1, "ACTIVE", 0, null, null, null),
                        "admin");
        organizations.saveEmployee(
                tenant, null, employee("可见一", parent.id(), "ACTIVE", 0), "admin");
        organizations.saveEmployee(tenant, null, employee("可见二", child.id(), "ACTIVE", 0), "admin");
        long hidden =
                organizations.saveEmployee(
                        tenant, null, employee("不可见", other.id(), "ACTIVE", 0), "admin");
        var user = java.util.UUID.randomUUID();
        var caller =
                new com.rigour.shared.context.CallerIdentity(
                        "TENANT",
                        user,
                        tenantId,
                        user,
                        null,
                        java.util.UUID.randomUUID(),
                        0,
                        0,
                        0,
                        java.util.Set.of(),
                        java.util.Set.of("hr:employee:read"));
        var none =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "NONE", java.util.List.of());
        var departments =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "SPECIFIED", java.util.List.of(parent.id().toString()));
        var clause =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Clause(
                        java.util.UUID.randomUUID(),
                        "EMPLOYEE",
                        "DEPARTMENT",
                        departments,
                        none,
                        none,
                        true);
        org.mockito.Mockito.when(authorizations.authorization(caller, "hr:employee:read"))
                .thenReturn(
                        new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView(
                                "ACTIVE",
                                tenantId,
                                user,
                                "EMP-MANAGER",
                                1,
                                1,
                                1,
                                1,
                                java.util.Set.of("hr:employee:read"),
                                "hr:employee:read",
                                true,
                                java.util.List.of(clause),
                                none,
                                none));
        com.rigour.shared.context.TestAuthorizationContext.set(caller);
        try {
            var criteria =
                    new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(
                            null, null, null, null, null, null, null, null, null, null, null, null, null);
            var page = employeeStore.employees(tenant, 0, 1, criteria);
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.items()).hasSize(1);
            assertThat(employeeStore.employee(tenant, hidden)).isEmpty();
            assertThat(organizations.identities(tenant, null, 0, 1).total()).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> organizations.assignments(tenant, hidden))
                    .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        } finally {
            com.rigour.shared.context.TestAuthorizationContext.clear();
        }
    }

    @Test
    void contextLoadsAndMigratesHrTables() {
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM information_schema.tables
                                 WHERE table_schema = DATABASE() AND table_name IN (
                                   'hr_employee', 'hr_position', 'hr_employee_source_binding'
                                 )
                                """,
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbcTemplate.queryForList(
                                """
                                SELECT column_name FROM information_schema.columns
                                 WHERE table_schema = DATABASE() AND table_name = 'hr_employee'
                                """,
                                String.class))
                .contains(
                        "employee_code",
                        "employment_status",
                        "job_category",
                        "primary_position_code",
                        "region_name",
                        "city_name",
                        "source_system",
                        "source_document_no",
                        "source_payload_json");
    }
    @org.springframework.beans.factory.annotation.Autowired private com.rigour.hr.application.port.out.SupplyReadinessStore supplyReadiness;
    @org.junit.jupiter.api.Test
    void readinessChecksRunAgainstTheMigratedTenantSchema() {
      var report=supplyReadiness.inspect(java.util.UUID.randomUUID().toString());
      org.assertj.core.api.Assertions.assertThat(report.contractVersion()).isEqualTo(1);
      org.assertj.core.api.Assertions.assertThat(report.version()).isNotBlank();
      org.assertj.core.api.Assertions.assertThat(report.checks()).allMatch(c->c.count()==0);
    }
}
