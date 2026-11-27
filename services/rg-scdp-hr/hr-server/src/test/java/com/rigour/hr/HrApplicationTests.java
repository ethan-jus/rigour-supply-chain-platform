package com.rigour.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.hr.application.port.out.HrPayrollStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    static {
        MYSQL.withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");
        MYSQL.withUrlParam("connectionCollation", "utf8mb4_general_ci");
        MYSQL.withUrlParam("connectionTimeZone", "UTC");
        MYSQL.withUrlParam("forceConnectionTimeZoneToSession", "true");
    }

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

    @Autowired private com.rigour.hr.application.port.out.HrEmployeeStore employeeResolution;

    @Autowired private HrPayrollStore payroll;

    @Test
    void looksUpOnlyTenantLockedSalesSettlementEvidenceAcrossMonths() {
        String tenant = UUID.randomUUID().toString();
        insertPayrollEvidence(tenant, "A", "2026-10", "CONFIRMED", "SALES", "FULLY_PAID", "FULL:1");
        insertPayrollEvidence(tenant, "B", "2026-11", "PAID", "SALES", "FULLY_PAID", "FULL:2");
        insertPayrollEvidence(tenant, "C", "2026-12", "READY", "SALES", "FULLY_PAID", "FULL:3");
        insertPayrollEvidence(tenant, "D", "2026-11", "PAID", "CITY_MANAGER", "FULLY_PAID", "FULL:4");
        insertPayrollEvidence(tenant, "A", "2026-08", "PAID", "SALES", "PARTIAL", "FULL:5");
        insertPayrollEvidence(tenant, "A", "2026-09", "PAID", "SALES", "FULLY_PAID", "FULL:1");
        insertPayrollEvidence(UUID.randomUUID().toString(), "A", "2026-11", "PAID", "SALES", "FULLY_PAID", "FULL:6");
        insertPayrollEvidence(tenant, "E", "2026-11", "CONFIRMED", "SALES", "FULLY_PAID", "FULL:7");
        jdbcTemplate.update("""
                UPDATE hr_payroll SET calculation_json = JSON_REMOVE(
                    JSON_SET(calculation_json, '$.performanceDetail',
                        JSON_OBJECT('sources', JSON_EXTRACT(calculation_json, '$.settledOrders'))),
                    '$.settledOrders')
                WHERE tenant_id = ? AND employee_code = 'E'
                """, tenant);

        assertThat(payroll.lockedSalesSettlementIds(tenant, "A", "2026-11",
                List.of("FULL:1", "FULL:2", "FULL:3", "FULL:4", "FULL:5", "FULL:6", "FULL:7")))
                .containsExactlyInAnyOrder("FULL:1", "FULL:2", "FULL:7");
        assertThat(payroll.lockedSalesSettlementIds(tenant, "B", "2026-11", List.of("FULL:2")))
                .isEmpty();
        assertThat(payroll.lockedSalesSettlementIds(tenant, "B", "2026-11", List.of())).isEmpty();
    }

    private void insertPayrollEvidence(String tenant, String employee, String month, String status,
            String role, String kind, String sourceId) {
        jdbcTemplate.update("""
                INSERT INTO hr_payroll (id, tenant_id, employee_code, employee_name, payroll_month,
                    status_code, calculation_json, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, JSON_OBJECT(
                    'scheme', JSON_OBJECT('scheme', JSON_OBJECT('definition', JSON_OBJECT('role', ?))),
                    'settledOrders', JSON_ARRAY(JSON_OBJECT('sourceId', ?, 'kind', ?))
                ), 'HR', 'HR')
                """, UUID.randomUUID().toString(), tenant, employee, employee, month, status, role, sourceId, kind);
    }

    @Test
    void mobileResolutionUsesScopedBindingNotDuplicateNameOrEmploymentStatus() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_employee(tenant_id,employee_code,employee_name,employment_status) VALUES(?,'E1','同名员工','LEFT'),(?,'E2','同名员工','ACTIVE')", tenant, tenant);
        jdbcTemplate.update("INSERT INTO hr_employee_source_binding(tenant_id,employee_code,source_system,source_tenant_key,source_employee_id,source_mobile,source_employee_name) VALUES(?,'E1','DINGHUOBAO','A','s1','13800000001','同名员工'),(?,'E2','DINGHUOBAO','B','s2','13800000001','同名员工')", tenant, tenant);
        assertThat(employeeResolution.resolveExternalEmployees(tenant, "DINGHUOBAO", "A",
                java.util.List.of("13800000001"), java.util.List.of("同名员工")))
                .singleElement().satisfies(e -> assertThat(e.employeeCode()).isEqualTo("E1"));
        assertThat(employeeResolution.resolveExternalEmployees("other", "DINGHUOBAO", "A",
                java.util.List.of("13800000001"), java.util.List.of("同名员工"))).isEmpty();
        jdbcTemplate.update("UPDATE hr_employee_source_binding SET source_tenant_key='A' WHERE tenant_id=? AND source_employee_id='s2'", tenant);
        assertThat(employeeResolution.resolveExternalEmployees(tenant, "DINGHUOBAO", "A",
                java.util.List.of("13800000001"), java.util.List.of("同名员工"))).isEmpty();
    }

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
            var store = new com.rigour.hr.infrastructure.persistence.repository.JdbcHrOrganizationStore(new JdbcTemplate(pool), new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool), scopes, org.mockito.Mockito.mock(com.rigour.hr.application.port.out.HrAuditActorNameResolver.class), org.mockito.Mockito.mock(com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper.class));
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
    void manualCreationCannotDuplicateExistingOrConcurrentEmployees() throws Exception {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')", tenant);
        var sales = organizations.saveDepartment(tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"销售部",0,"ACTIVE",0,null,null,null),"admin");
        var city = organizations.saveDepartment(tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(sales.id(),"测试市",0,"ACTIVE",0,null,null,null),"admin");
        var manual = new com.rigour.hr.api.v1.model.HrEmployeeCommand("防重员工",city.id(),"SALES","ACTIVE","13800000777",null,null,null,null,0,null,null);
        organizations.saveEmployee(tenant,null,manual,"admin");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.saveEmployee(tenant,null,manual,"admin"))
                .hasMessageContaining("不要重复新增");
        var concurrent = new com.rigour.hr.api.v1.model.HrEmployeeCommand("并发员工",city.id(),"SALES","ACTIVE","13800000779",null,null,null,null,0,null,null);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> create = () -> { start.await(); try { organizations.saveEmployee(tenant,null,concurrent,"admin"); return true; } catch (com.rigour.shared.core.exception.BusinessException ex) { assertThat(ex.getMessage()).contains("不要重复新增"); return false; } };
            var a = pool.submit(create); var b = pool.submit(create); start.countDown();
            assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee WHERE tenant_id=? AND mobile='13800000779'",Integer.class,tenant)).isEqualTo(1);
    }

    @Test
    void historicalEmployeeBindingPendingReviewDoesNotResolve() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')",tenant);
        var dept = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"运营部",0,"ACTIVE",0,null,null,null),"admin");
        long id = organizations.saveEmployee(tenant,null,employee("核对员工",dept.id(),"ACTIVE",0),"admin");
        jdbcTemplate.update("UPDATE hr_employee SET mobile='13800000123' WHERE tenant_id=? AND id=?",tenant,id);
        var before = jdbcTemplate.queryForMap("SELECT * FROM hr_employee WHERE tenant_id=? AND id=?",tenant,id);
        var source = salesperson("risk-one","（测试）核对员工","13800000123","运营部");
        String code = employeeStore.employee(tenant, id).orElseThrow().employeeCode();
        jdbcTemplate.update("""
                INSERT INTO hr_employee_source_binding(tenant_id,employee_code,source_system,
                    source_tenant_key,source_employee_id,source_employee_name,review_required,review_reason)
                VALUES(?,?,'DINGHUOBAO','test-source','risk-one','来源姓名',1,'历史上报姓名不一致')
                """, tenant, code);
        assertThat(employeeStore.resolveExternalEmployees(tenant, "DINGHUOBAO", "test-source",
                java.util.List.of("risk-one"), java.util.List.of())).isEmpty();
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
    void employeeDefaultSortGroupsStatusBeforeEntryDateAcrossPages() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')", tenant);
        var dept = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null,"排序测试",0,"ACTIVE",0,null,null,null), "admin");
        var ids = new java.util.ArrayList<Long>();
        // 创建顺序与预期排序相反；离职员工的入职日期更新，仍应排在所有在职员工之后。
        for (String name : java.util.List.of("离职新", "离职旧", "停用", "待确认", "在职无日期", "在职旧", "在职新", "在职同日")) {
            ids.add(organizations.saveEmployee(tenant, null, employee(name, dept.id(), "ACTIVE", 0), "admin"));
        }
        String[] statuses = {"LEFT", "LEFT", "INACTIVE", "PENDING", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE"};
        String[] dates = {"2026-10-01", "2026-09-01", "2026-08-01", "2026-09-01", null, "2026-01-01", "2026-02-01", "2026-02-01"};
        for (int i = 0; i < ids.size(); i++) jdbcTemplate.update(
                "UPDATE hr_employee SET employment_status=?,entry_date=? WHERE tenant_id=? AND id=?", statuses[i], dates[i], tenant, ids.get(i));
        var expected = java.util.List.of(ids.get(7), ids.get(6), ids.get(5), ids.get(4), ids.get(3), ids.get(2), ids.get(0), ids.get(1));
        var actual = new java.util.ArrayList<Long>();
        for (int begin = 0; begin < ids.size(); begin += 3) {
            var page = employeeStore.employees(tenant, begin, 3, null);
            assertThat(page.total()).isEqualTo(8);
            actual.addAll(page.items().stream().map(e -> e.id()).toList());
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        var ascending = new com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria(
                null,null,null,null,null,null,null,null,null,null,dept.id(),null,null,false,"entryDate","asc");
        assertThat(employeeStore.employees(tenant, 0, 20, ascending).items()).extracting(e -> e.id())
                .containsExactly(ids.get(5), ids.get(7), ids.get(6), ids.get(4), ids.get(2), ids.get(3), ids.get(1), ids.get(0));
        assertThat(employeeStore.employees(java.util.UUID.randomUUID().toString(), 0, 20, null).items()).isEmpty();
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
        Instant entryDate = Instant.parse("2025-10-24T05:52:47Z");
        organizations.saveEmployee(
                tenant, imported, new com.rigour.hr.api.v1.model.HrEmployeeCommand(
                        "张三", department.id(), "SALES", "ACTIVE", null, null,
                        entryDate, null, null, 1, null, null), "admin");
        assertThat(organizations.assignments(tenant, imported))
                .singleElement().satisfies(term -> assertThat(term.effectiveFrom())
                        .isEqualTo(Instant.parse("2025-10-23T16:00:00Z")));
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
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.deleteDepartment(tenant, root.id(), root.revision(), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("下级");

        assertThat(organizations.departments("other-tenant")).isEmpty();
        var edited = organizations.saveDepartment(tenant, child.id(),
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "杭州区域", 5, "ACTIVE", child.revision(), null, null, null), "admin");
        assertThat(edited.parentId()).isNull();
        assertThat(edited.departmentName()).isEqualTo("杭州区域");
        assertThat(edited.departmentCode()).isEqualTo(child.departmentCode());
        assertThat(organizations.departments(tenant)).containsExactly(root, edited);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.saveDepartment(tenant, child.id(),
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "旧版本覆盖", 5, "ACTIVE", child.revision(), null, null, null), "admin"))
                .isInstanceOfSatisfying(com.rigour.shared.core.exception.BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(com.rigour.shared.core.api.ErrorCode.CONFLICT));
        jdbcTemplate.update("INSERT INTO hr_business_target(tenant_id,target_month,dimension_type,dimension_code,metric_code,target_value) VALUES(?,'2026-11-01','CITY',?,'SALES_AMOUNT',100)", tenant, edited.departmentCode());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.deleteDepartment(tenant, child.id(), edited.revision(), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("经营目标");
        jdbcTemplate.update("DELETE FROM hr_business_target WHERE tenant_id=? AND dimension_code=?", tenant, edited.departmentCode());
        organizations.deleteDepartment(tenant, child.id(), edited.revision(), "admin");
        assertThat(organizations.departments(tenant)).containsExactly(root);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_department WHERE tenant_id=? AND id=?", Integer.class, tenant, child.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_organization_change WHERE tenant_id=? AND event_type='DEPARTMENT_DELETE' AND object_ref=?", Integer.class, tenant, String.valueOf(child.id()))).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_department_closure WHERE tenant_id=? AND descendant_id=?", Integer.class, tenant, child.id())).isZero();
    }

    @Test
    void departmentDetailsKeepCreationAuditAndReplaceOnlyModificationAudit() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO hr_employee(tenant_id,employee_code,employee_name,employment_status,mobile)
                VALUES(?,'EMPTEST1','张三','ACTIVE','13800000001'),(?,'EMPTEST2','李四','ACTIVE','13800000002')
                """, tenant, tenant);
        var created = organizations.saveDepartment(tenant, null,
            new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "杭州销售部", 3, "ACTIVE", 0,
                "EMPTEST1", "0571-12345678", java.time.LocalDate.of(2020, 5, 6)), "creator-a");
        assertThat(created.leaderName()).isEqualTo("张三");
        assertThat(created.contactPhone()).isEqualTo("13800000001");
        assertThat(created.establishedDate()).isEqualTo(java.time.LocalDate.of(2020, 5, 6));
        assertThat(created.createdBy()).isEqualTo("creator-a");
        assertThat(created.updatedBy()).isEqualTo("creator-a");
        assertThat(created.createdTime()).isNotNull();
        assertThat(created.updatedTime()).isEqualTo(created.createdTime());
        jdbcTemplate.update("UPDATE hr_department SET contact_phone='0571-12345678' WHERE tenant_id=? AND id=?", tenant, created.id());
        jdbcTemplate.update("UPDATE hr_employee SET mobile='13800000003' WHERE tenant_id=? AND employee_code='EMPTEST1'", tenant);
        assertThat(organizations.departments(tenant)).singleElement()
                .extracting(com.rigour.hr.api.v1.model.HrDepartmentView::contactPhone).isEqualTo("13800000003");
        var changed = organizations.saveDepartment(tenant, created.id(),
            new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "杭州销售部", 4, "ACTIVE", created.revision(),
                "EMPTEST2", null, java.time.LocalDate.of(2021, 1, 1)), "editor-b");
        assertThat(changed.createdBy()).isEqualTo(created.createdBy());
        assertThat(changed.createdTime()).isEqualTo(created.createdTime());
        assertThat(changed.updatedBy()).isEqualTo("editor-b");
        assertThat(changed.updatedTime()).isAfterOrEqualTo(created.updatedTime());
        assertThat(changed.contactPhone()).isEqualTo("13800000002");
        assertThat(changed.leaderName()).isEqualTo("李四");
        assertThat(changed.establishedDate()).isEqualTo(java.time.LocalDate.of(2021, 1, 1));
        assertThat(organizations.departments(tenant)).containsExactly(changed);
        var withoutLeader = organizations.saveDepartment(tenant, created.id(),
            new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "杭州销售部", 4, "ACTIVE", changed.revision(),
                null, null, changed.establishedDate()), "editor-b");
        assertThat(withoutLeader.contactPhone()).isNull();
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
        var terms = organizations.assignments(tenant, employee);
        assertThat(terms).hasSize(2);
        assertThat(terms.getLast().effectiveTo()).isEqualTo(terms.getFirst().effectiveFrom());
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
    void departureUsesLastBusinessDayAndKeepsPreviousTermsIntact() {
        String tenant = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO hr_position(tenant_id,position_code,position_name,status_code)"
                    + " VALUES(?, 'SALES', '业务员', 'ACTIVE')",
                tenant);
        var department = organizations.saveDepartment(
                tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                        null, "离职日期测试部", 0, "ACTIVE", 0, null, null, null), "admin");
        Instant entryDate = Instant.parse("2025-10-23T16:00:00Z");
        Instant leaveDate = Instant.parse("2025-10-30T16:00:00Z");
        long employee = organizations.saveEmployee(
                tenant, null, new com.rigour.hr.api.v1.model.HrEmployeeCommand(
                        "月底离职测试员工", department.id(), "SALES", "ACTIVE", null, null,
                        entryDate, null, null, 0, null, null), "admin");
        organizations.saveEmployee(
                tenant, employee, new com.rigour.hr.api.v1.model.HrEmployeeCommand(
                        "月底离职测试员工", department.id(), "SALES", "LEFT", null, null,
                        entryDate, leaveDate, null, 1, null, null), "admin");

        Instant endExclusive = Instant.parse("2025-10-31T16:00:00Z");
        assertThat(organizations.assignments(tenant, employee)).singleElement().satisfies(term -> {
            assertThat(term.effectiveFrom()).isEqualTo(entryDate);
            assertThat(term.effectiveTo()).isEqualTo(endExclusive);
        });
        String employeeCode = jdbcTemplate.queryForObject(
                "SELECT employee_code FROM hr_employee WHERE id=?", String.class, employee);
        var lastDay = java.time.LocalDate.of(2025, 10, 31);
        assertThat(payroll.departmentAt(tenant, employeeCode, lastDay)).isEqualTo(department.id());
        assertThat(payroll.preparationDepartments(tenant, List.of(employeeCode), "2025-10", lastDay))
                .containsEntry(employeeCode, department.id());
        assertThat(payroll.departmentAt(tenant, employeeCode, lastDay.plusDays(1))).isNull();
        assertThat(payroll.preparationDepartments(
                tenant, List.of(employeeCode), "2025-11", lastDay.plusDays(1))).isEmpty();

        organizations.saveEmployee(
                tenant, employee, new com.rigour.hr.api.v1.model.HrEmployeeCommand(
                        "月底离职测试员工", department.id(), "SALES", "ACTIVE", null, null,
                        entryDate, null, null, 2, null, null), "admin");

        var terms = organizations.assignments(tenant, employee);
        assertThat(terms).hasSize(2);
        assertThat(terms.getFirst().effectiveFrom()).isAfter(endExclusive);
        assertThat(terms.getLast().effectiveFrom()).isEqualTo(entryDate);
        assertThat(terms.getLast().effectiveTo()).isEqualTo(endExclusive);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancelFutureDeparturePreservesAssignmentUntilAnyActualTransfer(boolean transfer) {
        String tenant = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO hr_position(tenant_id,position_code,position_name,status_code)"
                    + " VALUES(?, 'SALES', '业务员', 'ACTIVE')", tenant);
        var firstDepartment = organizations.saveDepartment(
                tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                        null, "原部门", 0, "ACTIVE", 0, null, null, null), "admin");
        var nextDepartment = organizations.saveDepartment(
                tenant, null, new com.rigour.hr.api.v1.model.HrDepartmentCommand(
                        null, "调入部门", 0, "ACTIVE", 0, null, null, null), "admin");
        var entryDate = Instant.parse("2025-10-23T16:00:00Z");
        var businessZone = java.time.ZoneId.of("Asia/Shanghai");
        var leaveDate = java.time.LocalDate.now(businessZone).plusDays(5)
                .atStartOfDay(businessZone).toInstant();
        long employee = organizations.saveEmployee(
                tenant, null, new com.rigour.hr.api.v1.model.HrEmployeeCommand(
                        "预登记离职测试员工", firstDepartment.id(), "SALES", "ACTIVE", null, null,
                        entryDate, null, null, 0, null, null), "admin");
        organizations.saveEmployee(
                tenant, employee, new com.rigour.hr.api.v1.model.HrEmployeeCommand(
                        "预登记离职测试员工", firstDepartment.id(), "SALES", "LEFT", null, null,
                        entryDate, leaveDate, null, 1, null, null), "admin");
        var scheduled = organizations.assignments(tenant, employee).getFirst();
        assertThat(scheduled.effectiveTo()).isAfter(Instant.now());

        organizations.saveEmployee(
                tenant, employee, new com.rigour.hr.api.v1.model.HrEmployeeCommand(
                        "预登记离职测试员工", transfer ? nextDepartment.id() : firstDepartment.id(),
                        "SALES", "ACTIVE", null, null, entryDate, null, null, 2, null, null), "admin");

        var assignments = organizations.assignments(tenant, employee);
        assertThat(assignments).hasSize(transfer ? 2 : 1);
        assertThat(assignments.getLast().id()).isEqualTo(scheduled.id());
        assertThat(assignments.getLast().effectiveFrom()).isEqualTo(entryDate);
        assertThat(assignments.getFirst().effectiveTo()).isNull();
        if (transfer) {
            assertThat(assignments.getFirst().departmentId()).isEqualTo(nextDepartment.id());
            assertThat(assignments.getLast().effectiveTo()).isEqualTo(assignments.getFirst().effectiveFrom());
            assertThat(assignments.getLast().effectiveTo()).isBefore(scheduled.effectiveTo());
        }
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
        org.mockito.Mockito.when(authorizations.authorization(caller, "hr:employee:delete"))
                .thenReturn(new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView(
                        "ACTIVE", tenantId, user, "EMP-MANAGER", 1, 1, 1, 1,
                        java.util.Set.of("hr:employee:delete"), "hr:employee:delete", true,
                        java.util.List.of(clause), none, none));
        com.rigour.shared.context.TestAuthorizationContext.set(caller);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.deleteEmployee(tenant, hidden, 1, user.toString()))
                    .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hr_employee WHERE tenant_id=? AND id=?", Integer.class, tenant, hidden)).isZero();
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
    void identityReportsOnlyTheHighestActiveDepartmentsActuallyManagedByTheEmployee() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')", tenant);
        var parent = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "苏州战区", 0, "ACTIVE", 0, null, null, null), "admin");
        var home = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(parent.id(), "苏州市", 0, "ACTIVE", 0, null, null, null), "admin");
        var other = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "独立区域", 0, "ACTIVE", 0, null, null, null), "admin");
        var disabled = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "已停用区域", 0, "INACTIVE", 0, null, null, null), "admin");
        long id = organizations.saveEmployee(tenant, null, employee("战区负责人", home.id(), "ACTIVE", 0), "admin");
        String code = employeeStore.employee(tenant, id).orElseThrow().employeeCode();
        jdbcTemplate.update("UPDATE hr_department SET leader_employee_code=? WHERE tenant_id=? AND id IN (?,?,?,?)",
                code, tenant, parent.id(), home.id(), other.id(), disabled.id());
        var identity = organizations.identity(tenant, code).orElseThrow();
        assertThat(identity.departmentId()).isEqualTo(home.id());
        assertThat(identity.managedDepartmentIds()).containsExactlyInAnyOrder(parent.id(), other.id());
        jdbcTemplate.update("UPDATE hr_department SET leader_employee_code=NULL WHERE tenant_id=? AND id=?", tenant, parent.id());
        assertThat(organizations.identity(tenant, code).orElseThrow().managedDepartmentIds())
                .containsExactlyInAnyOrder(home.id(), other.id());
    }

    @Test
    void employeeDeletePreservesHistoryAndSourceTombstone() {
        String tenant = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO hr_position(tenant_id,position_code,position_name,status_code) VALUES(?,'SALES','业务员','ACTIVE')", tenant);
        var dept = organizations.saveDepartment(tenant, null,
                new com.rigour.hr.api.v1.model.HrDepartmentCommand(null, "删除测试部", 0, "ACTIVE", 0, null, null, null), "admin");
        long id = organizations.saveEmployee(tenant, null, employee("删除测试员工", dept.id(), "ACTIVE", 0), "admin");
        var before = employeeStore.employee(tenant, id).orElseThrow();
        jdbcTemplate.update("""
                INSERT INTO hr_employee_source_binding(tenant_id,employee_code,source_system,
                    source_tenant_key,source_employee_id,source_employee_name)
                VALUES(?,?,'DINGHUOBAO','test-source','delete-source','删除测试员工')
                """, tenant, before.employeeCode());
        long accessVersion = organizations.identity(tenant, before.employeeCode()).orElseThrow().accessVersion();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.deleteEmployee(tenant, id, before.revision() + 1, "admin"))
                .hasMessage("员工已修改，请刷新");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.deleteEmployee(java.util.UUID.randomUUID().toString(), id, before.revision(), "admin"))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        jdbcTemplate.update("UPDATE hr_department SET leader_employee_code=? WHERE tenant_id=? AND id=?", before.employeeCode(), tenant, dept.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.deleteEmployee(tenant, id, before.revision(), "admin"))
                .hasMessage("请先调整部门负责人或直属下级");
        jdbcTemplate.update("UPDATE hr_department SET leader_employee_code=NULL WHERE tenant_id=? AND id=?", tenant, dept.id());
        long subordinate = organizations.saveEmployee(tenant, null, employee("下级员工", dept.id(), "ACTIVE", 0), "admin");
        jdbcTemplate.update("UPDATE hr_employee SET leader_employee_code=? WHERE tenant_id=? AND id=?", before.employeeCode(), tenant, subordinate);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> organizations.deleteEmployee(tenant, id, before.revision(), "admin"))
                .hasMessage("请先调整部门负责人或直属下级");
        jdbcTemplate.update("UPDATE hr_employee SET leader_employee_code=NULL WHERE tenant_id=? AND id=?", tenant, subordinate);
        organizations.deleteEmployee(tenant, id, before.revision(), "admin");
        assertThat(employeeStore.employee(tenant, id)).isEmpty();
        assertThat(organizations.identity(tenant, before.employeeCode())).isEmpty();
        var deleted = jdbcTemplate.queryForMap("SELECT deleted,revision,access_version,updated_by FROM hr_employee WHERE tenant_id=? AND id=?", tenant, id);
        assertThat(((Number) deleted.get("deleted")).intValue()).isEqualTo(1);
        assertThat(((Number) deleted.get("revision")).intValue()).isEqualTo(before.revision() + 1);
        assertThat(((Number) deleted.get("access_version")).longValue()).isEqualTo(accessVersion + 1);
        assertThat(deleted.get("updated_by")).isEqualTo("admin");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee_assignment WHERE tenant_id=? AND employee_code=? AND effective_to IS NOT NULL", Integer.class, tenant, before.employeeCode())).isPositive();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee_assignment WHERE tenant_id=? AND employee_code=? AND effective_to IS NULL", Integer.class, tenant, before.employeeCode())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_organization_change WHERE tenant_id=? AND object_ref=? AND event_type='EMPLOYEE_DELETE'", Integer.class, tenant, before.employeeCode())).isEqualTo(1);
        assertThat(employeeStore.resolveExternalEmployees(tenant, "DINGHUOBAO", "test-source",
                java.util.List.of("delete-source"), java.util.List.of())).isEmpty();
        assertThat(employeeStore.employee(tenant, id)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee_source_binding WHERE tenant_id=? AND employee_code=?", Integer.class, tenant, before.employeeCode())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr_employee WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(2);
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


    @org.springframework.beans.factory.annotation.Autowired
    com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper employeeMapper;

    @Test
    void fieldworkJoinsLegacyEmployeeKeysToCityHistory() throws Exception {
        String tenant = java.util.UUID.randomUUID().toString();
        String otherTenant = java.util.UUID.randomUUID().toString();
        String code = "MIXED-COLLATION";
        var at = java.time.Instant.parse("2026-10-01T01:00:00Z");
        var time = java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC);
        try (var connection = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            // Mirror legacy DEV columns in a separate schema in the disposable test container.
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE fieldwork_legacy_fixture");
            }
            connection.setCatalog("fieldwork_legacy_fixture");
            var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            var legacyJdbc = new JdbcTemplate(dataSource);
            legacyJdbc.execute("""
                    CREATE TABLE hr_employee (
                      tenant_id VARCHAR(64), employee_code VARCHAR(50), employee_name VARCHAR(100),
                      updated_time DATETIME(6), department_id BIGINT, department_name_snapshot VARCHAR(128)
                    ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                    """);
            legacyJdbc.execute("""
                    CREATE TABLE hr_employee_assignment (
                      id BIGINT, tenant_id VARCHAR(64), employee_code VARCHAR(50), department_id BIGINT,
                      department_name_snapshot VARCHAR(128), effective_from DATETIME(6), effective_to DATETIME(6)
                    ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                    """);
            legacyJdbc.execute("""
                    CREATE TABLE hr_employee_city_history (
                      tenant_id VARCHAR(64), employee_code VARCHAR(50), effective_at DATETIME(6),
                      city_name VARCHAR(80), PRIMARY KEY (tenant_id,employee_code,effective_at)
                    ) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci
                    """);
            legacyJdbc.update("INSERT INTO hr_employee (tenant_id,employee_code,employee_name) VALUES (?,?,?)",
                    tenant, code, "测试员工");
            legacyJdbc.update("INSERT INTO hr_employee_city_history VALUES (?,?,?,?),(?,?,?,?)",
                    tenant, code, time, "杭州", otherTenant, code, time.plusSeconds(1), "宁波");
            var query = new com.rigour.hr.infrastructure.persistence.repository.FieldworkOrganizationQuery(
                    new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(legacyJdbc));

            assertThat(query.find(tenant, at.plusSeconds(1), java.util.List.of(code))).singleElement()
                    .satisfies(organization -> assertThat(organization.city()).isEqualTo("杭州"));
            assertThat(query.find(otherTenant, at, java.util.List.of(code))).isEmpty();
        }
    }

    @org.junit.jupiter.api.Test
    void fieldworkKeepsCityAsOfTheWorkDayAndSeparatesTenants() {
        var query=new com.rigour.hr.infrastructure.persistence.repository.FieldworkOrganizationQuery(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate));
        String code="CITY-HISTORY",tenant=java.util.UUID.randomUUID().toString();
        var first=java.time.Instant.parse("2026-10-01T01:00:00Z");
        var second=first.plus(java.time.Duration.ofDays(15));
        jdbcTemplate.update("INSERT INTO hr_employee(tenant_id,employee_code,employee_name,employment_status) VALUES(?,?,'测试员工','ACTIVE')",tenant,code);
        employeeMapper.recordCity(tenant,code,java.time.LocalDateTime.ofInstant(first,java.time.ZoneOffset.UTC),"杭州");
        employeeMapper.recordCity(tenant,code,java.time.LocalDateTime.ofInstant(second,java.time.ZoneOffset.UTC),"宁波");
        org.assertj.core.api.Assertions.assertThat(query.find(tenant,first.minusSeconds(1),java.util.List.of(code)).getFirst().city()).isNull();
        org.assertj.core.api.Assertions.assertThat(query.find(tenant,first,java.util.List.of(code)).getFirst().city()).isEqualTo("杭州");
        org.assertj.core.api.Assertions.assertThat(query.find(tenant,second,java.util.List.of(code)).getFirst().city()).isEqualTo("宁波");
        org.assertj.core.api.Assertions.assertThat(query.find("another-tenant",second,java.util.List.of(code))).isEmpty();
        var firstUtc=java.time.LocalDateTime.ofInstant(first,java.time.ZoneOffset.UTC);
        var secondUtc=java.time.LocalDateTime.ofInstant(second,java.time.ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO hr_department(tenant_id,department_code,department_name) VALUES(?,'X','一组')",tenant);
        long department=jdbcTemplate.queryForObject("SELECT id FROM hr_department WHERE tenant_id=?",Long.class,tenant);
        jdbcTemplate.update("UPDATE hr_employee SET department_id=?, department_name_snapshot='一组',updated_time=? WHERE tenant_id=?",department,firstUtc,tenant);
        jdbcTemplate.update("INSERT INTO hr_department_parent_history(tenant_id,department_id,effective_at,parent_id,department_name) VALUES(?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)",
            tenant,department,firstUtc,9001L,"一组",tenant,9001L,firstUtc,null,"北区",tenant,9002L,firstUtc,null,"南区",tenant,department,secondUtc,9002L,"新一组");
        org.assertj.core.api.Assertions.assertThat(query.find(tenant,first,java.util.List.of(code)).getFirst().departmentAncestors()).containsEntry(9001L,"北区").doesNotContainKey(9002L);
        org.assertj.core.api.Assertions.assertThat(query.find(tenant,second,java.util.List.of(code)).getFirst().departmentAncestors()).containsEntry(9002L,"南区").doesNotContainKey(9001L);

        // Recreate a mixed legacy schema in the isolated MySQL database.
        jdbcTemplate.execute("ALTER TABLE hr_employee_city_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            assertThat(query.find(tenant,first,java.util.List.of(code)).getFirst().city()).isEqualTo("杭州");
            assertThat(query.find(tenant,second,java.util.List.of(code)).getFirst().city()).isEqualTo("宁波");
        } finally {
            jdbcTemplate.execute("ALTER TABLE hr_employee_city_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
    }
}
