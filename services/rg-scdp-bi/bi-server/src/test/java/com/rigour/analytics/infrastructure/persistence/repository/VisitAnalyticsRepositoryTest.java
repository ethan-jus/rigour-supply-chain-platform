package com.rigour.analytics.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实 SQL 验证跨天、跨人员去重、权限筛选和未同步状态。 */
class VisitAnalyticsRepositoryTest {
    @Test void aggregatesDistinctStoresSeparatelyAndKeepsEveryScope() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:visits_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE bi_sales_contact_snapshot (tenant_id VARCHAR, synced_time TIMESTAMP, business_links_ready INT)");
        jdbc.execute("CREATE TABLE bi_employee_dim (tenant_id VARCHAR, employee_code VARCHAR, employee_name VARCHAR)");
        jdbc.execute("CREATE TABLE bi_sales_contact_fact (tenant_id VARCHAR, store_id VARCHAR, region_code VARCHAR, city_name VARCHAR, submitted_at TIMESTAMP, review_status VARCHAR, salesperson_id VARCHAR, owner_staff_code VARCHAR, customer_code VARCHAR)");
        jdbc.update("INSERT INTO bi_sales_contact_snapshot VALUES ('T','2026-09-15',1)");
        jdbc.update("INSERT INTO bi_employee_dim VALUES ('T','E1','员工一'),('OTHER','E1','其他租户同编码')");
        jdbc.update("INSERT INTO bi_sales_contact_fact VALUES ('T','S1','BJ','北京','2026-08-31 16:00:00','PENDING','P1','E1','C1'),('T','S1','BJ','北京','2026-09-01 16:00:00','APPROVED','P2',NULL,'C1'),('T','S2','SH','上海','2026-09-02 01:00:00','FLAGGED','P3',NULL,NULL),('OTHER','S9','BJ','北京','2026-09-01','PENDING','P9','E1',NULL),('T','S8','BJ','北京','2026-08-31 15:59:59','PENDING','P1','E1',NULL)");
        var store = new JdbcVisitAnalyticsStore(ds);
        var from = Instant.parse("2026-08-31T16:00:00Z");
        var to = Instant.parse("2026-09-03T15:59:59Z");
        var all = store.read("T", from, to, null, null);
        assertThat(all.summary().visits()).isEqualTo(3);
        assertThat(all.summary().contactedStores()).isEqualTo(2);
        assertThat(all.summary().repeatStores()).isEqualTo(1);
        assertThat(all.summary().visitingPeople()).isEqualTo(3);
        assertThat(all.summary().crmLinkedStores()).isEqualTo(1);
        assertThat(all.summary().unlinkedEmployeeVisits()).isEqualTo(2);
        assertThat(all.summary().approvedVisits()).isEqualTo(1);
        assertThat(all.summary().pendingVisits()).isEqualTo(1);
        assertThat(all.summary().flaggedVisits()).isEqualTo(1);
        assertThat(all.days()).hasSize(3);
        assertThat(all.days().getFirst().date().toString()).isEqualTo("2026-09-01");
        assertThat(all.days().getFirst().visits()).isEqualTo(1);
        assertThat(all.days().getLast().visits()).isZero();
        assertThat(all.days().stream().mapToLong(d -> d.contactedStores()).sum()).isEqualTo(3);
        assertThat(all.people()).hasSize(3);
        assertThat(all.people().stream().filter(p -> "E1".equals(p.employeeCode())).findFirst().orElseThrow().employeeName()).isEqualTo("员工一");
        var city = store.read("T", from, to, "BJ", null);
        assertThat(city.summary().visits()).isEqualTo(2);
        assertThat(city.summary().contactedStores()).isEqualTo(1);
        var self = store.read("T", from, to, "BJ", "E1");
        assertThat(self.summary().visits()).isEqualTo(1);
        assertThat(self.people()).hasSize(1);
        assertThat(self.cities()).hasSize(1);
        assertThat(self.days().stream().mapToLong(d -> d.visits()).sum()).isEqualTo(1);
        assertThat(store.read("T", from, to, "BJ", "MISSING").status()).isEqualTo("EMPTY");
        assertThat(store.read("OTHER", from, to, null, null).summary()).isNull();
        jdbc.update("UPDATE bi_sales_contact_snapshot SET business_links_ready=0");
        assertThat(store.read("T", from, to, null, null).status()).isEqualTo("NOT_READY");
    }
}
