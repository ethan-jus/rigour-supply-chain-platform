package com.rigour.analytics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rigour.analytics.api.controller.SalesOverviewController;
import com.rigour.analytics.api.controller.AnalyticsSupplyDashboardController;
import com.rigour.analytics.api.v1.AnalyticsSupplyDashboardApi.OverviewProjection;
import com.rigour.analytics.api.v1.AnalyticsSupplyDashboardApi.OverviewRankingKind;
import com.rigour.analytics.api.v1.model.SupplyDashboardMetricCardView;
import com.rigour.analytics.application.model.SalesOverview.DetailKind;
import com.rigour.analytics.application.port.out.HrTargetClient;
import com.rigour.analytics.application.service.SalesOverviewService;
import com.rigour.analytics.application.service.SalesOverviewService.Query;
import com.rigour.analytics.infrastructure.cache.JdbcCacheVersions;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    @Test
    void searchedSalesSummaryCannotExpandAuthorizedScope() {
        fact(1,"A","HZ",10,11,100);
        fact(2,"B","HZ",20,11,900);
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CONFIRMED',customer_id=order_id WHERE tenant_id=?",tenant);
        var self=policy(List.of(clause("SELF",none(),all(),none())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(self);
        var repository=new com.rigour.analytics.infrastructure.persistence.repository.JdbcSalesDashboardStore(
                jdbc.getDataSource(),mock(com.rigour.analytics.infrastructure.persistence.repository.HrDashboardTargets.class));
        var filter=new com.rigour.analytics.application.model.SupplyDashboardFilter(
                java.time.Instant.parse("2020-01-01T00:00:00Z"),java.time.Instant.parse("2030-01-01T00:00:00Z"),null,null,null,null,null);
        try(var context=SupplyAuthorizationContext.open(iam,actor,self)) {
            var selected=repository.summary(tenant,filter,List.of("A","B"));
            assertThat(selected.sales()).isEqualByComparingTo("100");
            assertThat(selected.customers()).isEqualTo(1);
            assertThat(repository.summary(tenant,filter,List.of("B")).sales()).isZero();
        }
    }

    @Test
    void inactiveSourceOrdersAndUnchangedPaymentsAreExcludedFromBiFacts() {
        var from = java.time.LocalDateTime.parse("2026-11-01T00:00:00");
        var to = java.time.LocalDateTime.parse("2026-11-05T00:00:00");
        for (int i=1; i<=3; i++) {
            String status = List.of("COMPLETED", "CANCELLED", "DELETED").get(i-1);
            jdbc.update("INSERT INTO bi_source_order_order_sales_order(tenant_id,id,order_no,order_date,order_status_code,payment_status_code,total_quantity,payable_amount,paid_amount,unpaid_amount,deleted,created_time,updated_time) VALUES(?,?,?,? ,?,'PARTIAL_PAID',1,100,40,60,0,?,?)",tenant,i,"BI-"+i,from,status,from,from.plusDays(1));
            jdbc.update("INSERT INTO bi_source_order_order_payment_record(tenant_id,id,payment_no,order_id,payment_time,paid_amount,deleted,created_time,updated_time) VALUES(?,?,?,?,?,40,0,?,?)",tenant,i,"P-"+i,i,from,from,from.plusDays(1));
        }
        productionMapper.upsertSalesOrderFactFromSource(tenant,from,to,to);
        productionMapper.upsertSalesPaymentFactFromSource(tenant,from,to,to);
        assertThat(jdbc.queryForObject("SELECT SUM(payable_amount) FROM bi_sales_order_fact WHERE tenant_id=? AND deleted=0",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT SUM(paid_amount) FROM bi_sales_payment_fact WHERE tenant_id=? AND deleted=0",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_sales_order_fact WHERE tenant_id=? AND deleted=1",Integer.class,tenant)).isEqualTo(2);
        // 只变更订单状态，原回款不动；增量也必须将该回款从 BI 排除。
        jdbc.update("UPDATE bi_source_order_order_sales_order SET order_status_code='CANCELLED',updated_time=? WHERE tenant_id=? AND id=1",from.plusDays(2),tenant);
        productionMapper.upsertSalesOrderFactFromSource(tenant,from.plusDays(1),to,to);
        productionMapper.upsertSalesPaymentFactFromSource(tenant,from.plusDays(1),to,to);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_sales_order_fact WHERE tenant_id=? AND deleted=0",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_sales_payment_fact WHERE tenant_id=? AND deleted=0",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_source_order_order_payment_record WHERE tenant_id=? AND deleted=0",Integer.class,tenant)).isEqualTo(3);
        // 模拟旧版本留存的事实且来源水位未变化，全量镜像后的退役检查同样排除它们。
        jdbc.update("UPDATE bi_sales_order_fact SET deleted=0 WHERE tenant_id=?",tenant);
        jdbc.update("UPDATE bi_sales_payment_fact SET deleted=0 WHERE tenant_id=?",tenant);
        dashboardProducts.retireMissingFacts(tenant);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_sales_payment_fact WHERE tenant_id=? AND deleted=0",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_sales_order_fact WHERE tenant_id=? AND deleted=0",Integer.class,tenant)).isZero();
    }

    @Autowired SalesOverviewService salesOverview;
    @Autowired AnalyticsSupplyDashboardController supplyDashboard;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean HrTargetClient salesTargets;

    @Test
    void operatingOverviewReconcilesReceiptsByDayAndCityAndKeepsTheOrderCohort() throws Exception {
        fact(1, "A", "HZ", 10, 11, 100);
        fact(2, "A", "HZ", 10, 11, 900);
        fact(3, "B", "NB", 20, 11, 200);
        fact(4, "B", "NB", 20, 11, 1000);
        jdbc.update("""
                UPDATE bi_sales_order_fact SET order_status_code=IF(order_id=4,'CANCELLED','COMPLETED'),
                    order_date=IF(order_id=2,'2026-09-05','2026-10-01'),customer_id=order_id,
                    paid_amount=IF(order_id=1,40,0),unpaid_amount=IF(order_id=1,60,payable_amount)
                WHERE tenant_id=?
                """, tenant);
        jdbc.update("""
                INSERT INTO bi_sales_payment_fact(tenant_id,payment_id,order_id,region_code,payment_time,paid_amount,synced_time,deleted)
                VALUES (?,1,2,'HZ','2026-09-30 16:00:00',50,UTC_TIMESTAMP(6),0),
                       (?,2,1,'HZ','2026-10-01 12:00:00',40,UTC_TIMESTAMP(6),0),
                       (?,3,3,'NB','2026-10-01 16:00:00',60,UTC_TIMESTAMP(6),0),
                       (?,4,4,'NB','2026-10-01 16:00:00',500,UTC_TIMESTAMP(6),1),
                       (?,5,2,'HZ','2026-09-30 15:59:59.999999',900,UTC_TIMESTAMP(6),0)
                """, tenant, tenant, tenant, tenant, tenant);
        var from = Instant.parse("2026-09-30T16:00:00Z");
        var to = Instant.parse("2026-10-31T15:59:59.999999Z");
        var unrestricted = policy(List.of(clause("ALL", all(), all(), all())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(unrestricted);
        try (var context = SupplyAuthorizationContext.open(iam, actor, unrestricted)) {
            var response = supplyDashboard.overview(from, to, null, null, null, null, null,
                    OverviewProjection.OPERATING).data();
            var metrics = response.metrics().stream().collect(java.util.stream.Collectors.toMap(
                    SupplyDashboardMetricCardView::metricCode, SupplyDashboardMetricCardView::value));
            assertThat(metrics.get("sales_amount")).isEqualByComparingTo("300");
            assertThat(metrics.get("unpaid_amount")).isEqualByComparingTo("260");
            assertThat(metrics.get("receipt_amount")).isEqualByComparingTo("150");
            assertThat(response.collectionTrend()).satisfiesExactly(
                    day -> {
                        assertThat(day.period()).isEqualTo("2026-10-01");
                        assertThat(day.value()).isEqualByComparingTo("90");
                    },
                    day -> assertThat(day.value()).isEqualByComparingTo("60"));
            assertThat(response.operatingDetails().cityReceipts()).satisfiesExactly(
                    city -> {
                        assertThat(city.regionCode()).isEqualTo("HZ");
                        assertThat(city.receiptAmount()).isEqualByComparingTo("90");
                    },
                    city -> assertThat(city.receiptAmount()).isEqualByComparingTo("60"));
            assertThat(response.operatingDetails().customerRetention()).isNotNull();
            assertThat(response.productSalesRanking()).isEmpty();
            assertThat(response.categorySalesRanking()).isEmpty();
            var repeated = supplyDashboard.overview(from, to, null, null, null, null, null,
                    OverviewProjection.OPERATING).data();
            assertThat(repeated.operatingDetails()).isEqualTo(response.operatingDetails());

            var mvc = MockMvcBuilders.standaloneSetup(supplyDashboard).build();
            mvc.perform(get("/api/v1/analytics/supply/dashboard/overview")
                            .param("from", from.toString()).param("to", to.toString())
                            .param("projection", "OPERATING_COMPARISON"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.operatingDetails.cityReceipts.length()").value(2))
                    .andExpect(jsonPath("$.data.operatingDetails.customerRetention").doesNotExist());

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE bi_sales_payment_fact SET paid_amount=65 WHERE tenant_id=? AND payment_id=3", tenant);
                new JdbcCacheVersions(jdbc).changed(tenant);
            });
            var updated = supplyDashboard.overview(from, to, null, null, null, null, null,
                    OverviewProjection.OPERATING).data();
            assertThat(updated.metrics().stream().filter(metric -> "receipt_amount".equals(metric.metricCode())))
                    .singleElement().satisfies(metric -> assertThat(metric.value()).isEqualByComparingTo("155"));
        }
        var region = policy(List.of(clause("REGION", none(), specified("HZ"), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(region);
        try (var context = SupplyAuthorizationContext.open(iam, actor, region)) {
            var restricted = supplyDashboard.overview(from, to, null, null, null, null, null,
                    OverviewProjection.OPERATING_COMPARISON).data();
            assertThat(restricted.operatingDetails().cityReceipts()).singleElement()
                    .satisfies(row -> assertThat(row.receiptAmount()).isEqualByComparingTo("90"));
        }
    }

    @Test
    void operatingRankingsUseTheSameOrderFactsAndAuthorization() throws Exception {
        fact(1, "A", "HZ", 10, 11, 100);
        fact(2, "B", "NB", 20, 11, 200);
        jdbc.update("UPDATE bi_sales_order_fact SET order_date='2026-10-01',order_status_code='COMPLETED' WHERE tenant_id=?", tenant);
        jdbc.update("""
                INSERT INTO bi_product_category_dim(tenant_id,category_id,category_name,synced_time)
                VALUES (?,1,'用品',UTC_TIMESTAMP(6))
                """, tenant);
        jdbc.update("""
                INSERT INTO bi_product_dim(tenant_id,product_id,product_name,product_category_id,synced_time)
                VALUES (?,1,'台呢',1,UTC_TIMESTAMP(6))
                """, tenant);
        jdbc.update("""
                INSERT INTO bi_sales_order_line_fact(tenant_id,order_id,order_line_id,order_date,order_status_code,
                    product_id,product_category_id,line_amount,synced_time)
                SELECT tenant_id,order_id,order_id,order_date,order_status_code,1,1,payable_amount,UTC_TIMESTAMP(6)
                FROM bi_sales_order_fact WHERE tenant_id=?
                """, tenant);
        var from = Instant.parse("2026-09-30T16:00:00Z");
        var to = Instant.parse("2026-10-31T15:59:59.999999Z");
        var unrestricted = policy(List.of(clause("ALL", all(), all(), all())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(unrestricted);
        try (var context = SupplyAuthorizationContext.open(iam, actor, unrestricted)) {
            var mvc = MockMvcBuilders.standaloneSetup(supplyDashboard).build();
            mvc.perform(get("/api/v1/analytics/supply/dashboard/overview/ranking")
                            .param("kind", "PRODUCT").param("from", from.toString()).param("to", to.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].dimensionName").value("台呢"))
                    .andExpect(jsonPath("$.data[0].salesAmount").value(300));
            var category = supplyDashboard.operatingOverviewRanking(OverviewRankingKind.CATEGORY,
                    from, to, null, null, null, null, null).data();
            assertThat(category).singleElement().satisfies(row -> {
                assertThat(row.dimensionName()).isEqualTo("用品");
                assertThat(row.salesAmount()).isEqualByComparingTo("300");
            });
            assertThat(supplyDashboard.operatingOverviewRanking(OverviewRankingKind.CATEGORY,
                    from, to, null, null, null, null, null).data()).isEqualTo(category);
        }
        var region = policy(List.of(clause("REGION", none(), specified("HZ"), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(region);
        try (var context = SupplyAuthorizationContext.open(iam, actor, region)) {
            for (var kind : OverviewRankingKind.values())
                assertThat(supplyDashboard.operatingOverviewRanking(kind, from, to,
                        null, null, null, null, null).data()).singleElement()
                        .satisfies(row -> assertThat(row.salesAmount()).isEqualByComparingTo("100"));
        }
    }

    @Test
    void overviewReusesPostedAllocationsAndBalancesAcrossDepartmentAndYearFilters() throws Exception {
        fact(11, "A", "HZ", 10, 11, 6000);
        fact(12, "B", "NB", 20, 11, 1200);
        fact(13, "C", "HZ", 10, 11, 900);
        jdbc.update("""
            UPDATE bi_sales_order_fact SET order_status_code=IF(order_id=13,'DRAFT','CONFIRMED'),
                order_date='2026-09-05',order_no=CONCAT('S',order_id),
                customer_id=order_id,owner_staff_name=CONCAT('员工 ',owner_staff_code)
            WHERE tenant_id=?
            """,tenant);
        jdbc.update("UPDATE bi_order_authority SET department_path=JSON_ARRAY(1,department_id) WHERE tenant_id=?",tenant);
        jdbc.update("""
            INSERT INTO bi_source_hr_hr_department(tenant_id,id,department_code,department_name,parent_id,status_code,department_path)
            VALUES (?,1,'SALES','销售部',NULL,'ACTIVE','[1]'),(?,10,'HZ','杭州组',1,'ACTIVE','[1,10]'),
                   (?,20,'NB','宁波组',1,'ACTIVE','[1,20]')
            """,tenant,tenant,tenant);
        jdbc.update("""
            INSERT INTO bi_sales_contact_city_dim(tenant_id,region_code,city_name,department_id,department_path)
            VALUES (?,'HZ','杭州',10,JSON_ARRAY(1,10)),(?,'NB','宁波',20,JSON_ARRAY(1,20))
            """,tenant,tenant);
        jdbc.update("""
            INSERT INTO bi_employee_dim(tenant_id,employee_code,employee_name,employment_status,city_name,region_code,department_id,department_path,synced_time)
            VALUES (?,'A','员工 A','ACTIVE','杭州','HZ',10,JSON_ARRAY(1,10),UTC_TIMESTAMP(6)),
                   (?,'Z','员工 Z','ACTIVE','杭州','HZ',10,JSON_ARRAY(1,10),UTC_TIMESTAMP(6)),
                   (?,'B','员工 B','ACTIVE','宁波','NB',20,JSON_ARRAY(1,20),UTC_TIMESTAMP(6))
            """,tenant,tenant,tenant);
        jdbc.update("""
            INSERT INTO bi_sales_order_line_fact(tenant_id,order_line_id,order_id,order_date,product_name,line_amount,synced_time)
            VALUES (?,11,11,'2026-09-05','球台',6000,UTC_TIMESTAMP(6)),
                   (?,12,12,'2026-09-05','配件',1200,UTC_TIMESTAMP(6))
            """,tenant,tenant);
        jdbc.update("""
            INSERT INTO bi_receivable_installment(id,tenant_id,order_id,plan_version,installment_no,effective_at,due_date,amount)
            VALUES(11,?,11,1,1,'2026-09-05','2026-09-25',6000)
            """,tenant);
        for (String dataset : List.of("ORDER_CASH_RECEIPT", "ORDER_CASH_APPLICATION",
                "ORDER_RECEIVABLE_PLAN", "ORDER_RECEIVABLE_ADJUSTMENT", "ORDER_RECEIVABLE_OPENING")) {
            jdbc.update("""
                INSERT INTO bi_source_snapshot_checkpoint(tenant_id,dataset,source_version,projected_at)
                VALUES(?,?,'fixture',UTC_TIMESTAMP(6))
                """,tenant,dataset);
        }
        jdbc.update("""
            INSERT INTO bi_cash_receipt(tenant_id,id,reference_no,customer_id,cash_at,amount,kind,verified,owner_code)
            VALUES(?,UNHEX(MD5('PRE')),'PRE',11,'2026-08-22',2000,'RECEIPT',1,'B'),
                  (?,UNHEX(MD5('FINAL')),'FINAL',11,'2026-10-02',4000,'RECEIPT',1,'B'),
                  (?,UNHEX(MD5('PENDING')),'PENDING',11,'2026-09-21',99,'RECEIPT',0,'A')
            """,tenant,tenant,tenant);
        jdbc.update("""
            INSERT INTO bi_cash_application(tenant_id,id,cash_key,order_id,effective_at,amount,verified)
            VALUES(?,11,MD5('PRE'),11,'2026-09-15',2000,1),(?,12,MD5('FINAL'),11,'2026-10-02',4000,1),
                  (?,13,MD5('PENDING'),11,'2026-09-22',99,1)
            """,tenant,tenant,tenant);
        jdbc.update("""
            INSERT INTO bi_source_order_order_payment_record
              (tenant_id,id,order_id,payment_owner_code,payment_owner_department_id,deleted)
            VALUES (?,11,11,'A',10,0),(?,12,11,'B',20,0),(?,13,11,'B',20,1)
            """,tenant,tenant,tenant);
        jdbc.update("""
            INSERT INTO bi_sales_payment_fact
              (tenant_id,payment_id,order_id,owner_staff_code,region_code,payment_time,paid_amount,synced_time,deleted)
            VALUES (?,11,11,'A','HZ','2026-08-22',2000,UTC_TIMESTAMP(6),0),
                   (?,12,11,'B','NB','2026-10-02',4000,UTC_TIMESTAMP(6),0),
                   (?,13,11,'B','NB','2026-10-03',99,UTC_TIMESTAMP(6),1)
            """,tenant,tenant,tenant);
        when(salesTargets.values(eq(tenant),anyString(),anyString())).thenAnswer(call -> {
            var from = java.time.YearMonth.parse(call.getArgument(1));
            var to = java.time.YearMonth.parse(call.getArgument(2));
            var values = new ArrayList<Target>();
            for (var m=from; !m.isAfter(to); m=m.plusMonths(1)) {
                values.add(new Target(m.toString(),"SALES_OWNER","A","员工 A","SALES_AMOUNT",
                        new BigDecimal("8000"),1));
                values.add(new Target(m.toString(),"SALES_OWNER","A","员工 A","RECEIPT_AMOUNT",
                        new BigDecimal("6000"),1));
                values.add(new Target(m.toString(),"SALES_OWNER","Z","员工 Z","RECEIPT_AMOUNT",
                        BigDecimal.ZERO,1));
            }
            return values;
        });
        var full = policy(List.of(clause("ALL",all(),all(),all())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(full);
        var sep = LocalDate.of(2026,9,1);
        var oct = sep.plusMonths(1);
        var hz = new Query(sep,oct.minusDays(1),null,10L,false);
        try (var context = SupplyAuthorizationContext.open(iam,actor,full)) {
            assertThat(salesOverview.overview(hz).received()).isNull();
            jdbc.update("""
                INSERT INTO bi_etl_checkpoint(tenant_id,source_code,last_success_time)
                VALUES (?,'ORDER_PAYMENT_RECORD',UTC_TIMESTAMP(6))
                """,tenant);
            var result = salesOverview.overview(hz);
            assertThat(result.booked()).isEqualByComparingTo("6000");
            assertThat(result.received()).isZero();
            assertThat(result.applied()).isEqualByComparingTo("2000");
            assertThat(result.outstanding()).isEqualByComparingTo("4000");
            assertThat(result.overdue()).isEqualByComparingTo("4000");
            assertThat(result.overdueOrders()).isEqualTo(1);
            assertThat(result.employees()).extracting(o -> o.code()).containsExactly("A","Z");
            assertThat(result.people().getFirst().target()).isEqualByComparingTo("8000");
            assertThat(result.people().getFirst().receiptTarget()).isEqualByComparingTo("6000");
            assertThat(result.people().getLast().receiptTarget()).isZero();
            assertThat(result.people().getFirst().outstanding()).isEqualByComparingTo("4000");
            assertThat(result.departments()).extracting(d -> d.id()).containsExactlyInAnyOrder(1L,10L,20L);
            assertThat(salesOverview.overview(new Query(sep,oct.minusDays(1),null,1L,false)).booked()).isZero();
            assertThat(salesOverview.overview(new Query(sep,oct.minusDays(1),null,1L,true)).booked()).isEqualByComparingTo("7200");
            var year = salesOverview.overview(new Query(sep.withDayOfYear(1),oct.plusMonths(1).minusDays(1),"A",10L,false));
            assertThat(year.received()).isEqualByComparingTo("2000");
            assertThat(year.applied()).isEqualByComparingTo("6000");
            assertThat(year.outstanding()).isZero();
            assertThat(year.people().getFirst().target()).isEqualByComparingTo("80000");
            assertThat(year.people().getFirst().receiptTarget()).isEqualByComparingTo("60000");
            var octoberNingbo = salesOverview.overview(new Query(oct,oct.plusMonths(1).minusDays(1),"B",20L,false));
            assertThat(octoberNingbo.received()).isEqualByComparingTo("4000");
            assertThat(octoberNingbo.applied()).isZero();
            assertThat(octoberNingbo.people().getFirst().missingReceiptTargetMonths()).isEqualTo(1);
            var allocations = salesOverview.details(hz,DetailKind.APPLICATIONS,1,20);
            assertThat(allocations.items()).extracting(d -> d.name()).containsExactly("PRE");
            assertThat(allocations.items().getFirst().amount()).isEqualByComparingTo("2000");
            assertThat(salesOverview.details(hz,DetailKind.PRODUCTS,1,20).items())
                    .extracting(d -> d.name()).containsExactly("球台");
            assertThat(salesOverview.details(new Query(oct,oct.plusMonths(1).minusDays(1),"A",10L,false),DetailKind.ORDERS,1,20)
                    .items().getFirst().applied()).isEqualByComparingTo("4000");
            assertThat(receivables.loadFiltered(sep,oct.minusDays(1),null,null,null,"OVERDUE",1,20,null,null,10L,false)
                    .balances()).extracting(b -> b.orderNo()).containsExactly("S11");
            var mvc = MockMvcBuilders.standaloneSetup(new SalesOverviewController(salesOverview)).build();
            mvc.perform(get("/api/v1/analytics/supply/dashboard/sales-overview")
                    .param("from","2026-09-01").param("asOf","2026-09-30")
                    .param("departmentId","10").param("ownerStaffCode","A"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.booked").value(6000))
                    .andExpect(jsonPath("$.data.people[0].code").value("A"))
                    .andExpect(jsonPath("$.data.people[0].receiptTarget").value(6000));
        }
        var self=policy(List.of(clause("SELF",none(),all(),none())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(self);
        try(var context=SupplyAuthorizationContext.open(iam,actor,self)) {
            var outside = new Query(sep,oct.minusDays(1),"B",20L,true);
            var denied = salesOverview.overview(outside);
            assertThat(denied.people()).isEmpty();
            assertThat(denied.booked()).isZero();
            assertThat(denied.received()).isZero();
            assertThat(denied.outstanding()).isZero();
            assertThat(denied.canSeeCompanyCash()).isFalse();
            assertThat(salesOverview.details(outside,DetailKind.PRODUCTS,1,20).total()).isZero();
        }
    }

    @Autowired com.rigour.analytics.application.service.ReceivablesService receivables;

    @Autowired com.rigour.analytics.application.service.ReceivablesArchive archive;

    @Test
    void cashRiskAndAllocationsReconcileAcrossMonthsAndScopes() throws java.io.IOException {
        String archivePermission="analytics:receivables:archive";
        grantAdditionalAction(archivePermission);
        fact(1,"A","HZ",10,11,6000);
        fact(2,"B","HZ",20,11,1200);
        fact(3,"B","HZ",20,11,900);
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CONFIRMED',order_date='2026-09-05',customer_id=order_id,customer_name=CONCAT('样例门店 ',order_id),region_name='杭州',owner_staff_name=CONCAT('员工 ',owner_staff_code),order_no=CONCAT('S',order_id) WHERE tenant_id=?",tenant);
        jdbc.update("INSERT INTO bi_receivable_installment(id,tenant_id,order_id,plan_version,installment_no,effective_at,due_date,amount) VALUES(1,?,1,1,1,'2026-09-05','2026-09-25',6000),(2,?,2,1,1,'2026-09-05','2026-10-26',1200)",tenant,tenant);
        for (String dataset:List.of("ORDER_CASH_RECEIPT","ORDER_CASH_APPLICATION","ORDER_RECEIVABLE_PLAN","ORDER_RECEIVABLE_ADJUSTMENT","ORDER_RECEIVABLE_OPENING"))
            jdbc.update("INSERT INTO bi_source_snapshot_checkpoint(tenant_id,dataset,source_version,projected_at) VALUES(?,?,'fixture',UTC_TIMESTAMP(6))",tenant,dataset);
        for (String[] r:List.of(new String[]{"R1","1","09-15","2000"},new String[]{"R2","2","09-22","500"},
                new String[]{"R3","1","10-12","4000"},new String[]{"R4","2","10-18","1000"}))
            jdbc.update("INSERT INTO bi_cash_receipt(tenant_id,id,reference_no,customer_id,cash_at,amount,kind,verified) VALUES(?,UNHEX(MD5(?)),?,?,?,?,'RECEIPT',1)",tenant,r[0],r[0],r[1],"2026-"+r[2],r[3]);
        long id=0;
        for (String[] a:List.of(new String[]{"R1","1","09-15","2000"}, new String[]{"R2","2","10-02","500"},
                new String[]{"R3","1","10-12","4000"},new String[]{"R4","2","10-18","700"},new String[]{"R4","3","10-18","300"}))
            jdbc.update("INSERT INTO bi_cash_application(tenant_id,id,cash_key,order_id,effective_at,amount,verified) VALUES(?,?,MD5(?),?,?,?,1)",tenant,++id,a[0],a[1],"2026-"+a[2],a[3]);
        jdbc.update("UPDATE bi_cash_receipt SET channel='银行转账',payee='样例公司',owner_code=IF(customer_id=1,'A','B'),verified_at=DATE_ADD(cash_at,INTERVAL 1 DAY) WHERE tenant_id=?",tenant);
        String action=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO bi_operating_action(id,tenant_id,kind,business_ref,business_label,assignee,due_at,status,note,created_by,created_at,updated_at)
            VALUES(?,?,'COLLECTION','order-id:1','S1','A','2026-10-03','OPEN','核实尾款','manager','2026-09-26','2026-09-26')
            """,action,tenant);
        jdbc.update("""
            INSERT INTO bi_operating_action_event(id,tenant_id,action_id,revision,status,assignee,due_at,note,actor,occurred_at,promised_on,disputed)
            VALUES(?,?,?,1,'OPEN','A','2026-10-03','核实尾款','manager','2026-09-26','2026-10-03',1)
            """,UUID.randomUUID().toString(),tenant,action);
        var full=policy(List.of(clause("ALL",all(),all(),all())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(full);
        var writePolicy=new SupplyAuthorizationView(full.mode(),full.tenantId(),full.userId(),full.employeeCode(),
                full.applicationVersion(),full.memberVersion(),full.employeeRevision(),full.organizationVersion(),
                full.permissions(),archivePermission,true,full.clauses(),full.regionLimit(),full.warehouseLimit());
        when(iam.authorization(eq(actor),eq(archivePermission))).thenReturn(writePolicy);
        var sep=java.time.LocalDate.of(2026,9,1);
        var oct=sep.plusMonths(1);
        try (var context=SupplyAuthorizationContext.open(iam,actor,full)) {
            var sept=receivables.load(sep,oct.minusDays(1),null,null,null,null,1,20).totals();
            var revision=archive.save(sep,oct.minusDays(1));
            assertThat(archive.save(sep,oct.minusDays(1))).isEqualTo(revision);
            assertThat(archive.revisions(sep,oct.minusDays(1))).hasSize(1);
            jdbc.update("UPDATE bi_cash_receipt SET reference_no='R1-reconciled' WHERE tenant_id=? AND reference_no='R1'",tenant);
            var frozen=archive.read(sep,oct.minusDays(1),revision,null,1,20,null,null);
            assertThat(frozen.receipts()).anyMatch(r->"R1".equals(r.reference()));
            assertThat(receivables.load(sep,oct.minusDays(1),null,null,null,null,1,20).version()).isNotEqualTo(revision);
            assertThat(sept.receipts()).isEqualByComparingTo("2500");
            var queue=receivables.loadFiltered(sep,oct.minusDays(1),null,null,null,null,1,20,"A",true);
            assertThat(queue.balanceCount()).isEqualTo(1);
            assertThat(queue.balances().getFirst().promisedOn()).isEqualTo(oct.plusDays(2));
            assertThat(queue.cashGroups()).anyMatch(g->g.dimension().equals("CHANNEL") && g.label().equals("银行转账") && g.net().intValueExact()==2500);
            assertThat(sept.unapplied()).isEqualByComparingTo("500");
            assertThat(sept.overdue()).isEqualByComparingTo("4000");
            assertThat(sept.missingDue()).isEqualByComparingTo("900");
            var october=receivables.load(oct,oct.plusMonths(1).minusDays(1),null,null,null,null,1,20);
            assertThat(october.totals().receipts()).isEqualByComparingTo("5000");
            assertThat(october.totals().cumulativeReceipts()).isEqualByComparingTo("7500");
            assertThat(october.totals().applied()).isEqualByComparingTo("5500");
            assertThat(october.totals().outstanding()).isEqualByComparingTo("600");
            assertThat(october.totals().overdue()).isZero();
            var repository = new com.rigour.analytics.infrastructure.persistence.repository.JdbcReceivablesStore(jdbc.getDataSource());
            var rows = repository.overview(new com.rigour.analytics.application.port.out.ReceivablesStore.Scope(
                    tenant, null, null, null, true), oct, oct.plusMonths(1).minusDays(1));
            assertThat(rows.getFirst().outstanding()).isEqualByComparingTo(october.totals().outstanding());
            assertThat(rows.getFirst().overdue()).isEqualByComparingTo(october.totals().overdue());
            assertThat(rows.subList(1, rows.size()).stream()
                    .map(com.rigour.analytics.application.port.out.ReceivablesStore.OverviewRow::received)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                    .isEqualByComparingTo(october.totals().receipts());
            assertThat(october.totals().opening().add(october.totals().newReceivables()).add(october.totals().adjustments()).subtract(october.totals().applied())).isEqualByComparingTo(october.totals().outstanding());
        }
        var self=policy(List.of(clause("SELF",none(),all(),none())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(self);
        try (var context=SupplyAuthorizationContext.open(iam,actor,self)) {
            var restricted=receivables.load(sep,oct.minusDays(1),null,null,null,null,1,20);
            assertThatThrownBy(()->archive.revisions(sep,oct.minusDays(1))).isInstanceOf(AuthorizationDeniedException.class);
            assertThat(restricted.completeCashScope()).isFalse();
            assertThat(restricted.totals().unapplied()).isNull();
            assertThat(restricted.totals().receipts()).isEqualByComparingTo("2000");
            assertThat(restricted.totals().outstanding()).isEqualByComparingTo("4000");
            var repository = new com.rigour.analytics.infrastructure.persistence.repository.JdbcReceivablesStore(jdbc.getDataSource());
            var rows = repository.overview(new com.rigour.analytics.application.port.out.ReceivablesStore.Scope(
                    tenant, null, null, null, false), sep, oct.minusDays(1));
            assertThat(rows.getFirst().outstanding()).isEqualByComparingTo("4000");
            assertThat(rows.getLast().received()).isEqualByComparingTo("2000");
        }
    }

    @Test void installmentsRefundsAndApprovedCreditsKeepCashAndDebtSeparate() {
        var sep=java.time.LocalDate.of(2026,9,1);
        var oct=sep.plusMonths(1);
        fact(21,"A","HZ",1,1,6000);
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CONFIRMED',order_date='2026-09-01',customer_id=1 WHERE tenant_id=?",tenant);
        jdbc.update("""
            INSERT INTO bi_receivable_installment(id,tenant_id,order_id,plan_version,installment_no,effective_at,due_date,amount)
            VALUES(1,?,21,1,1,'2026-09-01','2026-09-14',3000),(2,?,21,1,2,'2026-09-01','2026-11-01',3000)
            """,tenant,tenant);
        for(String key:List.of("ORDER_CASH_RECEIPT","ORDER_CASH_APPLICATION","ORDER_RECEIVABLE_PLAN","ORDER_RECEIVABLE_ADJUSTMENT","ORDER_RECEIVABLE_OPENING"))
            jdbc.update("INSERT INTO bi_source_snapshot_checkpoint(tenant_id,dataset,source_version,projected_at) VALUES(?,?,'verified',UTC_TIMESTAMP())",tenant,key);
        jdbc.update("""
            INSERT INTO bi_cash_receipt(tenant_id,id,reference_no,customer_id,cash_at,amount,kind,verified) VALUES
            (?,UNHEX(MD5('C1')),'C1',1,'2026-09-03',2000,'RECEIPT',1),(?,UNHEX(MD5('F1')),'F1',1,'2026-10-06',500,'REFUND',1)
            """,tenant,tenant);
        jdbc.update("INSERT INTO bi_cash_application(tenant_id,id,cash_key,order_id,effective_at,amount,verified) VALUES(?,11,MD5('C1'),21,'2026-09-03',2000,1)",tenant);
        jdbc.update("""
            INSERT INTO bi_receivable_adjustment(tenant_id,id,order_id,event_type,receivable_delta,received_delta,effective_at,deleted)
            VALUES(?,1,21,'CREDIT_NOTE',-4000,0,'2026-10-03',0),(?,2,21,'PAYMENT_REVERSAL',0,-500,'2026-10-06',0)
            """,tenant,tenant);
        var full=policy(List.of(clause("ALL",all(),all(),all())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(full);
        try(var context=SupplyAuthorizationContext.open(iam,actor,full)) {
            var september=receivables.load(sep,oct.minusDays(1),null,null,null,null,1,25);
            assertThat(september.totals().overdue()).isEqualByComparingTo("1000");
            assertThat(september.balances()).extracting(r->r.remaining().intValueExact()).containsExactly(1000,3000);
            var october=receivables.load(oct,oct.plusMonths(1).minusDays(1),null,null,null,null,1,25).totals();
            assertThat(october.receipts()).isZero();assertThat(october.refunds()).isEqualByComparingTo("500");
            assertThat(october.net()).isEqualByComparingTo("-500");assertThat(october.outstanding()).isEqualByComparingTo("500");
            assertThat(october.adjustments()).isEqualByComparingTo("-3500");
        }
    }

    @Autowired com.rigour.analytics.application.service.AssignedFollowups assigned;

    @Test void assignedFollowupsShareTheManagersRecordAndProtectOtherEmployees() {
        String read="sales:followup:self:read", respond="sales:followup:self:respond";
        grantAdditionalAction(read);grantAdditionalAction(respond);
        var base=policy(List.of(clause("SELF",none(),all(),none())),all(),all());
        for(String action:List.of(read,respond)){
            var p=new SupplyAuthorizationView(base.mode(),base.tenantId(),base.userId(),"A",1,1,1,1,base.permissions(),action,true,base.clauses(),base.regionLimit(),base.warehouseLimit());
            when(iam.authorization(actor,action)).thenReturn(p);
        }
        String mine=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        for(var id:List.of(mine,other))jdbc.update("""
            INSERT INTO bi_operating_action(id,tenant_id,kind,business_ref,business_label,assignee,due_at,status,note,revision,created_by,created_at,updated_at)
            VALUES(?,?,'CUSTOMER','customer-id:1','样例门店',?,UTC_TIMESTAMP(),'OPEN','核对补货计划',1,'manager',UTC_TIMESTAMP(),UTC_TIMESTAMP())
            """,id,tenant,id.equals(mine)?"A":"B");
        var page=assigned.list(false,1,25);
        assertThat(page.items()).extracting(task->task.id()).containsExactly(mine);
        var started=new com.rigour.analytics.application.service.AssignedFollowups.Reply("IN_PROGRESS","已与门店联系",1);
        assertThatThrownBy(()->assigned.respond(other,started)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assigned.respond(mine,started);
        assertThatThrownBy(()->assigned.respond(mine,started)).isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assigned.respond(mine,new com.rigour.analytics.application.service.AssignedFollowups.Reply("RESOLVED","门店已确认",2));
        assertThat(assigned.list(false,1,25).total()).isZero();
        assertThat(assigned.list(true,1,25).items().getFirst().revision()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_operating_action_event WHERE tenant_id=? AND action_id=?",Integer.class,tenant,mine)).isEqualTo(2);
    }

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

    @Test
    void provinceIncludesCitiesWhileCityAndAuthorizationRemainNarrow() {
        department(1,null,"SALES","销售部"); department(27,1L,"HB","河北省");
        department(11,27L,"SJZ","石家庄"); department(28,27L,"TS","唐山市");
        jdbc.update("INSERT INTO bi_sales_contact_city_dim(tenant_id,region_code,city_name,department_id,department_path) VALUES(?,'HB','河北省',27,'[1,27]'),(?,'SJZ','石家庄',11,'[1,27,11]'),(?,'TS','唐山市',28,'[1,27,28]')",tenant,tenant,tenant);
        fact(1,"A","SJZ",11,1,100);fact(2,"A","TS",28,1,80);fact(3,"B","OTHER",30,1,999);
        jdbc.update("UPDATE bi_order_authority SET department_path=CASE department_id WHEN 11 THEN '[1,27,11]' WHEN 28 THEN '[1,27,28]' ELSE '[1,30]' END WHERE tenant_id=?",tenant);
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CONFIRMED' WHERE tenant_id=?",tenant);
        var from = java.time.LocalDateTime.of(2020,1,1,0,0);
        var to = java.time.LocalDateTime.of(2030,1,1,0,0);
        var unrestricted = policy(List.of(clause("ALL",all(),all(),all())),all(),all());
        when(iam.authorization(any(), any())).thenReturn(unrestricted);
        try (var context = SupplyAuthorizationContext.open(iam, actor, unrestricted); var session = sessions.openSession()) {
            var mapper = session.getMapper(com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper.class);
            assertThat(new java.math.BigDecimal(mapper.salesSummary(tenant,from,to,"HB",null,null,null).get("salesAmount").toString())).isEqualByComparingTo("180");
            var national = mapper.citySalesRanking(tenant,from,to,null,null,null,null);
            assertThat(national).extracting(r -> r.get("dimensionCode")).containsExactly("OTHER", "HB");
            assertThat(new java.math.BigDecimal(national.get(1).get("salesAmount").toString())).isEqualByComparingTo("180");
            assertThat(mapper.citySalesRanking(tenant,from,to,"HB",null,null,null))
                    .extracting(r -> r.get("dimensionCode")).containsExactly("SJZ", "TS");
            assertThat(mapper.cityCollectionRateRanking(tenant,from,to,null,null,null,null))
                    .extracting(r -> r.get("dimensionCode")).containsExactlyInAnyOrder("HB", "OTHER");
            jdbc.update("INSERT INTO bi_city_cost_record(tenant_id,region_code,cost_type_code,cost_date,cost_amount,source_system_code,source_record_id) VALUES(?,'SJZ','COST',UTC_TIMESTAMP(6),10,'MANUAL_IMPORT','sjz'),(?,'TS','COST',UTC_TIMESTAMP(6),20,'MANUAL_IMPORT','ts')",tenant,tenant);
            assertThat(mapper.cityCostRanking(tenant,from,to,null,null,null,null)).singleElement().satisfies(r -> {
                assertThat(r.get("regionCode")).isEqualTo("HB");
                assertThat(new java.math.BigDecimal(r.get("costAmount").toString())).isEqualByComparingTo("30");
                assertThat(new java.math.BigDecimal(r.get("salesAmount").toString())).isEqualByComparingTo("180");
            });
            var risk = new com.rigour.analytics.infrastructure.persistence.repository.JdbcPaymentRiskStore(jdbc.getDataSource());
            assertThat(risk.regions(tenant)).filteredOn(r -> "TS".equals(r.code()))
                    .singleElement().satisfies(r -> assertThat(r.parentCode()).isEqualTo("HB"));
            assertThat(risk.load(tenant,from.toInstant(java.time.ZoneOffset.UTC),to.toInstant(java.time.ZoneOffset.UTC),"HB",null,100))
                    .extracting(r -> r.regionCode()).containsExactlyInAnyOrder("SJZ","TS");
            var client = mock(com.rigour.analytics.application.port.out.HrTargetClient.class);
            var goals = List.of("HB","SJZ","TS").stream().map(code ->
                new com.rigour.hr.api.v1.model.TargetSettingsModels.Target("2026-10","CITY",code,code,
                    "SALES_AMOUNT",new java.math.BigDecimal("1000"),1)).toList();
            when(client.values(any(),any(),any())).thenReturn(goals);
            var targets = new com.rigour.analytics.infrastructure.persistence.repository.HrDashboardTargets(client,jdbc.getDataSource(),mapper);
            assertThat(targets.cityGoals(tenant,2026,null)).extracting(r -> r.regionCode()).containsExactly("HB");
            var filter = new com.rigour.analytics.application.model.SupplyDashboardFilter(from.toInstant(java.time.ZoneOffset.UTC),to.toInstant(java.time.ZoneOffset.UTC),null,null,null,null,null);
            assertThat(targets.completions(tenant,filter,"CITY",goals)).singleElement().satisfies(r -> {
                assertThat(r.dimensionCode()).isEqualTo("HB");
                assertThat(r.actualValue()).isEqualByComparingTo("180");
            });
            assertThat(targets.cityGoals(tenant,2026,"TS")).extracting(r -> r.regionCode()).containsExactly("TS");
            // Two sibling cities share a customer: the parent count is distinct, not child-count addition.
            jdbc.update("UPDATE bi_sales_order_fact SET customer_id=77 WHERE tenant_id=? AND order_id IN (1,2)", tenant);
            jdbc.update("""
                    INSERT INTO bi_sales_order_line_fact
                        (tenant_id,order_id,order_line_id,region_code,owner_staff_code,brand_name,
                         line_amount,order_date,order_status_code,deleted,synced_time)
                    SELECT tenant_id,order_id,order_id,region_code,owner_staff_code,
                           '杨掌柜 X CBSA世界斯诺克学院',payable_amount,order_date,'CONFIRMED',0,UTC_TIMESTAMP(6)
                      FROM bi_sales_order_fact WHERE tenant_id=?
                    """, tenant);
            jdbc.update("""
                    INSERT INTO bi_sales_payment_fact
                        (tenant_id,payment_id,order_id,region_code,owner_staff_code,paid_amount,payment_time,synced_time)
                    SELECT tenant_id,order_id,order_id,region_code,owner_staff_code,payable_amount/2,order_date,UTC_TIMESTAMP(6)
                      FROM bi_sales_order_fact WHERE tenant_id=?
                    """, tenant);
            var allMetrics = new ArrayList<com.rigour.hr.api.v1.model.TargetSettingsModels.Target>();
            for (String code : List.of("HB", "SJZ", "TS"))
                for (String metric : List.of("SALES_AMOUNT", "RECEIPT_AMOUNT", "ORDERED_STORE_COUNT", "YANGZHANGGUI_SALES_AMOUNT"))
                    allMetrics.add(new com.rigour.hr.api.v1.model.TargetSettingsModels.Target("2026-10", "CITY", code, code,
                            metric, new java.math.BigDecimal("1000"), 1));
            // JDBC fixture writes happened after an earlier mapper read in the same session.
            session.clearCache();
            var completion = targets.completions(tenant, filter, "CITY", allMetrics);
            assertThat(completion).hasSize(4).allSatisfy(r -> assertThat(r.dimensionCode()).isEqualTo("HB"));
            for (var r : completion) assertThat(r.actualValue()).isEqualByComparingTo(switch (r.metricCode()) {
                case "SALES_AMOUNT", "YANGZHANGGUI_SALES_AMOUNT" -> "180";
                case "RECEIPT_AMOUNT" -> "90";
                case "ORDERED_STORE_COUNT" -> "1";
                default -> throw new AssertionError(r.metricCode());
            });
            var cityFilter = new com.rigour.analytics.application.model.SupplyDashboardFilter(
                    filter.from(), filter.to(), "TS", null, null, null, null);
            assertThat(targets.completions(tenant, cityFilter, "CITY", allMetrics)).hasSize(4)
                    .allSatisfy(r -> {
                        assertThat(r.dimensionCode()).isEqualTo("TS");
                        assertThat(r.actualValue()).isEqualByComparingTo(switch (r.metricCode()) {
                            case "SALES_AMOUNT", "YANGZHANGGUI_SALES_AMOUNT" -> "80";
                            case "RECEIPT_AMOUNT" -> "40";
                            case "ORDERED_STORE_COUNT" -> "1";
                            default -> throw new AssertionError(r.metricCode());
                        });
                    });



            assertThat(new java.math.BigDecimal(mapper.salesSummary(tenant,from,to,"TS",null,null,null).get("salesAmount").toString())).isEqualByComparingTo("80");
        }
        var province = policy(List.of(clause("DEPARTMENT",specified("27"),all(),all())),all(),all());
        when(iam.authorization(any(), any())).thenReturn(province);
        try (var context = SupplyAuthorizationContext.open(iam, actor, province); var session = sessions.openSession()) {
            var mapper = session.getMapper(com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper.class);
            assertThat(new java.math.BigDecimal(mapper.salesSummary(tenant,from,to,"HB",null,null,null).get("salesAmount").toString())).isEqualByComparingTo("180");
        }
        var restricted = policy(List.of(clause("DEPARTMENT",specified("28"),all(),all())),all(),all());
        when(iam.authorization(any(), any())).thenReturn(restricted);
        try (var context = SupplyAuthorizationContext.open(iam, actor, restricted); var session = sessions.openSession()) {
            var mapper = session.getMapper(com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper.class);
            assertThat(new java.math.BigDecimal(mapper.salesSummary(tenant,from,to,"TS",null,null,null).get("salesAmount").toString())).isEqualByComparingTo("80");
            assertThat(new java.math.BigDecimal(mapper.salesSummary(tenant,from,to,"HB",null,null,null).get("salesAmount").toString())).isZero();
        }
    }

    @Test
    void receiptDetailsUseSameFactsDatesAndPermissionsAsCards() {
        department(1,null,"SALES","销售部"); department(27,1L,"HB","河北省");
        department(11,27L,"SJZ","石家庄"); department(28,27L,"TS","唐山市");
        jdbc.update("INSERT INTO bi_sales_contact_city_dim(tenant_id,region_code,city_name,department_id,department_path) VALUES(?,'HB','河北省',27,'[1,27]'),(?,'SJZ','石家庄',11,'[1,27,11]'),(?,'TS','唐山市',28,'[1,27,28]')",tenant,tenant,tenant);
        for (int i=1;i<=6;i++) fact(i,"A",i==2 ? "TS" : "SJZ",i==2 ? 28 : 11,1,100);
        jdbc.update("UPDATE bi_sales_order_fact SET order_date='2026-09-30 16:00:00',order_status_code='CONFIRMED',paid_amount=40,unpaid_amount=60 WHERE tenant_id=?",tenant);
        jdbc.update("UPDATE bi_sales_order_fact SET order_date='2026-08-01' WHERE tenant_id=? AND order_id=3",tenant);
        jdbc.update("UPDATE bi_sales_order_fact SET order_status_code='CANCELLED' WHERE tenant_id=? AND order_id=4",tenant);
        jdbc.update("UPDATE bi_sales_order_fact SET deleted=1 WHERE tenant_id=? AND order_id=5",tenant);
        jdbc.update("UPDATE bi_sales_order_fact SET unpaid_amount=0,paid_amount=100 WHERE tenant_id=? AND order_id=6",tenant);
        jdbc.update("""
            INSERT INTO bi_sales_payment_fact(tenant_id,payment_id,order_id,region_code,payment_time,paid_amount,synced_time,deleted)
            VALUES (?,101,3,'SJZ','2026-10-01',40,UTC_TIMESTAMP(6),0),
                   (?,102,2,'TS','2026-10-23 15:59:59.999999',30,UTC_TIMESTAMP(6),0),
                   (?,103,1,'SJZ','2026-10-24',999,UTC_TIMESTAMP(6),0),
                   (?,104,1,'SJZ','2026-10-01',999,UTC_TIMESTAMP(6),1)
            """,tenant,tenant,tenant,tenant);
        var from=java.time.LocalDateTime.parse("2026-09-30T16:00:00");
        var to=java.time.LocalDateTime.parse("2026-10-23T15:59:59.999999");
        var full=policy(List.of(clause("ALL",all(),all(),all())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(full);
        try(var context=SupplyAuthorizationContext.open(iam,actor,full);var session=sessions.openSession()) {
            var mapper=session.getMapper(com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper.class);
            var payments=mapper.receiptDetails(tenant,from,to,"HB",null,null,null,"receipts",true,0,20).getFirst();
            assertThat(payments.get("total")).isEqualTo(2L);
            var national = mapper.cityReceipts(tenant,from,to,null,null,null,null);
            assertThat(national).singleElement().satisfies(r -> {
                assertThat(r.get("regionCode")).isEqualTo("HB");
                assertThat(new java.math.BigDecimal(r.get("receiptAmount").toString())).isEqualByComparingTo("70");
            });
            assertThat(mapper.cityReceipts(tenant,from,to,"HB",null,null,null))
                    .extracting(r -> r.get("regionCode")).containsExactly("SJZ", "TS");

            assertThat(payments.get("amount")).isEqualTo(mapper.collectionSummary(tenant,from,to,"HB",null,null,null).get("receiptAmount"));
            var orders=mapper.receiptDetails(tenant,from,to,"HB",null,null,null,"unpaid",true,0,20).getFirst();
            assertThat(orders.get("total")).isEqualTo(2L);
            assertThat(orders.get("amount")).isEqualTo(mapper.salesSummary(tenant,from,to,"HB",null,null,null).get("unpaidAmount"));
            assertThat(mapper.receiptDetails(tenant,from,to,"HB",null,null,null,"receipts",false,1,1)).singleElement().satisfies(row -> assertThat(row.get("id")).isEqualTo(101L));
        }
        var scoped=policy(List.of(clause("REGION",none(),specified("TS"),none())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(scoped);
        try(var context=SupplyAuthorizationContext.open(iam,actor,scoped);var session=sessions.openSession()) {
            var mapper=session.getMapper(com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper.class);
            assertThat(mapper.receiptDetails(tenant,from,to,"TS",null,null,null,"receipts",false,0,20)).singleElement().satisfies(row -> assertThat(row.get("id")).isEqualTo(102L));
            assertThat(mapper.receiptDetails(tenant,from,to,"SJZ",null,null,null,"receipts",false,0,20)).isEmpty();
        }
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }





    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "10,CITY-BJ,400", "20,CITY-HZ,600", "ALL,ALL,1500"
    })
    void paymentRiskUsesCurrentDepartmentOrAllScopeBeforeReturningOrders(
            String departmentId, String expectedCity, int expectedAmount) {
        department(1, null, "SALES", "销售部");
        department(10, 1L, "CITY-BJ", "北京市");
        department(20, 1L, "CITY-HZ", "杭州市");
        productionMapper.refreshCityDimension(tenant);
        fact(1, "BJ-OWNER", "CITY-BJ", 10, 11, 100);
        fact(2, "HZ-OWNER", "CITY-HZ", 20, 11, 200);
        fact(3, "BJ-OWNER", "CITY-BJ", 10, 11, 300);
        fact(4, "HZ-OWNER", "CITY-HZ", 20, 11, 400);
        fact(5, "UNKNOWN", "UNKNOWN", 30, 11, 500);
        fact(6, "BJ-OWNER", "CITY-BJ", 10, 11, 900);
        jdbc.update("UPDATE bi_sales_order_fact SET order_date='2026-09-25',paid_amount=10 WHERE tenant_id=?", tenant);
        jdbc.update("UPDATE bi_sales_order_fact SET payment_status_code='PAID',paid_amount=900 WHERE tenant_id=? AND order_id=6", tenant);
        jdbc.update("UPDATE bi_sales_order_fact SET payment_status_code='PARTIAL_PAID' WHERE tenant_id=? AND order_id=1", tenant);
        jdbc.update("UPDATE bi_order_authority SET attribution_state='REVIEW',department_id=NULL,department_path=JSON_ARRAY() WHERE tenant_id=? AND order_id BETWEEN 3 AND 5", tenant);
        jdbc.update("INSERT INTO bi_product_category_dim(tenant_id,category_id,category_name,synced_time) VALUES(?,1,'台呢',UTC_TIMESTAMP(6))", tenant);
        jdbc.update("""
                INSERT INTO bi_sales_order_line_fact
                    (tenant_id,order_id,order_line_id,product_id,product_name,product_category_id,product_category_name,line_amount,order_date,synced_time)
                SELECT tenant_id,order_id,order_id,1,'台呢A',1,'台呢',payable_amount,order_date,synced_time
                FROM bi_sales_order_fact WHERE tenant_id=?
                """, tenant);
        // 同一订单ID在其他租户出现，ALL 也不能跨租户读取。
        jdbc.update("""
                INSERT INTO bi_sales_order_fact(tenant_id,order_id,owner_staff_code,region_code,order_date,payable_amount,synced_time)
                VALUES(?,1,'OTHER-OWNER','CITY-BJ','2026-09-25',9999,UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString());
        boolean full = "ALL".equals(departmentId);
        var p = policy(List.of(new Clause(UUID.randomUUID(), "ANALYTICS",
                full ? "ALL" : "DEPARTMENT", full ? all() : specified(departmentId), all(), all(), false)), all(), all());
        when(iam.authorization(any(), any())).thenReturn(p);
        var service = new com.rigour.analytics.application.service.PaymentRiskService(
                new com.rigour.analytics.infrastructure.persistence.repository.JdbcPaymentRiskStore(jdbc.getDataSource()),
                new com.rigour.analytics.infrastructure.persistence.repository.JdbcReceivablesStore(jdbc.getDataSource()),
                scopeService, java.time.Clock.fixed(java.time.Instant.parse("2026-10-20T00:00:00Z"), java.time.ZoneOffset.UTC));
        var endpoint = new com.rigour.analytics.api.controller.AnalyticsPaymentRiskController(service);
        var from = java.time.Instant.parse("2026-09-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-10-20T00:00:00Z");
        try (var context = SupplyAuthorizationContext.open(iam, actor, p)) {
            assertThatThrownBy(() -> endpoint.paymentRisk(from, to, null, null, null, null))
                    .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class)
                    .hasMessageContaining("资金数据尚未完成同步");
            for (String dataset : List.of("ORDER_CASH_RECEIPT", "ORDER_CASH_APPLICATION",
                    "ORDER_RECEIVABLE_PLAN", "ORDER_RECEIVABLE_ADJUSTMENT", "ORDER_RECEIVABLE_OPENING")) {
                jdbc.update("""
                        INSERT INTO bi_source_snapshot_checkpoint(tenant_id,dataset,source_version,projected_at)
                        VALUES(?,?,'test',UTC_TIMESTAMP(6))
                        """, tenant, dataset);
            }
            var orders = endpoint.paymentRisk(from, to, null, null, null, null).data().orders();
            assertThat(orders).hasSize(full ? 5 : 2);
            assertThat(orders).extracting(o -> o.orderId()).doesNotContain("6");
            if (full || "10".equals(departmentId)) {
                assertThat(orders).extracting(o -> o.orderId()).contains("1");
            }
            assertThat(orders.stream().map(o -> o.orderAmount()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                    .isEqualByComparingTo(String.valueOf(expectedAmount));
            assertThat(orders).allSatisfy(order -> {
                assertThat(order.products()).singleElement().satisfies(product ->
                        assertThat(product.riskLevel()).isEqualTo("HIGH"));
                if (!full) assertThat(order.regionCode()).isEqualTo(expectedCity);
            });
            if (!full) {
                String otherCity = "CITY-BJ".equals(expectedCity) ? "CITY-HZ" : "CITY-BJ";
                String otherOwner = "CITY-BJ".equals(expectedCity) ? "HZ-OWNER" : "BJ-OWNER";
                assertThat(endpoint.paymentRisk(from, to, otherCity, null, null, null).data().orders()).isEmpty();
                assertThat(endpoint.paymentRisk(from, to, null, otherOwner, List.of(1L), "HIGH").data().orders()).isEmpty();
                assertThat(endpoint.paymentRisk(from, to, expectedCity, null, List.of(1L), "HIGH").data().orders())
                        .containsExactlyElementsOf(orders);
            } else {
                assertThat(orders).extracting(o -> o.regionCode()).contains("CITY-BJ", "CITY-HZ", "UNKNOWN");
            }
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
            var goals = hrTargets().cityGoals(tenant,2026,null);
            assertThat(goals).hasSize(12);
            assertThat(goals).allSatisfy(row -> {
                assertThat(row.regionCode()).isEqualTo("HZ");
                assertThat(new java.math.BigDecimal(row.salesTarget().toString()))
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
        String path = parent==null ? "["+id+"]" : jdbc.queryForObject("SELECT department_path FROM bi_source_hr_hr_department WHERE tenant_id=? AND id=?",String.class,tenant,parent).replace("]",","+id+"]");
        jdbc.update("INSERT INTO bi_source_hr_hr_department(id,tenant_id,department_code,department_name,parent_id,status_code,deleted,department_path) VALUES(?,?,?,?,?,'ACTIVE',0,?)",id,tenant,code,name,parent,path);
    }

    @Autowired com.rigour.analytics.api.controller.SalesDashboardController salesDashboard;
    @Autowired com.rigour.analytics.api.controller.AnalyticsPaymentRiskController paymentRisk;

    @Test
    void crossCitySalesUseIndependentDepartmentAndCustomerRegion() throws Exception {
        department(1,null,"SALES","销售部");
        department(5,1L,"GROUP","苏无部");
        department(10,5L,"SZ-DEPT","苏州市部");
        department(20,5L,"WX-DEPT","无锡市部");
        department(21,20L,"WX-TEAM","无锡一组");
        jdbc.update("UPDATE bi_source_hr_hr_department SET leader_employee_code='A' WHERE tenant_id=? AND id=5", tenant);
        productionMapper.refreshCityDimension(tenant);
        fact(1,"张三","SZ",20,11,10000);
        fact(2,"A","WX",10,11,20000);
        jdbc.update("UPDATE bi_order_authority SET department_path=JSON_ARRAY(1,5,department_id) WHERE tenant_id=?", tenant);
        productionMapper.alignOperatingOrderCities(tenant);
        jdbc.update("UPDATE bi_sales_order_fact SET order_date='2026-11-15',order_status_code='COMPLETED' WHERE tenant_id=?",tenant);
        for (String dataset : List.of("ORDER_CASH_RECEIPT", "ORDER_CASH_APPLICATION", "ORDER_RECEIVABLE_PLAN",
                "ORDER_RECEIVABLE_ADJUSTMENT", "ORDER_RECEIVABLE_OPENING")) {
            jdbc.update("INSERT INTO bi_source_snapshot_checkpoint(tenant_id,dataset,source_version,projected_at) "
                    + "VALUES(?,?,'test',UTC_TIMESTAMP(6))", tenant, dataset);
        }
        var policy = policy(List.of(clause("ALL",all(),all(),all())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(policy);
        try (var context=SupplyAuthorizationContext.open(iam,actor,policy)) {
            var mvc=MockMvcBuilders.standaloneSetup(supplyDashboard, salesDashboard, paymentRisk).build();
            mvc.perform(get("/api/v1/analytics/supply/dashboard/department-filters"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultDepartmentCode").value("GROUP"))
                .andExpect(jsonPath("$.data.departments[?(@.code == 'WX-TEAM')].parentCode").value("WX-DEPT"));
            for (String department : List.of("GROUP", "WX-DEPT", "SZ-DEPT")) {
                boolean includesZhang = !department.equals("SZ-DEPT");
                var sales = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/analytics/supply/dashboard/sales-analysis/summary")
                        .contentType("application/json").content("{\"ownerStaffCodes\":[\"张三\",\"A\"]}");
                var risk = get("/api/v1/analytics/supply/dashboard/payment-risk");
                for (var request : List.of(sales, risk)) {
                    request.param("regionCode", department).param("customerRegionCode", "SZ")
                            .param("from", "2026-11-01T00:00:00Z").param("to", "2026-11-24T00:00:00Z");
                }
                mvc.perform(sales).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.sales").value(includesZhang ? 10000 : 0));
                var result = mvc.perform(risk).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.orders.length()").value(includesZhang ? 1 : 0));
                if (includesZhang) result.andExpect(jsonPath("$.data.orders[0].ownerStaffCode").value("张三"));
            }
            for (var sample : List.of(
                    List.of("", "", 30000), List.of("GROUP", "", 30000),
                    List.of("WX-DEPT", "", 10000), List.of("", "SZ", 10000),
                    List.of("WX-DEPT", "SZ", 10000), List.of("SZ-DEPT", "SZ", 0),
                    List.of("SZ-DEPT", "WX", 20000), List.of("WX-TEAM", "", 0))) {
                for (String projection : List.of("OPERATING", "SALES", "CITY")) {
                    var req=get("/api/v1/analytics/supply/dashboard/overview").param("projection",projection)
                            .param("from","2026-11-01T00:00:00Z").param("to","2026-11-30T23:59:59Z");
                    if (!sample.get(0).equals("")) req.param("regionCode",sample.get(0).toString());
                    if (!sample.get(1).equals("")) req.param("customerRegionCode",sample.get(1).toString());
                    mvc.perform(req).andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.metrics[0].metricCode").value("sales_amount"))
                            .andExpect(jsonPath("$.data.metrics[0].value").value(
                                    org.hamcrest.Matchers.equalTo(((Number) sample.get(2)).doubleValue()), Double.class));
                }
            }
            mvc.perform(get("/api/v1/analytics/supply/dashboard/overview").param("projection","OPERATING")
                    .param("from","2026-11-01T00:00:00Z").param("to","2026-11-30T23:59:59Z"))
                .andExpect(jsonPath("$.data.citySalesRanking.length()").value(1))
                .andExpect(jsonPath("$.data.citySalesRanking[0].dimensionCode").value("GROUP"))
                .andExpect(jsonPath("$.data.citySalesRanking[0].salesAmount").value(30000));
            mvc.perform(get("/api/v1/analytics/supply/dashboard/overview").param("projection","OPERATING")
                    .param("regionCode","GROUP").param("from","2026-11-01T00:00:00Z").param("to","2026-11-30T23:59:59Z"))
                .andExpect(jsonPath("$.data.citySalesRanking.length()").value(2))
                .andExpect(jsonPath("$.data.citySalesRanking[0].dimensionCode").value("SZ-DEPT"))
                .andExpect(jsonPath("$.data.citySalesRanking[1].dimensionCode").value("WX-DEPT"));
        }
        jdbc.update("INSERT INTO bi_sales_contact_city_dim(tenant_id,region_code,city_name,department_id,department_path) VALUES(?,'OTHER','其他战区',30,JSON_ARRAY(1,30))", tenant);
        var managed = policy(List.of(new SupplyAuthorizationView.Clause(
                UUID.randomUUID(), "ANALYTICS", "DEPARTMENT", specified("5"), all(), none(), true)), all(), all());
        when(iam.authorization(any(),any())).thenReturn(managed);
        try (var context=SupplyAuthorizationContext.open(iam,actor,managed)) {
            var mvc=MockMvcBuilders.standaloneSetup(supplyDashboard, salesDashboard, paymentRisk).build();
            mvc.perform(get("/api/v1/analytics/supply/dashboard/department-filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultDepartmentCode").value("GROUP"))
                .andExpect(jsonPath("$.data.departments[?(@.code == 'SZ-DEPT')].parentCode").value("GROUP"))
                .andExpect(jsonPath("$.data.departments[?(@.code == 'WX-DEPT')].parentCode").value("GROUP"))
                .andExpect(jsonPath("$.data.departments[?(@.code == 'OTHER')]").isEmpty());
            var risk = get("/api/v1/analytics/supply/dashboard/payment-risk").param("regionCode","GROUP")
                    .param("from","2026-11-01T00:00:00Z").param("to","2026-11-30T23:59:59Z");
            mvc.perform(risk).andExpect(status().isOk()).andExpect(jsonPath("$.data.orders.length()").value(2));
            for (String projection : List.of("OPERATING", "SALES", "CITY")) {
                mvc.perform(get("/api/v1/analytics/supply/dashboard/overview").param("projection",projection)
                        .param("regionCode","GROUP").param("from","2026-11-01T00:00:00Z").param("to","2026-11-30T23:59:59Z"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.metrics[?(@.metricCode == 'sales_amount')].value").value(org.hamcrest.Matchers.contains(30000.0)));
            }
        }
        var restricted = policy(List.of(clause("DEPARTMENT",specified("20"),all(),none())),all(),all());
        when(iam.authorization(any(),any())).thenReturn(restricted);
        try (var context=SupplyAuthorizationContext.open(iam,actor,restricted)) {
            var mvc=MockMvcBuilders.standaloneSetup(supplyDashboard).build();
            mvc.perform(get("/api/v1/analytics/supply/dashboard/department-filters"))
                .andExpect(jsonPath("$.data.departments[?(@.code == 'SZ-DEPT')]").isEmpty());
            mvc.perform(get("/api/v1/analytics/supply/dashboard/overview").param("projection","OPERATING")
                    .param("customerRegionCode","WX").param("from","2026-11-01T00:00:00Z").param("to","2026-11-30T23:59:59Z"))
                .andExpect(jsonPath("$.data.metrics[?(@.metricCode == 'sales_amount')].value").value(org.hamcrest.Matchers.contains(0.0)));
        }
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
            assertThat(hrTargets().cityGoals(tenant,2026,null)).hasSize(12).allSatisfy(row->assertThat(row.regionCode()).isEqualTo("CITY-HZ"));
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
    void paymentPerformanceKeepsUnverifiedOwnersPendingWithoutChangingSourceOrCity(
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
        assertThat(original.get("owner_staff_code")).isNull();
        assertThat(original.get("owner_staff_name")).isNull();
        assertThat(original.get("collector_staff_code")).isEqualTo(collector);
        assertThat(original.get("collector_staff_name")).isEqualTo(collectorSnapshot);
        assertThat(original.get("region_code")).isEqualTo("HZ");
        assertThat(original.get("payment_time")).isEqualTo(java.time.LocalDateTime.parse("2026-09-01T08:30:00"));
        assertThat((java.math.BigDecimal)original.get("paid_amount")).isEqualByComparingTo("125.375");
        var repository=new com.rigour.analytics.infrastructure.persistence.repository.MybatisPlusSupplyDashboardRepository(productionMapper,hrTargets());
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
    void confirmedPaymentOwnerDoesNotMoveWithCustomerOrRecorder() {
        paymentPerformanceKeepsUnverifiedOwnersPendingWithoutChangingSourceOrCity("RECORDER","CURRENT",0,"录入人",null,null,null);
        jdbc.update("UPDATE bi_source_order_order_payment_record SET payment_owner_code='LI',payment_owner_name='李四',payment_owner_evidence='CRM主责历史#2' WHERE tenant_id=?",tenant);
        var now=java.time.LocalDateTime.parse("2026-11-09T00:00:00");
        assertThat(productionMapper.alignPaymentOrderAttribution(tenant,now)).isEqualTo(1);
        jdbc.update("UPDATE bi_source_crm_crm_customer SET owner_employee_code='WANG' WHERE tenant_id=?",tenant);
        assertThat(productionMapper.alignPaymentOrderAttribution(tenant,now.plusHours(1))).isZero();
        assertThat(jdbc.queryForMap("SELECT owner_staff_code,owner_staff_name,collector_staff_code FROM bi_sales_payment_fact WHERE tenant_id=?",tenant)).containsEntry("owner_staff_code","LI").containsEntry("owner_staff_name","李四").containsEntry("collector_staff_code","RECORDER");
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
                actor.permissions(),
                "analytics:dashboard:read",
                true,
                clauses,
                regions,
                warehouses);
    }

    private void grantAdditionalAction(String action) {
        var permissions = new HashSet<>(actor.permissions());
        permissions.add(action);
        actor = new CallerIdentity(actor.principalScope(), actor.principalId(), actor.tenantId(),
                actor.userId(), actor.platformUserId(), actor.sessionId(), actor.sessionVersion(),
                actor.userSecurityVersion(), actor.tenantPolicyVersion(), actor.roles(), permissions);
        TestAuthorizationContext.set(actor);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "SELF,2,300", "REGION,2,1000", "ALL,3,1200", "SELF_HZ,1,100"
    })
    void batchedTargetActualsMatchScalarQueriesWithinCurrentScope(
            String mode, int customers, int amount) {
        fact(9501, "A", "HZ", 10, 11, 100);
        fact(9502, "A", "NB", 10, 11, 200);
        fact(9503, "B", "HZ", 20, 22, 900);
        jdbc.update("UPDATE bi_sales_order_fact SET customer_id=order_id,order_date='2026-10-15',"
                + "order_status_code='COMPLETED',deleted=0 WHERE tenant_id=?", tenant);
        jdbc.update("""
                INSERT INTO bi_sales_order_line_fact
                    (tenant_id,order_id,order_line_id,owner_staff_code,region_code,brand_name,
                     line_amount,order_date,order_status_code,deleted,synced_time)
                SELECT tenant_id,order_id,order_id,owner_staff_code,region_code,
                       '杨掌柜 X CBSA世界斯诺克学院',payable_amount,order_date,'COMPLETED',0,UTC_TIMESTAMP(6)
                  FROM bi_sales_order_fact WHERE tenant_id=?
                """, tenant);
        var p = switch (mode) {
            case "SELF" -> policy(List.of(clause("SELF", none(), all(), none())), all(), all());
            case "SELF_HZ" -> policy(List.of(clause("SELF", none(), all(), none())), specified("HZ"), all());
            case "REGION" -> policy(List.of(clause("REGION", none(), specified("HZ"), none())), all(), all());
            default -> policy(List.of(clause("ALL", all(), all(), all())), all(), all());
        };
        when(iam.authorization(any(), any())).thenReturn(p);
        var from = java.time.LocalDateTime.of(2026, 10, 1, 0, 0);
        var to = java.time.LocalDateTime.of(2026, 10, 23, 15, 59, 59);
        try (var ctx = SupplyAuthorizationContext.open(iam, actor, p);
                var session = sessions.openSession()) {
            var mapper = session.getMapper(com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper.class);
            for (boolean city : List.of(false, true)) {
                var rows = mapper.targetBusinessActuals(tenant, from, to, null, null, city);
                var count = java.math.BigDecimal.ZERO;
                var brand = java.math.BigDecimal.ZERO;
                for (var row : rows) {
                    String code = (String) row.get("dimensionCode");
                    var value = new java.math.BigDecimal(row.get("actualValue").toString());
                    if ("ORDERED_STORE_COUNT".equals(row.get("metricCode"))) {
                        var legacy = mapper.customerRetention(tenant, from, to,
                                city ? code : null, city ? null : code, null, null);
                        assertThat(value).isEqualByComparingTo(legacy.get("orderingCustomerCount").toString());
                        count = count.add(value);
                    } else {
                        assertThat(value).isEqualByComparingTo(mapper.yangzhangguiTargetSales(tenant,
                                from, to, city ? code : null, city ? null : code));
                        brand = brand.add(value);
                    }
                }
                assertThat(count).isEqualByComparingTo(String.valueOf(customers));
                assertThat(brand).isEqualByComparingTo(String.valueOf(amount));
                assertThat(mapper.targetBusinessActuals(tenant, from, to, "UNKNOWN-CITY", null, city)).isEmpty();
            }
        }
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
        grantAdditionalAction("analytics:targets:write");
        fact(901, "A", "HZ", 10, 11, 100);
        fact(902, "B", "HZ", 10, 11, 100);
        fact(903, "B", "NB", 10, 11, 100);
        jdbc.update(
                "INSERT INTO bi_region_authority(tenant_id,region_code,region_path)"
                        + " VALUES(?,'HZ',JSON_ARRAY('HZ')),(?,'NB',JSON_ARRAY('NB'))",
                tenant,
                tenant);
        jdbc.update("INSERT INTO bi_sales_contact_city_dim(tenant_id,region_code,city_name,source_region_code) VALUES(?,'HZ','杭州','HZ'),(?,'NB','宁波','NB')",tenant,tenant);
        jdbc.update("INSERT INTO bi_employee_dim(tenant_id,employee_code,employee_name,employment_status,region_code,synced_time) VALUES(?,'A','A','ACTIVE','HZ',UTC_TIMESTAMP(6)),(?,'B','B','ACTIVE','HZ',UTC_TIMESTAMP(6))",tenant,tenant);
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
                            actor.permissions(),
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

    private com.rigour.analytics.infrastructure.persistence.repository.HrDashboardTargets hrTargets() {
        com.rigour.analytics.application.port.out.HrTargetClient client=(tenantId,from,to) -> {
            var result=new java.util.ArrayList<com.rigour.hr.api.v1.model.TargetSettingsModels.Target>();
            for (var city:jdbc.queryForList("SELECT region_code FROM bi_sales_contact_city_dim WHERE tenant_id=?",String.class,tenantId))
                for (var month=java.time.YearMonth.parse(from);!month.isAfter(java.time.YearMonth.parse(to));month=month.plusMonths(1))
                    for(var metric:List.of("SALES_AMOUNT","RECEIPT_AMOUNT","NEW_CUSTOMER","REPEAT_CUSTOMER"))
                        result.add(new com.rigour.hr.api.v1.model.TargetSettingsModels.Target(month.toString(),"CITY",city,city,metric,new java.math.BigDecimal("100000"),0));
            return result;
        };
        return new com.rigour.analytics.infrastructure.persistence.repository.HrDashboardTargets(client,jdbc.getDataSource(),productionMapper);
    }

    interface Queries {
        @Select("SELECT COALESCE(SUM(cost_amount),0) FROM bi_city_cost_record WHERE tenant_id=#{tenant}")
        long cityCosts(@Param("tenant") String tenant);

        @Select(
                "SELECT CONCAT(dimension_type,':',dimension_code) FROM bi_target_subject WHERE"
                        + " tenant_id=#{tenant}")
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
        grantAdditionalAction("analytics:city-cost:write");
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
                        actor.permissions(),
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

    @Test
    void employeeCityAliasesUseOperatingCodesWithoutDuplicateRowsOrGuessingAmbiguousNames() {
        department(1, null, "SALES", "销售部");
        department(10, 1L, "CITY-WH", "武汉市");
        department(20, 1L, "CITY-HZ", "杭州市");
        department(30, 1L, "CITY-J1", "江城市");
        department(31, 1L, "CITY-J2", "江城市");
        department(40, 1L, "CITY-K1", "昆明");
        department(41, 1L, "CITY-K2", "昆明市");
        department(50, 1L, "PROVINCE", "湖北省");
        jdbc.update("INSERT INTO bi_source_crm_crm_customer_area(id,tenant_id,area_code,area_name,status,deleted)"
                + " VALUES(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),'RAW-WH','武汉市','ACTIVE',0),"
                + "(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),'RAW-HZ','杭州市','ACTIVE',0)", tenant, tenant);
        String[] cities = {"武汉", " 武汉市 ", "杭州", "江城", "江城市", "昆明", "昆明市", "湖北省", "总部", null, "武汉"};
        String[] employees = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"};
        for (int i = 0; i < cities.length; i++) {
            int department = i == 0 || i == 1 ? 10 : 20;
            jdbc.update("INSERT INTO bi_source_hr_hr_employee(id,tenant_id,employee_code,employee_name,"
                    + "employment_status,city_name,department_id,department_path) VALUES(?,?,?,?,'ACTIVE',?,?,JSON_ARRAY(?))",
                    i + 1, tenant, employees[i], employees[i], cities[i], department, department);
        }
        String other = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO bi_source_hr_hr_employee(id,tenant_id,employee_code,employee_name,"
                + "employment_status,city_name) VALUES(1,?,'A','另一租户','ACTIVE','武汉')", other);
        employeeStore.refresh(tenant, java.time.Instant.now());
        productionMapper.refreshCityDimension(tenant);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_employee_dim WHERE tenant_id=?", Integer.class, tenant))
                .isEqualTo(11);
        var assigned = new HashMap<String, String>();
        jdbc.query("SELECT employee_code,region_code FROM bi_employee_dim WHERE tenant_id=?", rs -> {
            assigned.put(rs.getString(1), rs.getString(2));
        }, tenant);
        assertThat(assigned).containsEntry("A", "CITY-WH").containsEntry("B", "CITY-WH")
                .containsEntry("C", "CITY-HZ").containsEntry("F", "CITY-K1")
                .containsEntry("G", "CITY-K2").containsEntry("I", "COST_HEADQUARTERS")
                .containsEntry("K", "CITY-WH");
        for (String code : List.of("D", "E", "H", "J")) assertThat(assigned.get(code)).isNull();
        assertThat(jdbc.queryForObject("SELECT city_name FROM bi_source_hr_hr_employee WHERE tenant_id=? AND employee_code='A'",
                String.class, tenant)).isEqualTo("武汉");
        jdbc.update("INSERT INTO bi_region_authority(tenant_id,region_code,region_path)"
                + " VALUES(?,'RAW-WH',JSON_ARRAY('PROVINCE-HB','RAW-WH')),(?,'RAW-HZ',JSON_ARRAY('PROVINCE-ZJ','RAW-HZ'))", tenant, tenant);
        var from = java.time.Instant.parse("2026-10-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-10-23T15:59:59Z");
        var wh = policy(List.of(clause("REGION", none(), specified("RAW-WH"), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(wh);
        try (var context = SupplyAuthorizationContext.open(iam, actor, wh)) {
            assertThat(employeeStore.read(tenant, from, to, "CITY-WH", null).rows())
                    .extracting(r -> r.employee().employeeCode()).containsExactlyInAnyOrder("A", "B", "K");
            assertThat(employeeStore.read(tenant, from, to, "CITY-HZ", null).rows()).isEmpty();
        }
        var department = policy(List.of(clause("DEPARTMENT", specified("10"), all(), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(department);
        try (var context = SupplyAuthorizationContext.open(iam, actor, department)) {
            assertThat(employeeStore.read(tenant, from, to, null, null).rows())
                    .extracting(r -> r.employee().employeeCode()).containsExactlyInAnyOrder("A", "B");
        }
        var self = policy(List.of(clause("SELF", none(), all(), none())), all(), all());
        when(iam.authorization(any(), any())).thenReturn(self);
        try (var context = SupplyAuthorizationContext.open(iam, actor, self)) {
            assertThat(employeeStore.read(tenant, from, to, "CITY-WH", null).rows())
                    .extracting(r -> r.employee().employeeCode()).containsExactly("A");
        }
    }

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
