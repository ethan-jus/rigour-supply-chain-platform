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
    void employeeIdentityDoesNotBorrowNestedConnectionsWithOneConnectionPool() throws Exception {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','销售','ACTIVE')", tenant);
        var root = organizations.saveDepartment(tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "总部", 0, "ACTIVE", 0, null, null, null), "admin");
        var child = organizations.saveDepartment(tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(root.id(), "业务部", 0, "ACTIVE", 0, null, null, null), "admin");
        long id = organizations.saveEmployee(tenant, null, employee("连接池回归员工", child.id(), "ACTIVE", 0), "admin");
        String code = jdbcTemplate.queryForObject("SELECT employee_code FROM hr_employee WHERE tenant_id=? AND id=?", String.class, tenant, id);
        var expected = organizations.identity(tenant, code).orElseThrow();
        try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl(MYSQL.getJdbcUrl());
            pool.setUsername(MYSQL.getUsername());
            pool.setPassword(MYSQL.getPassword());
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(1);
            pool.setConnectionTimeout(500);
            var scopes = org.mockito.Mockito.mock(com.rigour.hr.infrastructure.persistence.repository.HrDataScope.class);
            org.mockito.Mockito.when(scopes.canReadCode(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
            var store = new com.rigour.hr.infrastructure.persistence.repository.JdbcHrOrganizationStore(new JdbcTemplate(pool), new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool), scopes, org.mockito.Mockito.mock(com.rigour.hr.application.port.out.HrAuditActorNameResolver.class));
            var workers = java.util.concurrent.Executors.newFixedThreadPool(5);
            try {
                var results = new java.util.ArrayList<java.util.concurrent.Future<com.rigour.hr.api.v1.model.HrEmployeeIdentityView>>();
                for (int i = 0; i < 20; i++) results.add(workers.submit(() -> store.identity(tenant, code).orElseThrow()));
                for (var result : results) assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(expected);
                assertThat(expected.departmentAncestorIds()).containsExactly(root.id(), child.id());
                assertThat(store.identity("other-tenant", code)).isEmpty();
                assertThat(store.identity(tenant, "MISSING")).isEmpty();
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void departmentMembersExcludeOtherDepartmentsAndTenantsWithoutImplicitDescendants() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','销售','ACTIVE')", tenant);
        var root = organizations.saveDepartment(tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "总部", 0, "ACTIVE", 0, null, null, null), "admin");
        var child = organizations.saveDepartment(tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(root.id(), "下级", 0, "ACTIVE", 0, null, null, null), "admin");
        long first = organizations.saveEmployee(tenant, null, employee("总部员工", root.id(), "ACTIVE", 0), "admin");
        long second = organizations.saveEmployee(tenant, null, employee("下级员工", child.id(), "ACTIVE", 0), "admin");
        String firstCode = jdbcTemplate.queryForObject("SELECT employee_code FROM hr_employee WHERE tenant_id=? AND id=?", String.class, tenant, first);
        String secondCode = jdbcTemplate.queryForObject("SELECT employee_code FROM hr_employee WHERE tenant_id=? AND id=?", String.class, tenant, second);
        assertThat(organizations.departmentMembers(tenant, java.util.List.of(root.id()), false)).containsExactly(firstCode);
        assertThat(organizations.departmentMembers(tenant, java.util.List.of(root.id()), true)).containsExactlyInAnyOrder(firstCode, secondCode);
        assertThat(organizations.departmentMembers(java.util.UUID.randomUUID().toString(), java.util.List.of(root.id()), true)).isEmpty();
    }

    @Test
    void dhbSalespeopleOnlyBindExistingAndCreateBasicAssignedEmployeesIdempotently() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')", tenant);
        var sales = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "销售部", 0, "ACTIVE", 0, null, null, null), "admin");
        var city = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(sales.id(), "石家庄", 0, "ACTIVE", 0, null, null, null), "admin");
        long existing = organizations.saveEmployee(tenant, null, employee("张三", city.id(), "LEFT", 0), "admin");
        var before = jdbcTemplate.queryForMap("SELECT * FROM hr_employee WHERE tenant_id=? AND id=?", tenant, existing);
        var codes = new com.rigour.shared.core.code.BusinessCodeGenerator();
        var result = employeeStore.syncExternalEmployees(tenant, "DINGHUOBAO", java.util.List.of(
                salesperson("s1", "张三", null, "石家庄市"),
                salesperson("s2", "新业务员", "13800000002", "石家庄市")), "sync", codes);
        assertThat(result.failed()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM hr_employee WHERE tenant_id=? AND id=?", tenant, existing)).isEqualTo(before);
        var newEmployee = employeeStore.resolveExternalEmployees(tenant, "DINGHUOBAO", "test-source", java.util.List.of("s2"), java.util.List.of()).getFirst();
        var detail = employeeStore.employee(tenant, newEmployee.employeeId()).orElseThrow();
        assertThat(detail.departmentId()).isEqualTo(city.id());
        assertThat(detail.positionName()).isEqualTo("业务员");
        assertThat(detail.mobile()).isEqualTo("13800000002");
        assertThat(detail.entryDate()).isNull();
        assertThat(detail.email()).isNull();
        assertThat(detail.employmentStatus()).isEqualTo("PENDING");
        assertThat(detail.dhbStaffIds()).containsExactly("s2");
        assertThat(detail.dhbAccountNames()).containsExactly("login-s2");
        var accountFilter = new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(
                "login-s2", null, null, null, null, null, null, null, null, null, null, null, null, null);
        var found = employeeStore.employees(tenant, 0, 20, accountFilter);
        assertThat(found.total()).isEqualTo(1);
        assertThat(found.items()).extracting(com.rigour.hr.api.v1.model.HrEmployeeView::id)
                .containsExactly(newEmployee.employeeId());
        assertThat(found.items().getFirst().dhbAccountNames()).containsExactly("login-s2");
        // A binding in another tenant, or a deleted binding, must not satisfy account search.
        String otherTenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_employee_source_binding(tenant_id,employee_code,source_system,source_tenant_key,source_employee_id,source_account_name,deleted) VALUES(?,?,'DINGHUOBAO','test','foreign','foreign-only',0)",
                otherTenant, detail.employeeCode());
        jdbcTemplate.update("INSERT INTO hr_employee_source_binding(tenant_id,employee_code,source_system,source_tenant_key,source_employee_id,source_account_name,deleted) VALUES(?,?,'DINGHUOBAO','test','removed','removed-only',1)",
                tenant, detail.employeeCode());
        for (String keyword : java.util.List.of("foreign-only", "removed-only", "' OR 1=1 --")) {
            var filter = new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(
                    keyword, null, null, null, null, null, null, null, null, null, null, null, null, null);
            assertThat(employeeStore.employees(tenant, 0, 20, filter).total()).isZero();
        }
        assertThat(employeeStore.employee(tenant, newEmployee.employeeId()).orElseThrow().dhbAccountNames())
                .containsExactly("login-s2");
        var repeated = employeeStore.syncExternalEmployees(tenant, "DINGHUOBAO", java.util.List.of(
                salesperson("s1", "来源改名", "13900000000", "未知部门"),
                salesperson("s2", "来源改名", "13900000000", "未知部门")), "sync", codes);
        assertThat(repeated.unchanged()).isEqualTo(2);
        assertThat(repeated.created()).isZero();
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM hr_employee WHERE tenant_id=? AND id=?", tenant, existing)).isEqualTo(before);
        assertThat(employeeStore.employee(tenant, newEmployee.employeeId()).orElseThrow().mobile()).isEqualTo("13800000002");
        var secondId = employeeStore.syncExternalEmployees(tenant, "DINGHUOBAO", java.util.List.of(
                salesperson("s3", "张三", null, "石家庄市")), "sync", codes);
        assertThat(secondId.updated()).isEqualTo(1);
        assertThat(employeeStore.employee(tenant, existing).orElseThrow().dhbStaffIds()).containsExactly("s1", "s3");
        assertThat(employeeStore.employee(java.util.UUID.randomUUID().toString(), existing)).isEmpty();
    }

    @Test
    void dhbConflictsUnmappedDepartmentsAndDeletedEmployeesAreNeverDuplicated() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')", tenant);
        var sales = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "销售部", 0, "ACTIVE", 0, null, null, null), "admin");
        var city = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(sales.id(), "北京市", 0, "ACTIVE", 0, null, null, null), "admin");
        organizations.saveEmployee(tenant, null, employee("同名员工", city.id(), "ACTIVE", 0), "admin");
        organizations.saveEmployee(tenant, null, employee("同名员工", city.id(), "ACTIVE", 0), "admin");
        long deleted = organizations.saveEmployee(tenant, null, employee("已删员工", city.id(), "ACTIVE", 0), "admin");
        jdbcTemplate.update("UPDATE hr_employee SET deleted=1 WHERE tenant_id=? AND id=?", tenant, deleted);
        var result = employeeStore.syncExternalEmployees(tenant, "DINGHUOBAO", java.util.List.of(
                salesperson("ambiguous", "同名员工", null, "北京市"),
                salesperson("deleted", "已删员工", null, "北京市"),
                salesperson("unmapped", "未建员工", null, "运营部")), "sync", new com.rigour.shared.core.code.BusinessCodeGenerator());
        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.failureMessages()).anyMatch(m -> m.contains("订货宝登录账号 login-ambiguous")
                && m.contains("同名员工") && m.contains("来源ID ambiguous"));
        assertThat(result.created()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee_source_binding WHERE tenant_id=?", Integer.class, tenant)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(3);
        assertThat(result.failureMessages()).anyMatch(m -> m.contains("unmapped") && m.contains("部门"));
        jdbcTemplate.update("UPDATE hr_employee SET mobile='13800000999' WHERE tenant_id=? AND deleted=0", tenant);
        var ambiguousPhone = employeeStore.syncExternalEmployees(tenant,"DINGHUOBAO",java.util.List.of(
                salesperson("phone-conflict","来源测试名","13800000999","北京市")),"sync",new com.rigour.shared.core.code.BusinessCodeGenerator());
        assertThat(ambiguousPhone.failed()).isEqualTo(1);
        assertThat(reviews.pending(tenant)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee_source_binding WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }

    @Autowired private com.rigour.hr.application.port.out.HrDhbBindingReviewStore reviews;

    @Test
    void uniquePhoneMismatchLinksWithReviewAndConfirmationSurvivesRepeatedSync() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')",tenant);
        var dept = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"运营部",0,"ACTIVE",0,null,null,null),"admin");
        long id = organizations.saveEmployee(tenant,null,employee("核对员工",dept.id(),"ACTIVE",0),"admin");
        jdbcTemplate.update("UPDATE hr_employee SET mobile='13800000123' WHERE tenant_id=? AND id=?",tenant,id);
        var before = jdbcTemplate.queryForMap("SELECT * FROM hr_employee WHERE tenant_id=? AND id=?",tenant,id);
        var source = salesperson("risk-one","（测试）核对员工","13800000123","运营部");
        var codes = new com.rigour.shared.core.code.BusinessCodeGenerator();
        var result = employeeStore.syncExternalEmployees(tenant,"DINGHUOBAO",java.util.List.of(source),"sync",codes);
        assertThat(result.failed()).isZero();
        assertThat(result.created()).isZero();
        assertThat(employeeStore.employee(tenant,id).orElseThrow().dhbReviewCount()).isEqualTo(1);
        var risk = reviews.pending(tenant).getFirst();
        assertThat(risk.accountName()).isEqualTo("login-risk-one");
        assertThat(reviews.pending(java.util.UUID.randomUUID().toString())).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reviews.confirm(tenant,risk.bindingId(),
                new com.rigour.hr.api.v1.model.DhbBindingReviewCommand(risk.version()+1,id),"reviewer"))
                .hasMessageContaining("刷新");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reviews.confirm(java.util.UUID.randomUUID().toString(),risk.bindingId(),
                new com.rigour.hr.api.v1.model.DhbBindingReviewCommand(risk.version(),id),"reviewer")).hasMessageContaining("不存在");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reviews.confirm(tenant,risk.bindingId(),
                new com.rigour.hr.api.v1.model.DhbBindingReviewCommand(risk.version(),Long.MAX_VALUE),"reviewer"))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        assertThat(reviews.pending(tenant)).hasSize(1);
        reviews.confirm(tenant,risk.bindingId(),new com.rigour.hr.api.v1.model.DhbBindingReviewCommand(risk.version(),id),"reviewer");
        assertThat(reviews.pending(tenant)).isEmpty();
        employeeStore.syncExternalEmployees(tenant,"DINGHUOBAO",java.util.List.of(source),"sync",codes);
        assertThat(reviews.pending(tenant)).isEmpty();
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM hr_employee WHERE tenant_id=? AND id=?",tenant,id)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_dhb_binding_review_audit WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
        employeeStore.syncExternalEmployees(tenant,"DINGHUOBAO",java.util.List.of(salesperson("risk-one","再次改名","13800000123","运营部")),"sync",codes);
        assertThat(reviews.pending(tenant)).hasSize(1);
        long other = organizations.saveEmployee(tenant,null,employee("另一个员工",dept.id(),"ACTIVE",0),"admin");
        var changed = reviews.pending(tenant).getFirst();
        reviews.confirm(tenant,changed.bindingId(),new com.rigour.hr.api.v1.model.DhbBindingReviewCommand(changed.version(),other),"reviewer");
        assertThat(employeeStore.employee(tenant,id).orElseThrow().dhbStaffIds()).isEmpty();
        assertThat(employeeStore.employee(tenant,other).orElseThrow().dhbStaffIds()).containsExactly("risk-one");
        employeeStore.syncExternalEmployees(tenant,"DINGHUOBAO",java.util.List.of(salesperson("risk-one","再次改名","13800000123","运营部")),"sync",codes);
        assertThat(reviews.pending(tenant)).isEmpty();
    }

    @Test
    void employeeSortUsesAllRecordsStablePaginationAndWhitelistedColumns() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')",tenant);
        var dept = organizations.saveDepartment(tenant,null,new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"部门",0,"ACTIVE",0,null,null,null),"admin");
        long a=organizations.saveEmployee(tenant,null,employee("A",dept.id(),"ACTIVE",0),"admin");
        long b=organizations.saveEmployee(tenant,null,employee("B",dept.id(),"ACTIVE",0),"admin");
        jdbcTemplate.update("UPDATE hr_employee SET entry_date='2026-01-01',created_time='2026-01-01' WHERE tenant_id=? AND id=?",tenant,b);
        jdbcTemplate.update("UPDATE hr_employee SET entry_date=NULL,created_time='2026-02-01' WHERE tenant_id=? AND id=?",tenant,a);
        for(String key:java.util.List.of("employeeCode","employeeName","createdTime","entryDate")) {
            var criteria = new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(null,null,null,null,null,null,null,null,null,null,null,null,null,null,key,"asc");
            var first=employeeStore.employees(tenant,0,1,criteria);
            var second=employeeStore.employees(tenant,1,1,criteria);
            assertThat(first.total()).isEqualTo(2);
            assertThat(first.items().getFirst().id()).isNotEqualTo(second.items().getFirst().id());
            if(key.equals("entryDate") || key.equals("createdTime")) assertThat(first.items().getFirst().id()).isEqualTo(b);
            if(key.equals("employeeName")) assertThat(first.items().getFirst().id()).isEqualTo(a);
        }
        var invalid = new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(null,null,null,null,null,null,null,null,null,null,null,null,null,null,"id;drop table hr_employee","asc");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->employeeStore.employees(tenant,0,20,invalid)).hasMessageContaining("排序字段");
    }

    @Test
    void accountMigrationRecoversOnlyMatchingSourceEvidence() throws Exception {
        String tenant=java.util.UUID.randomUUID().toString();
        for(String source:java.util.List.of("verified","different")) jdbcTemplate.update(
                "INSERT INTO hr_employee_source_binding(tenant_id,employee_code,source_system,source_tenant_key,source_employee_id,source_account_name,source_payload_json) VALUES(?,'EMP1','DINGHUOBAO','legacy',?,'旧姓名',?)",
                tenant,source,"{\"employee\":{\"staffId\":\"verified\",\"accountName\":\"lh18049975818\"}}");
        String sql=new String(new org.springframework.core.io.ClassPathResource("db/migration/V8__dhb_binding_review_and_accounts.sql").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        jdbcTemplate.update(sql.substring(sql.indexOf("UPDATE hr_employee_source_binding"),sql.indexOf(";",sql.indexOf("UPDATE hr_employee_source_binding"))));
        assertThat(jdbcTemplate.queryForObject("SELECT source_account_name FROM hr_employee_source_binding WHERE tenant_id=? AND source_employee_id='verified'",String.class,tenant)).isEqualTo("lh18049975818");
        assertThat(jdbcTemplate.queryForObject("SELECT source_account_name FROM hr_employee_source_binding WHERE tenant_id=? AND source_employee_id='different'",String.class,tenant)).isEqualTo("旧姓名");
    }

    private static com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand salesperson(
            String sourceId, String name, String mobile, String department) {
        return new com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand(null, "test-source", sourceId,
                "login-" + sourceId, name, "salesman", "来源岗位", department, null, null, null, mobile, "ignored@example.test",
                null, null, null, null, null, "source-hash", "{}");
    }

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
                        "FEISHU",
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
                        "FEISHU",
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
                        "FEISHU",
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
                null,null,null,null,null,null,null,null,null,null,root.id(),"SALES","S1",null);
        assertThat(employeeStore.employees(tenant,0,1,gradeFilter).total()).isEqualTo(1);
        assertThat(employeeStore.employees(tenant,0,1,gradeFilter).items()).extracting(e -> e.employeeName()).containsExactly("测试甲");
        var otherPosition = new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(
                null,null,null,null,null,null,null,null,null,null,root.id(),"OTHER","S1",null);
        assertThat(employeeStore.employees(tenant,0,20,otherPosition).total()).isZero();
        assertThat(employeeStore.employees("other-tenant",0,20,gradeFilter).total()).isZero();
        assertThat(detail.departmentId()).isEqualTo(child.id());
        var filter=new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(null,null,null,null,null,null,null,null,null,null,root.id(),null,null,null);
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
                            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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

}
