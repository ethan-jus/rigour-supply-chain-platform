package com.rigour.analytics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.*;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import java.util.*;

/** 真实 MySQL 验证 CTE 过滤在总计、嵌套查询和分页之前执行，缓存键包含授权绑定值。 */
@SpringBootTest(
        properties = {
            "rigour.analytics.supply-dashboard.refresh.enabled=false",
            "spring.cloud.nacos.config.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("test")
@Testcontainers
class BiSupplyScopeIntegrationTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4").withDatabaseName("rigour_bi");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.flyway.enabled", () -> true);
        r.add("spring.flyway.url", MYSQL::getJdbcUrl);
        r.add("spring.flyway.user", MYSQL::getUsername);
        r.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired com.rigour.analytics.infrastructure.persistence.scope.BiAuthorityProjector projector;

    @Autowired
    com.rigour.analytics.infrastructure.persistence.scope.BiSourceSnapshotProjector sourceProjector;

    @Autowired
    com.rigour.analytics.infrastructure.persistence.scope.BiPeopleProjector peopleProjector;


    @MockitoBean com.rigour.analytics.application.port.out.BiSourceSnapshotClient sourceSnapshots;

    @Autowired
    com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper
            productionMapper;

    @MockitoBean com.rigour.analytics.application.port.out.BiAuthoritySource authority;
    @Autowired com.rigour.analytics.infrastructure.persistence.scope.BiDashboardProductProjector dashboardProducts;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionFactory sessions;
    @MockitoBean SupplyAuthorizationClient iam;
    String tenant;
    CallerIdentity actor;

    @BeforeEach
    void setup() {
        tenant = UUID.randomUUID().toString();
        UUID u = UUID.randomUUID();
        actor =
                new CallerIdentity(
                        "TENANT",
                        u,
                        UUID.fromString(tenant),
                        u,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("TENANT_SUPER_ADMIN"),
                        Set.of("analytics:dashboard:read"));
        TestAuthorizationContext.set(actor);
        if (!sessions.getConfiguration().hasMapper(Queries.class))
            sessions.getConfiguration().addMapper(Queries.class);
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void targetSettingsIncludeNewSalesAndUseDepartmentScopeWithoutAnyOrders() {
        department(1,null,"SALES","销售部");
        department(10,1L,"CITY-BJ","北京市");
        department(20,1L,"CITY-HZ","杭州市");
        productionMapper.refreshCityDimension(tenant);
        jdbc.update("INSERT INTO bi_employee_dim(tenant_id,employee_code,employee_name,employment_status,department_id,department_path,synced_time) VALUES(?,'NEW','新销售','ACTIVE',10,JSON_ARRAY(1,10),UTC_TIMESTAMP(6)),(?,'OTHER','异地销售','ACTIVE',20,JSON_ARRAY(1,20),UTC_TIMESTAMP(6))",tenant,tenant);
        var store=new com.rigour.analytics.infrastructure.persistence.repository.JdbcTargetSettingsStore(jdbc);
        assertThat(store.subjects(tenant)).filteredOn(s -> "SALES_OWNER".equals(s.dimensionType())).hasSize(2);
        var p=policy(List.of(clause("DEPARTMENT",specified("10"),all(),all())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(p);
        try(var ctx=SupplyAuthorizationContext.open(iam,actor,p)) {
            assertThat(store.permitted(tenant,"CITY","CITY-BJ","analytics:dashboard:read")).isTrue();
            assertThat(store.permitted(tenant,"CITY","CITY-HZ","analytics:dashboard:read")).isFalse();
            assertThat(store.permitted(tenant,"SALES_OWNER","NEW","analytics:dashboard:read")).isTrue();
            assertThat(store.permitted(tenant,"SALES_OWNER","OTHER","analytics:dashboard:read")).isFalse();
        }
    }

    @Test
    void cityDefaultsRespectEffectiveMonthAndExplicitZeroOnRealMysql() {
        department(1,null,"SALES","销售部");
        department(10,1L,"CITY-BJ","北京市");
        productionMapper.refreshCityDimension(tenant);
        jdbc.update("INSERT INTO bi_target_default(tenant_id,dimension_type,metric_code,effective_month,target_value,revision,updated_by,updated_at) VALUES(?,'CITY','SALES_AMOUNT','2026-11-01',125000,1,'test',UTC_TIMESTAMP(6))",tenant);
        jdbc.update("INSERT INTO bi_business_target(tenant_id,target_month,dimension_type,dimension_code,dimension_name,metric_code,target_value,source_system_code,synced_time) VALUES(?,'2026-12-01','CITY','CITY-BJ','北京市','SALES_AMOUNT',0,'BI_MANUAL',UTC_TIMESTAMP(6))",tenant);
        var p=policy(List.of(clause("ALL",all(),all(),all())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(p);
        try(var ctx=SupplyAuthorizationContext.open(iam,actor,p)) {
            var goals=productionMapper.cityMonthlyGoals(tenant,2026,null);
            assertThat(goals).hasSize(12);
            var values=goals.stream().map(r -> new java.math.BigDecimal(r.get("salesTarget").toString())).toList();
            assertThat(values.get(9)).isEqualByComparingTo("100000");
            assertThat(values.get(10)).isEqualByComparingTo("125000");
            assertThat(values.get(11)).isZero();
        }
    }

    @Test
    void importedOrdersUseRecognizedOperatingCityForEachDepartmentWithoutOpeningUnknownOrSelf() {
        department(1, null, "SALES", "销售部");
        department(10, 1L, "CITY-BJ", "北京市");
        department(20, 1L, "CITY-HZ", "杭州市");
        productionMapper.refreshCityDimension(tenant);
        fact(1, "A", "CITY-BJ", 10, 11, 100);
        fact(2, "B", "CITY-HZ", 20, 11, 200);
        fact(3, "A", "UNKNOWN", 10, 11, 900);
        jdbc.update("UPDATE bi_order_authority SET attribution_state='REVIEW',employee_code=NULL,department_id=NULL,department_path=JSON_ARRAY() WHERE tenant_id=?", tenant);
        for (String department : List.of("10", "20")) {
            var policy = policy(List.of(clause("DEPARTMENT", specified(department), all(), all())), all(), all());
            when(iam.authorization(any(), any())).thenReturn(policy);
            try (var context = SupplyAuthorizationContext.open(iam, actor, policy);
                    var session = sessions.openSession()) {
                assertThat(session.getMapper(Queries.class).total(tenant)).isEqualTo(department.equals("10") ? 100 : 200);
                var mapper = session.getMapper(com.rigour.analytics.infrastructure.persistence.mapper.BiDataScopeMapper.class);
                var regions = mapper.filterOptions(tenant, null, null).stream()
                        .filter(row -> "REGION".equals(row.get("optionType")))
                        .map(row -> row.get("optionValue")).toList();
                assertThat(regions).containsExactly(department.equals("10") ? "CITY-BJ" : "CITY-HZ");
                assertThat(mapper.filterOptions(tenant, List.of(department.equals("10") ? "CITY-HZ" : "CITY-BJ"), null)).isEmpty();
            }
        }
        var self = policy(List.of(clause("SELF", none(), all(), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(self);
        try (var context = SupplyAuthorizationContext.open(iam, actor, self);
                var session = sessions.openSession()) {
            assertThat(session.getMapper(Queries.class).count(tenant)).isZero();
        }
    }

    @Test
    void departmentAndSelfUseOwnersAndDoNotGrantUnattributedInventory() {
        jdbc.update("INSERT INTO bi_source_hr_hr_employee(id,tenant_id,employee_code,employee_name,employment_status,department_id,department_path) VALUES(1,?,'A','员工甲','ACTIVE',10,JSON_ARRAY(10)),(2,?,'B','员工乙','ACTIVE',20,JSON_ARRAY(20))", tenant, tenant);
        jdbc.update("INSERT INTO bi_customer_authority(tenant_id,customer_id,employee_code,region_code,region_path,source_revision) VALUES(?,1,'A','HZ',JSON_ARRAY('HZ'),1),(?,2,'B','HZ',JSON_ARRAY('HZ'),1),(?,3,'A','NB',JSON_ARRAY('NB'),1)", tenant, tenant, tenant);
        var policy = policy(List.of(clause("DEPARTMENT", specified("10"), all(), all())), all(), all());
        var filter = com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates.predicate(policy, "CUSTOMER");
        var args = new ArrayList<Object>(); args.add(tenant); args.addAll(filter.args());
        assertThat(jdbc.queryForList("SELECT a.customer_id FROM bi_customer_authority a WHERE a.tenant_id=? AND " + filter.text(), Long.class, args.toArray())).containsExactly(1L, 3L);
        var inventory = com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates.predicate(policy, "INVENTORY");
        assertThat(jdbc.queryForList("SELECT f.warehouse_id FROM (SELECT 7 warehouse_id UNION ALL SELECT 8) f WHERE " + inventory.text(), Long.class, inventory.args().toArray())).isEmpty();
        var self = policy(List.of(clause("SELF", all(), all(), all())), all(), all());
        var selfFilter = com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates.predicate(self, "CUSTOMER");
        var selfArgs = new ArrayList<Object>(); selfArgs.add(tenant); selfArgs.addAll(selfFilter.args());
        assertThat(jdbc.queryForList("SELECT a.customer_id FROM bi_customer_authority a WHERE a.tenant_id=? AND " + selfFilter.text(), Long.class, selfArgs.toArray())).containsExactly(1L, 3L);
    }

    @Test
    void salesDepartmentsProvideZeroOrderCityGoalsAndRespectAuthorization() {
        for (var city : List.of("HZ", "NB")) {
            jdbc.update("INSERT INTO bi_source_crm_crm_customer_area"
                    + " (id,tenant_id,area_code,area_name,status,deleted)"
                    + " VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'ACTIVE',0)",
                    UUID.randomUUID().toString(),tenant,city,city);
        }
        department(1,null,"SALES","销售部");
        department(2,1L,"HZ","HZ");
        department(3,1L,"NB","NB");
        department(4,null,"KA","KA销售部");
        assertThat(productionMapper.refreshCityDimension(tenant)).isEqualTo(2);
        jdbc.update("INSERT INTO bi_region_authority(tenant_id,region_code,region_path)"
                    + " VALUES(?,'HZ',JSON_ARRAY('HZ')),(?,'NB',JSON_ARRAY('NB'))",tenant,tenant);
        var p = policy(List.of(clause("REGION", none(), specified("HZ"), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(p);
        try (var ctx = SupplyAuthorizationContext.open(iam, actor, p)) {
            var goals = productionMapper.cityMonthlyGoals(tenant,2026,null);
            assertThat(goals).hasSize(12);
            assertThat(goals).allSatisfy(row -> {
                assertThat(row.get("regionCode")).isEqualTo("HZ");
                assertThat(new java.math.BigDecimal(row.get("salesTarget").toString()))
                        .isEqualByComparingTo("100000");
            });
        }
    }

    @Test
    void repeatCustomerHistorySurvivesCityAndSalesTransferWhileCurrentOrdersRemainScoped() {
        fact(1,"B","OTHER_CITY",20,11,20);
        fact(2,"A","HZ",10,11,30);
        jdbc.update("UPDATE bi_sales_order_fact SET customer_id=7,order_status_code='COMPLETED',order_date=CASE WHEN order_id=1 THEN '2026-08-01' ELSE '2026-09-15' END WHERE tenant_id=?",tenant);
        dashboardProducts.refresh(tenant,1,java.time.Instant.now());
        var self=policy(List.of(clause("SELF",none(),all(),none())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(self);
        try(var ctx=SupplyAuthorizationContext.open(iam,actor,self)) {
            var result=productionMapper.customerRetention(tenant,java.time.LocalDateTime.parse("2026-08-31T16:00:00"),
                    java.time.LocalDateTime.parse("2026-09-30T16:00:00"),null,null,null,null);
            assertThat(((Number)result.get("orderingCustomerCount")).longValue()).isEqualTo(1);
            assertThat(((Number)result.get("returningCustomerCount")).longValue()).isEqualTo(1);
        }
    }

    void department(long id,Long parent,String code,String name) {
        jdbc.update("INSERT INTO bi_source_hr_hr_department(id,tenant_id,department_code,department_name,parent_id,status_code,deleted,department_path) VALUES(?,?,?,?,?,'ACTIVE',0,JSON_ARRAY(1,?))",id,tenant,code,name,parent,id);
    }

    @Test
    void historicalOperatingCitiesSurviveEmployeeTransfersAndConserveLateReceiptsWithoutChangingAuthority() {
        department(1,null,"SALES","销售部");
        department(10,1L,"CITY-HZ","杭州市");
        department(20,1L,"CITY-BJ","北京市");
        department(30,1L,"CITY-JH","金华市");
        department(40,null,"KA","KA销售部");
        jdbc.update("INSERT INTO bi_source_crm_crm_customer_area(id,tenant_id,area_code,area_name,status,deleted) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'HZ','杭州市','ACTIVE',0)",UUID.randomUUID().toString(),tenant);
        productionMapper.refreshCityDimension(tenant);
        fact(1,"A","GZ",10,11,100); // frozen Hangzhou department, Guangzhou customer region
        fact(2,"B","GZ",20,11,200);
        fact(3,"C","GZ",30,11,300);
        fact(4,"B","CITY-BJ",20,11,400); // current fact/HR city cannot fill an unknown source city
        jdbc.update("INSERT INTO bi_source_order_order_sales_order(id,tenant_id,region_code,owner_employee_code) VALUES(1,?,'CITY-BJ','A'),(2,?,'HZ','B'),(3,?,'CITY-BJ','C'),(4,?,'UNMAPPED','B')",tenant,tenant,tenant,tenant);
        jdbc.update("UPDATE bi_order_authority SET attribution_state='REVIEW',department_id=NULL,department_path=JSON_ARRAY() WHERE tenant_id=? AND order_id IN(2,3,4)",tenant);
        jdbc.update("INSERT INTO bi_source_hr_hr_employee(id,tenant_id,employee_code,employee_name,employment_status,department_id,department_path) VALUES (1,?,'A','A','ACTIVE',20,JSON_ARRAY(1,20)),(2,?,'B','B','ACTIVE',30,JSON_ARRAY(1,30)),(3,?,'C','C','ACTIVE',40,JSON_ARRAY(40))",tenant,tenant,tenant);
        jdbc.update("INSERT INTO bi_sales_payment_fact(tenant_id,payment_id,order_id,payment_time,paid_amount,synced_time) VALUES(?,1,2,'2026-09-15',50,UTC_TIMESTAMP(6))",tenant);
        jdbc.update("INSERT INTO bi_sales_order_line_fact(tenant_id,order_id,order_line_id,order_date,synced_time) VALUES(?,2,1,'2026-08-01',UTC_TIMESTAMP(6))",tenant);
        for (int repeat=0;repeat<3;repeat++) {
            jdbc.update("UPDATE bi_source_hr_hr_employee SET department_id=?,department_path=JSON_ARRAY(1,?) WHERE tenant_id=?",repeat==0 ? 30 : 20,repeat==0 ? 30 : 20,tenant);
            productionMapper.clearCityDimension(tenant);
            productionMapper.refreshCityDimension(tenant);
            productionMapper.alignOperatingOrderCities(tenant);
            productionMapper.alignOperatingOrderLineCities(tenant);
            productionMapper.alignOperatingPaymentCities(tenant);
            assertThat(jdbc.queryForList("SELECT region_code FROM bi_sales_order_fact WHERE tenant_id=? ORDER BY order_id",String.class,tenant)).containsExactly("CITY-HZ","CITY-HZ","CITY-BJ","UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT region_code FROM bi_sales_payment_fact WHERE tenant_id=?",String.class,tenant)).isEqualTo("CITY-HZ");
            assertThat(jdbc.queryForObject("SELECT region_code FROM bi_sales_order_line_fact WHERE tenant_id=?",String.class,tenant)).isEqualTo("CITY-HZ");
        }
        assertThat(jdbc.queryForList("SELECT region_code FROM bi_source_order_order_sales_order WHERE tenant_id=? ORDER BY id",String.class,tenant)).containsExactly("CITY-BJ","HZ","CITY-BJ","UNMAPPED");
        assertThat(jdbc.queryForObject("SELECT SUM(payable_amount) FROM bi_sales_order_fact WHERE tenant_id=?",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT region_code FROM bi_order_authority WHERE tenant_id=? AND order_id=1",String.class,tenant)).isEqualTo("GZ");
        var geographical=policy(List.of(clause("REGION",none(),specified("GZ"),none())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(geographical);
        try(var ctx=SupplyAuthorizationContext.open(iam,actor,geographical);var session=sessions.openSession()) {
            assertThat(session.getMapper(Queries.class).total(tenant)).isEqualTo(100);
        }
        var p=policy(List.of(clause("DEPARTMENT",specified("10"),all(),none())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(p);
        try(var ctx=SupplyAuthorizationContext.open(iam,actor,p)) {
            assertThat(productionMapper.cityMonthlyGoals(tenant,2026,null)).hasSize(12).allSatisfy(row->assertThat(row.get("regionCode")).isEqualTo("CITY-HZ"));
        }
    }

    @Test
    void frozenCitiesUseFrozenRegionsOnlyWhenDepartmentIsMissingAndAmbiguousCitiesRemainUnknown() {
        department(1,null,"SALES","销售部");
        department(10,1L,"CITY-HZ","杭州市");
        department(20,1L,"CITY-BJ","北京市");
        productionMapper.refreshCityDimension(tenant);
        jdbc.update("UPDATE bi_sales_contact_city_dim SET source_region_code='AMBIGUOUS' WHERE tenant_id=?",tenant);
        fact(1,"A","CITY-HZ",10,11,100);
        fact(2,"A","CITY-HZ",30,11,200);
        fact(3,"A","CITY-HZ",10,11,300);
        fact(4,"A","CITY-HZ",10,11,400);
        jdbc.update("UPDATE bi_order_authority SET department_id=NULL,department_path=JSON_ARRAY() WHERE tenant_id=? AND order_id=1",tenant);
        jdbc.update("UPDATE bi_order_authority SET department_path=JSON_ARRAY(30) WHERE tenant_id=? AND order_id=2",tenant);
        jdbc.update("UPDATE bi_order_authority SET attribution_state='REVIEW' WHERE tenant_id=? AND order_id IN(3,4)",tenant);
        jdbc.update("INSERT INTO bi_source_order_order_sales_order(id,tenant_id,region_code) VALUES(1,?,'CITY-BJ'),(2,?,'CITY-BJ'),(3,?,'AMBIGUOUS')",tenant,tenant,tenant);
        productionMapper.alignOperatingOrderCities(tenant);
        assertThat(jdbc.queryForList("SELECT region_code FROM bi_sales_order_fact WHERE tenant_id=? ORDER BY order_id",String.class,tenant))
                .containsExactly("CITY-HZ","OUTSIDE_CITY","UNKNOWN","UNKNOWN");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
            value = {
                "COLLECTOR,CUSTOMER,0,经办人快照,经办人现名,COLLECTOR,经办人现名",
                "NO_HR,CUSTOMER,0,经办人快照,NULL,NO_HR,经办人快照",
                "NO_HR,CUSTOMER,0,NULL,NULL,NO_HR,NO_HR",
                "NULL,CUSTOMER,0,NULL,NULL,CUSTOMER,客户业务员快照",
                "'',CUSTOMER,0,无效经办人快照,NULL,CUSTOMER,客户业务员快照",
                "'   ',CUSTOMER,0,无效经办人快照,NULL,CUSTOMER,客户业务员快照",
                "unknown,CUSTOMER,0,无效经办人快照,NULL,CUSTOMER,客户业务员快照",
                "multi,CUSTOMER,0,无效经办人快照,NULL,CUSTOMER,客户业务员快照",
                "NULL,NULL,0,NULL,NULL,NULL,NULL",
                "NULL,UNKNOWN,0,NULL,NULL,NULL,NULL",
                "NULL,MULTI,0,NULL,NULL,NULL,NULL",
                "NULL,'',0,NULL,NULL,NULL,NULL",
                "NULL,'   ',0,NULL,NULL,NULL,NULL",
                "NULL,CUSTOMER,1,NULL,NULL,NULL,NULL"
            },
            nullValues = "NULL")
    void paymentPerformanceUsesCollectorThenCurrentCustomerWithoutChangingSourceOrCity(
            String collector, String customerOwner, int customerDeleted, String collectorSnapshot,
            String collectorHrName, String expectedOwner, String expectedName) {
        // 与后台 ETL 一致，不带浏览器租户请求的授权上下文。
        TestAuthorizationContext.clear();
        jdbc.update("""
                INSERT INTO bi_source_order_order_sales_order
                    (tenant_id,id,customer_id,region_code,owner_employee_code,owner_employee_name_snapshot,updated_time)
                VALUES(?,10,20,'HZ','ORDER_OWNER','订单业务员','2026-09-02')
                """, tenant);
        jdbc.update("""
                INSERT INTO bi_source_crm_crm_customer
                    (tenant_id,id,region_code,owner_employee_code,owner_employee_name_snapshot,deleted,updated_time)
                VALUES(?,20,'BJ',?,'客户业务员快照',?,'2026-09-02')
                """, tenant,customerOwner,customerDeleted);
        // customer_id 为空时沿用订单关联客户；不能因此使用订单业务员作业绩兜底。
        jdbc.update("""
                INSERT INTO bi_source_order_order_payment_record
                    (tenant_id,id,order_id,collector_staff_code,collector_name_snapshot,payment_time,paid_amount,updated_time,deleted)
                VALUES(?,101,10,?,?,'2026-09-01 08:30:00',125.375,'2026-09-02',0)
                """, tenant,collector,collectorSnapshot);
        if (collectorHrName != null) {
            jdbc.update("INSERT INTO bi_source_hr_hr_employee(tenant_id,id,employee_code,employee_name,employment_status) VALUES(?,1,?,?,'ACTIVE')",
                    tenant,collector,collectorHrName);
        }
        jdbc.update("INSERT INTO bi_source_hr_hr_employee(tenant_id,id,employee_code,employee_name,employment_status) VALUES(?,1,'NO_HR','其他租户员工','ACTIVE')",
                UUID.randomUUID().toString());
        var from=java.time.LocalDateTime.parse("2026-09-01T00:00:00");
        var to=java.time.LocalDateTime.parse("2026-09-03T00:00:00");
        productionMapper.upsertSalesPaymentFactFromSource(tenant,from,to,to);
        var original=jdbc.queryForMap("""
                SELECT owner_staff_code,owner_staff_name,collector_staff_code,collector_staff_name,
                       region_code,payment_time,paid_amount
                  FROM bi_sales_payment_fact WHERE tenant_id=? AND payment_id=101
                """,tenant);
        assertThat(original.get("owner_staff_code")).isEqualTo(expectedOwner);
        assertThat(original.get("owner_staff_name")).isEqualTo(expectedName);
        assertThat(original.get("collector_staff_code")).isEqualTo(collector);
        assertThat(original.get("collector_staff_name")).isEqualTo(collectorSnapshot);
        assertThat(original.get("region_code")).isEqualTo("HZ");
        assertThat(original.get("payment_time")).isEqualTo(java.time.LocalDateTime.parse("2026-09-01T08:30:00"));
        assertThat((java.math.BigDecimal)original.get("paid_amount")).isEqualByComparingTo("125.375");
        var repository=new com.rigour.analytics.infrastructure.persistence.repository.MybatisPlusSupplyDashboardRepository(productionMapper);
        assertThat(repository.refreshTargetNeedsBackfill(tenant,"ORDER_PAYMENT_RECORD")).isFalse();

        jdbc.update("UPDATE bi_sales_payment_fact SET owner_staff_code='ORDER_OWNER',owner_staff_name='错误姓名' WHERE tenant_id=?",tenant);
        assertThat(repository.refreshTargetNeedsBackfill(tenant,"ORDER_PAYMENT_RECORD")).isTrue();
        assertThat(productionMapper.alignPaymentOrderAttribution(tenant,to.plusHours(1))).isEqualTo(1);
        assertThat(productionMapper.alignPaymentOrderAttribution(tenant,to.plusHours(2))).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT owner_staff_code,owner_staff_name,collector_staff_code,collector_staff_name,
                       region_code,payment_time,paid_amount
                  FROM bi_sales_payment_fact WHERE tenant_id=? AND payment_id=101
                """,tenant)).isEqualTo(original);
        assertThat(repository.refreshTargetNeedsBackfill(tenant,"ORDER_PAYMENT_RECORD")).isFalse();
        assertThat(jdbc.queryForMap("SELECT collector_staff_code,collector_name_snapshot FROM bi_source_order_order_payment_record WHERE tenant_id=?",tenant))
                .containsEntry("collector_staff_code",collector).containsEntry("collector_name_snapshot",collectorSnapshot);
        assertThat(jdbc.queryForObject("SELECT region_code FROM bi_source_order_order_sales_order WHERE tenant_id=?",String.class,tenant)).isEqualTo("HZ");
    }

    @Test
    void dashboardAllocationsPublishOnMysqlAndConserveLatePaymentAndDiscountedOrderAmounts() {
        jdbc.update("""
                INSERT INTO bi_sales_order_fact(tenant_id,order_id,order_date,payable_amount,paid_amount,synced_time)
                VALUES(?,1,'2026-08-01',10,5,UTC_TIMESTAMP(6))
                """, tenant);
        for (int i=1;i<=3;i++) jdbc.update("""
                INSERT INTO bi_sales_order_line_fact(tenant_id,order_id,order_line_id,order_date,unit_price,quantity,synced_time)
                VALUES(?,1,?,'2026-08-01',1,1,UTC_TIMESTAMP(6))
                """, tenant,i);
        jdbc.update("""
                INSERT INTO bi_sales_payment_fact(tenant_id,payment_id,order_id,payment_time,paid_amount,synced_time)
                VALUES(?,1,1,'2026-09-15',5,UTC_TIMESTAMP(6))
                """,tenant);
        dashboardProducts.refresh(tenant,1,java.time.Instant.now());
        dashboardProducts.refresh(tenant,2,java.time.Instant.now());
        assertThat(jdbc.queryForObject("SELECT SUM(sales_amount) FROM bi_dashboard_order_product WHERE tenant_id=?",
                java.math.BigDecimal.class,tenant)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT SUM(cohort_paid_amount) FROM bi_dashboard_order_product WHERE tenant_id=?",
                java.math.BigDecimal.class,tenant)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT SUM(allocated_amount) FROM bi_dashboard_payment_product WHERE tenant_id=? AND payment_time>='2026-09-01'",
                java.math.BigDecimal.class,tenant)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_dashboard_payment_product WHERE tenant_id=?",
                Integer.class,tenant)).isEqualTo(3);
    }

    @Test
    void sourceMirrorsPreserveDecimalPrecisionAndRejectCrossTenantBatchAtomically() {
        when(sourceSnapshots.version(any(), any(), any())).thenReturn("v1");
        when(sourceSnapshots.page(any(), any(), any(), any()))
                .thenReturn(
                        new com.rigour.analytics.application.port.out.BiSourceSnapshotClient.Page(
                                "v1", List.of()));
        var definition =
                com.rigour.analytics.infrastructure.persistence.scope.BiSourceDatasets.ALL.stream()
                        .filter(d -> d.code().equals("ORDER_PAYMENT_RECORD"))
                        .findFirst()
                        .orElseThrow();
        Map<String, String> row = new LinkedHashMap<>();
        definition.columns().forEach(c -> row.put(c.name(), null));
        row.put("id", "9007199254740993");
        row.put("tenant_id", tenant);
        row.put("paid_amount", "123456789012.123456");
        when(sourceSnapshots.page(actor.tenantId(), "ORDER", "ORDER_PAYMENT_RECORD", ""))
                .thenReturn(
                        new com.rigour.analytics.application.port.out.BiSourceSnapshotClient.Page(
                                "v1", List.of(row)));
        sourceProjector.refresh(actor.tenantId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT paid_amount FROM bi_source_order_order_payment_record WHERE"
                                        + " tenant_id=? AND id=9007199254740993",
                                java.math.BigDecimal.class,
                                tenant))
                .isEqualByComparingTo("123456789012.123456");
        var wrong = new LinkedHashMap<>(row);
        wrong.put("tenant_id", UUID.randomUUID().toString());
        when(sourceSnapshots.version(actor.tenantId(), "ORDER", "ORDER_PAYMENT_RECORD"))
                .thenReturn("v2");
        when(sourceSnapshots.page(actor.tenantId(), "ORDER", "ORDER_PAYMENT_RECORD", ""))
                .thenReturn(
                        new com.rigour.analytics.application.port.out.BiSourceSnapshotClient.Page(
                                "v2", List.of(wrong)));
        assertThatThrownBy(() -> sourceProjector.refresh(actor.tenantId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bi_source_order_order_payment_record WHERE"
                                        + " tenant_id=?",
                                Integer.class,
                                tenant))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_version FROM bi_source_snapshot_checkpoint WHERE"
                                        + " tenant_id=? AND dataset='ORDER_PAYMENT_RECORD'",
                                String.class,
                                tenant))
                .isEqualTo("v1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void partialRefreshCannotConsumeDepartmentChangeWithoutPublishingPeopleProjection(boolean organizationOnly) {
        when(sourceSnapshots.version(any(), any(), any())).thenReturn("v1");
        when(sourceSnapshots.page(any(), any(), any(), any()))
                .thenReturn(
                        new com.rigour.analytics.application.port.out.BiSourceSnapshotClient.Page(
                                "v1", List.of()));
        if (organizationOnly)
            when(sourceSnapshots.version(any(), eq("SALES"), any()))
                    .thenThrow(new IllegalStateException("Sales service not deployed"));
        when(authority.version(any(), any())).thenReturn("v1");
        when(authority.page(any(), any(), anyLong()))
                .thenReturn(
                        new com.rigour.analytics.application.port.out.BiAuthoritySource.Page(
                                "v1", List.of(), List.of()));
        var definition =
                com.rigour.analytics.infrastructure.persistence.scope.BiSourceDatasets.ALL.stream()
                        .filter(d -> d.code().equals("HR_EMPLOYEE"))
                        .findFirst()
                        .orElseThrow();
        Map<String, String> row = new LinkedHashMap<>();
        definition.columns().forEach(c -> row.put(c.name(), null));
        row.putAll(
                Map.of(
                        "id",
                        "1",
                        "tenant_id",
                        tenant,
                        "employee_code",
                        "A",
                        "employee_name",
                        "张三",
                        "employment_status",
                        "ACTIVE",
                        "department_id",
                        "10",
                        "department_path",
                        "[10]"));
        when(sourceSnapshots.page(actor.tenantId(), "HR", "HR_EMPLOYEE", ""))
                .thenReturn(
                        new com.rigour.analytics.application.port.out.BiSourceSnapshotClient.Page(
                                "v1", List.of(row)));
        dashboardStore.synchronizeSourceSnapshots(tenant);
        if (organizationOnly) peopleProjector.refreshOrganization(actor.tenantId());
        else dashboardStore.synchronizePeopleSnapshots(tenant);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT department_id FROM bi_employee_dim WHERE tenant_id=? AND"
                                    + " employee_code='A'",
                                Long.class,
                                tenant))
                .isEqualTo(10L);

        var moved = new LinkedHashMap<>(row);
        moved.put("department_id", "20");
        moved.put("department_path", "[20]");
        when(sourceSnapshots.version(actor.tenantId(), "HR", "HR_EMPLOYEE")).thenReturn("v2");
        when(sourceSnapshots.page(actor.tenantId(), "HR", "HR_EMPLOYEE", ""))
                .thenReturn(
                        new com.rigour.analytics.application.port.out.BiSourceSnapshotClient.Page(
                                "v2", List.of(moved)));
        sourceProjector.refresh(actor.tenantId());
        if (organizationOnly) peopleProjector.refreshOrganization(actor.tenantId());
        else peopleProjector.refresh(actor.tenantId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT department_id FROM bi_employee_dim WHERE tenant_id=? AND"
                                    + " employee_code='A'",
                                Long.class,
                                tenant))
                .isEqualTo(20L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_version FROM bi_source_snapshot_checkpoint WHERE"
                                    + " tenant_id=? AND dataset=?",
                                String.class,
                                tenant,
                                (organizationOnly ? "ORGANIZATION_" : "PEOPLE_") + "HR_EMPLOYEE"))
                .isEqualTo("v2");
        if (organizationOnly) {
            verify(sourceSnapshots, never()).version(any(), eq("SALES"), any());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_sales_contact_snapshot WHERE tenant_id=?", Integer.class, tenant)).isZero();
            when(sourceSnapshots.version(actor.tenantId(), "HR", "HR_EMPLOYEE"))
                    .thenThrow(new IllegalStateException("HR unavailable"));
            assertThatThrownBy(() -> peopleProjector.refreshOrganization(actor.tenantId()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void alignmentUsesFrozenOrderEvenWhenCurrentCustomerBelongsToSomeoneElse() {
        fact(1, "A", "HZ", 10, 11, 100);
        jdbc.update(
                "UPDATE bi_sales_order_fact SET owner_staff_code='WRONG',region_code='NB' WHERE"
                        + " tenant_id=?",
                tenant);
        jdbc.update(
                "INSERT INTO"
                    + " bi_customer_dim(tenant_id,customer_id,owner_staff_code,region_code,synced_time)"
                    + " VALUES(?,1,'CURRENT','NB',UTC_TIMESTAMP(6))",
                tenant);
        productionMapper.alignOrderFactAttribution(tenant, java.time.LocalDateTime.now());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT owner_staff_code FROM bi_sales_order_fact WHERE"
                                        + " tenant_id=?",
                                String.class,
                                tenant))
                .isEqualTo("A");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT region_code FROM bi_sales_order_fact WHERE tenant_id=?",
                                String.class,
                                tenant))
                .isEqualTo("HZ");
    }

    @Test
    void sourceTransferRefreshesCurrentAuthorityAndInterruptedGenerationRollsBack() {
        var empty =
                new com.rigour.analytics.application.port.out.BiAuthoritySource.Page(
                        "o1", List.of(), List.of());
        when(authority.version(actor.tenantId(), "ORDER")).thenReturn("o1");
        when(authority.page(actor.tenantId(), "ORDER", 0)).thenReturn(empty);
        var first =
                new com.rigour.analytics.application.port.out.BiAuthoritySource.Page(
                        "c1",
                        List.of(
                                Map.of(
                                        "id",
                                        1,
                                        "employeeCode",
                                        "A",
                                        "regionCode",
                                        "HZ",
                                        "regionPath",
                                        List.of("ZJ", "HZ"),
                                        "revision",
                                        1)),
                        List.of(Map.of("code", "HZ", "path", List.of("ZJ", "HZ"))));
        when(authority.version(actor.tenantId(), "CRM")).thenReturn("c1");
        when(authority.page(actor.tenantId(), "CRM", 0)).thenReturn(first);
        projector.refresh(actor.tenantId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT employee_code FROM bi_customer_authority WHERE tenant_id=?",
                                String.class,
                                tenant))
                .isEqualTo("A");
        var second =
                new com.rigour.analytics.application.port.out.BiAuthoritySource.Page(
                        "c2",
                        List.of(
                                Map.of(
                                        "id",
                                        1,
                                        "employeeCode",
                                        "B",
                                        "regionCode",
                                        "HZ",
                                        "regionPath",
                                        List.of("ZJ", "HZ"),
                                        "revision",
                                        2)),
                        first.regions());
        when(authority.version(actor.tenantId(), "CRM")).thenReturn("c2");
        when(authority.page(actor.tenantId(), "CRM", 0)).thenReturn(second);
        projector.refresh(actor.tenantId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT employee_code FROM bi_customer_authority WHERE tenant_id=?",
                                String.class,
                                tenant))
                .isEqualTo("B");
        when(authority.version(actor.tenantId(), "CRM")).thenReturn("c3");
        assertThatThrownBy(() -> projector.refresh(actor.tenantId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT employee_code FROM bi_customer_authority WHERE tenant_id=?",
                                String.class,
                                tenant))
                .isEqualTo("B");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_version FROM bi_authority_checkpoint WHERE"
                                        + " tenant_id=? AND source_code='CRM'",
                                String.class,
                                tenant))
                .isEqualTo("c2");
    }

    @Test
    void aggregatePaginationAndNestedQueriesUseCompleteClausesAndCaps() {
        fact(1, "A", "HZ", 10, 11, 100);
        fact(2, "B", "NB", 20, 22, 900);
        fact(3, "B", "HZ", 20, 22, 500);
        fact(4, "A", "NB", 10, 11, 700);
        var clauses =
                List.of(
                        clause("SELF", none(), specified("HZ"), specified("11")),
                        clause("REGION", none(), specified("NB"), specified("22")));
        var policy = policy(clauses, all(), all());
        when(iam.authorization(any(), any())).thenReturn(policy);
        try (var authorization = SupplyAuthorizationContext.open(iam, actor, policy);
                var session = sessions.openSession()) {
            var mapper = session.getMapper(Queries.class);
            assertThat(mapper.total(tenant)).isEqualTo(1000);
            assertThat(mapper.count(tenant)).isEqualTo(2);
            assertThat(mapper.page(tenant, 1)).containsExactly(1L);
            assertThat(mapper.nested(tenant)).isEqualTo(1000);
        }
        var capped = policy(clauses, specified("HZ"), specified("11"));
        when(iam.authorization(any(), any())).thenReturn(capped);
        try (var authorization = SupplyAuthorizationContext.open(iam, actor, capped);
                var session = sessions.openSession()) {
            assertThat(session.getMapper(Queries.class).total(tenant)).isEqualTo(100);
        }
    }

    @Test
    void oldRoleNameCannotBypassDepartmentAndAllScopeIncludesTenantInventory() {
        fact(1, "A", "HZ", 10, 11, 100);
        fact(2, "B", "HZ", 20, 22, 900);
        var policy =
                policy(List.of(clause("DEPARTMENT", specified("1"), all(), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(policy);
        try (var authorization = SupplyAuthorizationContext.open(iam, actor, policy);
                var session = sessions.openSession()) {
            assertThat(session.getMapper(Queries.class).total(tenant)).isEqualTo(100);
        }
        jdbc.update(
                "INSERT INTO"
                    + " bi_inventory_balance_current(tenant_id,warehouse_id,product_id,product_variant_id,available_quantity,synced_time)"
                    + " VALUES(?,11,1,1,3,UTC_TIMESTAMP(6)),(?,22,1,1,9,UTC_TIMESTAMP(6))",
                tenant,
                tenant);
        var inventory =
                policy(List.of(clause("ALL", all(), all(), all())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(inventory);
        try (var authorization = SupplyAuthorizationContext.open(iam, actor, inventory);
                var session = sessions.openSession()) {
            assertThat(session.getMapper(Queries.class).stock(tenant)).isEqualTo(12);
            assertThatThrownBy(() -> session.getMapper(Queries.class).crossSchema(tenant))
                    .hasRootCauseInstanceOf(AuthorizationDeniedException.class);
        }
    }

    @Test
    void authorityMissingDoesNotGuessOwnerFromFactAndTenantIsAlwaysBound() {
        fact(1, "A", "HZ", 10, 11, 100);
        jdbc.update("DELETE FROM bi_order_authority WHERE tenant_id=?", tenant);
        var policy = policy(List.of(clause("SELF", none(), all(), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(policy);
        try (var authorization = SupplyAuthorizationContext.open(iam, actor, policy);
                var session = sessions.openSession()) {
            assertThat(session.getMapper(Queries.class).count(tenant)).isZero();
        }
    }

    void fact(
            long id, String employee, String region, long department, long warehouse, int amount) {
        jdbc.update(
                "INSERT INTO"
                    + " bi_sales_order_fact(tenant_id,order_id,owner_staff_code,region_code,order_date,payable_amount,synced_time)"
                    + " VALUES(?,?,?,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6))",
                tenant,
                id,
                employee,
                region,
                amount);
        jdbc.update(
                "INSERT INTO"
                    + " bi_order_authority(tenant_id,order_id,employee_code,department_id,department_path,region_code,region_path,warehouse_id,attribution_state)"
                    + " VALUES(?,?,?,?,?,?,?,?, 'FROZEN')",
                tenant,
                id,
                employee,
                department,
                department == 10 ? "[1,10]" : "[2,20]",
                region,
                "[\"ZJ\",\"" + region + "\"]",
                warehouse);
    }

    SupplyAuthorizationView policy(List<Clause> clauses, Limit regions, Limit warehouses) {
        return new SupplyAuthorizationView(
                "ACTIVE",
                actor.tenantId(),
                actor.userId(),
                "A",
                1,
                1,
                1,
                1,
                Set.of("analytics:dashboard:read"),
                "analytics:dashboard:read",
                true,
                clauses,
                regions,
                warehouses);
    }

    static Clause clause(String scope, Limit department, Limit region, Limit warehouse) {
        return new Clause(
                UUID.randomUUID(), "ANALYTICS", scope, department, region, warehouse, true);
    }

    static Limit none() {
        return new Limit("NONE", List.of());
    }

    static Limit all() {
        return new Limit("ALL", List.of());
    }

    static Limit specified(String... refs) {
        return new Limit("SPECIFIED", List.of(refs));
    }

    @Test
    void targetsRequireWholeFootprintAndWritesCannotBorrowReadScope() {
        fact(901, "A", "HZ", 10, 11, 100);
        fact(902, "B", "HZ", 10, 11, 100);
        fact(903, "B", "NB", 10, 11, 100);
        jdbc.update(
                "INSERT INTO bi_region_authority(tenant_id,region_code,region_path)"
                        + " VALUES(?,'HZ',JSON_ARRAY('HZ')),(?,'NB',JSON_ARRAY('NB'))",
                tenant,
                tenant);
        jdbc.update("INSERT INTO bi_sales_contact_city_dim(tenant_id,region_code,city_name,source_region_code) VALUES(?,'HZ','杭州','HZ'),(?,'NB','宁波','NB')",tenant,tenant);
        for (var target :
                List.of(
                        new String[] {"CITY", "HZ"},
                        new String[] {"CITY", "NB"},
                        new String[] {"SALES_OWNER", "A"},
                        new String[] {"SALES_OWNER", "B"}))
            jdbc.update(
                    "INSERT INTO"
                        + " bi_business_target(tenant_id,target_month,dimension_type,dimension_code,dimension_name,metric_code,target_value,synced_time)"
                        + " VALUES(?,'2026-09-01',?,?,?,'SALES_AMOUNT',100,UTC_TIMESTAMP(6))",
                    tenant,
                    target[0],
                    target[1],
                    target[1]);
        var p = policy(List.of(clause("REGION", none(), specified("HZ"), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(p);
        try (var ctx = SupplyAuthorizationContext.open(iam, actor, p);
                var session = sessions.openSession()) {
            assertThat(session.getMapper(Queries.class).targets(tenant))
                    .containsExactlyInAnyOrder("CITY:HZ", "SALES_OWNER:A");
            var denied =
                    new SupplyAuthorizationView(
                            "ACTIVE",
                            actor.tenantId(),
                            actor.userId(),
                            "A",
                            1,
                            1,
                            1,
                            1,
                            Set.of("analytics:targets:write"),
                            "analytics:targets:write",
                            true,
                            List.of(clause("REGION", none(), specified("NB"), none())),
                            all(),
                            all());
            when(iam.authorization(any(), eq("analytics:targets:write"))).thenReturn(denied);
            assertThatThrownBy(
                            () ->
                                    scopeService.requireObjectActionScope(
                                            "HZ", "A", "analytics:targets:write"))
                    .isInstanceOf(AuthorizationDeniedException.class);
            scopeService.requireObjectActionScope("NB", "B", "analytics:targets:write");
        }
    }

    @Test
    void departmentScopeFiltersCityCostsUsingCityDepartmentRatherThanRegionAuthority() {
        jdbc.update("INSERT INTO bi_sales_contact_city_dim(tenant_id,region_code,city_name,department_id,department_path) VALUES(?,'HZ','杭州',10,JSON_ARRAY(10)),(?,'NB','宁波',20,JSON_ARRAY(20))", tenant, tenant);
        jdbc.update("INSERT INTO bi_city_cost_record(tenant_id,region_code,cost_type_code,cost_date,cost_amount,source_system_code,source_record_id) VALUES(?,'HZ','COST',UTC_TIMESTAMP(6),5,'MANUAL_IMPORT','scope-hz'),(?,'NB','COST',UTC_TIMESTAMP(6),99,'MANUAL_IMPORT','scope-nb')", tenant, tenant);
        var p = policy(List.of(clause("DEPARTMENT", specified("10"), all(), all())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(p);
        try (var context = SupplyAuthorizationContext.open(iam, actor, p); var session = sessions.openSession()) {
            assertThat(scopeService.effective().unavailableSubjects()).doesNotContain("CITY_COST");
            assertThat(session.getMapper(Queries.class).cityCosts(tenant)).isEqualTo(5);
        }
    }

    interface Queries {
        @Select("SELECT COALESCE(SUM(cost_amount),0) FROM bi_city_cost_record WHERE tenant_id=#{tenant}")
        long cityCosts(@Param("tenant") String tenant);

        @Select(
                "SELECT CONCAT(dimension_type,':',dimension_code) FROM bi_business_target WHERE"
                        + " tenant_id=#{tenant} AND deleted=0")
        List<String> targets(@Param("tenant") String tenant);

        @Select(
                "SELECT COALESCE(SUM(payable_amount),0) FROM bi_sales_order_fact WHERE"
                        + " tenant_id=#{tenant}")
        long total(@Param("tenant") String tenant);

        @Select("SELECT COUNT(*) FROM bi_sales_order_fact WHERE tenant_id=#{tenant}")
        long count(@Param("tenant") String tenant);

        @Select(
                "SELECT order_id FROM bi_sales_order_fact WHERE tenant_id=#{tenant} ORDER BY"
                        + " order_id LIMIT #{limit}")
        List<Long> page(@Param("tenant") String tenant, @Param("limit") int limit);

        @Select(
                "WITH totals AS(SELECT SUM(payable_amount) amount FROM bi_sales_order_fact WHERE"
                        + " tenant_id=#{tenant}) SELECT amount FROM totals")
        long nested(@Param("tenant") String tenant);

        @Select(
                "SELECT SUM(available_quantity) FROM bi_inventory_balance_current WHERE"
                        + " tenant_id=#{tenant}")
        long stock(@Param("tenant") String tenant);

        @Select("SELECT COUNT(*) FROM rigour_order.order_sales_order WHERE tenant_id=#{tenant}")
        long crossSchema(@Param("tenant") String tenant);
    }




    @Autowired com.rigour.analytics.application.port.out.SupplyDashboardStore dashboardStore;
    @Autowired com.rigour.analytics.application.service.BiDataScopeService scopeService;

    @Test
    void cityCostImportUsesWriteScopeAndCannotAdoptAnOutOfScopeRecord() {
        jdbc.update(
                "INSERT INTO bi_region_authority(tenant_id,region_code,region_path)"
                        + " VALUES(?,'HZ',JSON_ARRAY('HZ')),(?,'NB',JSON_ARRAY('NB'))",
                tenant,
                tenant);
        var read = policy(List.of(clause("ALL", all(), all(), all())), all(), all());
        var write =
                new SupplyAuthorizationView(
                        "ACTIVE",
                        actor.tenantId(),
                        actor.userId(),
                        "A",
                        1,
                        1,
                        1,
                        1,
                        Set.of("analytics:city-cost:write"),
                        "analytics:city-cost:write",
                        true,
                        List.of(clause("REGION", none(), specified("HZ"), none())),
                        all(),
                        all());
        when(iam.authorization(any(), eq("analytics:city-cost:write"))).thenReturn(write);
        jdbc.update(
                "INSERT INTO"
                    + " bi_city_cost_record(tenant_id,region_code,cost_type_code,cost_date,cost_amount,source_system_code,source_record_id)"
                    + " VALUES(?,'NB','COST',UTC_TIMESTAMP(6),99,'MANUAL_IMPORT','old')",
                tenant);
        try (var ctx = SupplyAuthorizationContext.open(iam, actor, read)) {
            assertThatThrownBy(
                            () ->
                                    dashboardStore.importCityCostRecords(
                                            tenant,
                                            List.of(cost("new", "HZ"), cost("old", "HZ")),
                                            java.time.Instant.now()))
                    .isInstanceOf(AuthorizationDeniedException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM bi_city_cost_record WHERE tenant_id=? AND"
                                            + " source_record_id='new'",
                                    Integer.class,
                                    tenant))
                    .isZero();
            dashboardStore.importCityCostRecords(
                    tenant, List.of(cost("new", "HZ")), java.time.Instant.now());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT region_code FROM bi_city_cost_record WHERE tenant_id=?"
                                            + " AND source_record_id='old'",
                                    String.class,
                                    tenant))
                    .isEqualTo("NB");
        }
    }

    private com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostImportRow cost(
            String id, String region) {
        return new com.rigour.analytics.application.port.out.SupplyDashboardStore.CityCostImportRow(
                "MANUAL_IMPORT",
                id,
                region,
                region,
                "COST",
                "成本",
                java.time.Instant.now(),
                java.math.BigDecimal.TEN,
                java.math.BigDecimal.ZERO,
                null);
    }

    @Test
    void removedWarehouseModeCannotGrantInventory() {
        var p = policy(List.of(clause("WAREHOUSE", none(), none(), specified("11"))), all(), all());
        when(iam.authorization(any(), eq("analytics:dashboard:read"))).thenReturn(p);
        try (var ctx = SupplyAuthorizationContext.open(iam, actor, p)) {
            assertThat(scopeService.effective().unavailableSubjects())
                    .contains("INVENTORY", "CITY_COST", "SOURCE_GOVERNANCE");
        }
    }

    @Autowired com.rigour.analytics.application.port.out.EmployeeAnalyticsStore employeeStore;
    @Autowired com.rigour.analytics.application.port.out.VisitAnalyticsStore visitStore;

    @Autowired
    com.rigour.analytics.application.port.out.CustomerAttributeAnalyticsStore attributeStore;

    @Test
    void newJdbcDashboardsIntersectEachRoleThenApplyMemberRegionLimit() {
        jdbc.update(
                "INSERT INTO bi_region_authority(tenant_id,region_code,region_path)"
                        + " VALUES(?,'HZ',JSON_ARRAY('ZJ','HZ')),(?,'NB',JSON_ARRAY('ZJ','NB'))",
                tenant,
                tenant);
        String[] codes = {"A", "B", "C", "D"}, regions = {"HZ", "HZ", "NB", "NB"};
        int[] departments = {10, 20, 10, 20};
        for (int i = 0; i < 4; i++) {
            jdbc.update(
                    "INSERT INTO"
                        + " bi_employee_dim(tenant_id,employee_code,employee_name,employment_status,region_code,department_id,department_path,synced_time)"
                        + " VALUES(?,?,?,'ACTIVE',?,?,JSON_ARRAY(?),UTC_TIMESTAMP(6))",
                    tenant,
                    codes[i],
                    codes[i],
                    regions[i],
                    departments[i],
                    departments[i]);
            jdbc.update(
                    "INSERT INTO"
                        + " bi_source_hr_hr_employee(id,tenant_id,employee_code,employee_name,employment_status,department_id,department_path)"
                        + " VALUES(?,?,?,?,'ACTIVE',?,JSON_ARRAY(?))",
                    i + 1,
                    tenant,
                    codes[i],
                    codes[i],
                    departments[i],
                    departments[i]);
            jdbc.update(
                    "INSERT INTO"
                        + " bi_sales_contact_fact(tenant_id,submission_id,store_id,region_code,city_name,submitted_at,review_status,salesperson_id,owner_staff_code)"
                        + " VALUES(?,?,?,?,?,UTC_TIMESTAMP(6),'APPROVED',?,?)",
                    tenant,
                    "V" + i,
                    "S" + i,
                    regions[i],
                    regions[i],
                    "P" + i,
                    codes[i]);
        }
        jdbc.update("INSERT INTO bi_employee_snapshot VALUES(?,UTC_TIMESTAMP(6))", tenant);
        jdbc.update("INSERT INTO bi_sales_contact_snapshot VALUES(?,UTC_TIMESTAMP(6),1)", tenant);
        var mixed =
                policy(
                        List.of(
                                clause("DEPARTMENT", specified("10"), specified("HZ"), none()),
                                clause("DEPARTMENT", specified("20"), specified("NB"), none())),
                        all(),
                        all());
        when(iam.authorization(any(), eq("analytics:dashboard:read"))).thenReturn(mixed);
        try (var ctx = SupplyAuthorizationContext.open(iam, actor, mixed)) {
            var from = java.time.Instant.EPOCH;
            var to = java.time.Instant.now().plusSeconds(60);
            assertThat(employeeStore.read(tenant, from, to, null, null).rows())
                    .extracting(v -> v.employee().employeeCode())
                    .containsExactlyInAnyOrder("A", "D");
            assertThat(visitStore.read(tenant, from, to, null, null).summary().visits())
                    .isEqualTo(2);
        }
        var capped = policy(mixed.clauses(), specified("HZ"), all());
        when(iam.authorization(any(), eq("analytics:dashboard:read"))).thenReturn(capped);
        try (var ctx = SupplyAuthorizationContext.open(iam, actor, capped)) {
            assertThat(
                            employeeStore
                                    .read(
                                            tenant,
                                            java.time.Instant.EPOCH,
                                            java.time.Instant.now().plusSeconds(60),
                                            null,
                                            null)
                                    .rows())
                    .extracting(v -> v.employee().employeeCode())
                    .containsExactly("A");
            assertThat(
                            visitStore
                                    .read(
                                            tenant,
                                            java.time.Instant.EPOCH,
                                            java.time.Instant.now().plusSeconds(60),
                                            null,
                                            null)
                                    .summary()
                                    .visits())
                    .isEqualTo(1);
        }
    }

    @Test
    void customerAttributeTotalsUseFrozenOrderScopeAndCurrentCustomerScopeSeparately() {
        fact(810, "A", "HZ", 10, 11, 100);
        fact(811, "B", "HZ", 10, 11, 900);
        jdbc.update("UPDATE bi_sales_order_fact SET customer_id=1 WHERE tenant_id=?", tenant);
        jdbc.update(
                "INSERT INTO"
                    + " bi_customer_dim(tenant_id,customer_id,owner_staff_code,region_code,synced_time)"
                    + " VALUES(?,1,'A','HZ',UTC_TIMESTAMP(6))",
                tenant);
        jdbc.update(
                "INSERT INTO"
                    + " bi_customer_authority(tenant_id,customer_id,employee_code,region_code,region_path,source_revision)"
                    + " VALUES(?,1,'A','HZ',JSON_ARRAY('HZ'),1)",
                tenant);
        jdbc.update(
                "INSERT INTO bi_customer_attribute_current VALUES(?,1,'来源','行业',UTC_TIMESTAMP(6))",
                tenant);
        jdbc.update(
                "INSERT INTO bi_customer_attribute_snapshot VALUES(?,UTC_TIMESTAMP(6))", tenant);
        jdbc.update(
                "INSERT INTO bi_etl_checkpoint(tenant_id,source_code,source_name,last_success_time)"
                        + " VALUES(?,'ORDER_SALES_ORDER','订单',UTC_TIMESTAMP(6))",
                tenant);
        var self = policy(List.of(clause("SELF", none(), all(), none())), all(), all());
        when(iam.authorization(any(), eq("analytics:dashboard:read"))).thenReturn(self);
        try (var ctx = SupplyAuthorizationContext.open(iam, actor, self)) {
            assertThat(
                            attributeStore
                                    .read(
                                            tenant,
                                            java.time.Instant.EPOCH,
                                            java.time.Instant.now().plusSeconds(60),
                                            null,
                                            null)
                                    .sources())
                    .singleElement()
                    .satisfies(
                            i -> {
                                assertThat(i.customerCount()).isEqualTo(1);
                                assertThat(i.orderCount()).isEqualTo(1);
                                assertThat(i.salesAmount()).isEqualByComparingTo("100");
                            });
        }
    }
}
