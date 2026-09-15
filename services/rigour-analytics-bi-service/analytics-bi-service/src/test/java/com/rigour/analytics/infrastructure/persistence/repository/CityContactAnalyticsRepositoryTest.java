package com.rigour.analytics.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 已提交拜访去重：重复拜访不放大客户，审核标记互斥，零记录城市保留。 */
class CityContactAnalyticsRepositoryTest {
    @Test void deduplicatesStoresAndKeepsTenantCityAndTimeScope() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:contacts_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE bi_sales_contact_snapshot (tenant_id VARCHAR, synced_time TIMESTAMP, business_links_ready INT DEFAULT 1)");
        jdbc.execute("CREATE TABLE bi_sales_contact_city_dim (tenant_id VARCHAR, region_code VARCHAR, city_name VARCHAR)");
        jdbc.execute("CREATE TABLE bi_sales_contact_fact (tenant_id VARCHAR, store_id VARCHAR, region_code VARCHAR, city_name VARCHAR, submitted_at TIMESTAMP, review_status VARCHAR, owner_staff_code VARCHAR DEFAULT 'E1', customer_code VARCHAR)");
        jdbc.update("INSERT INTO bi_sales_contact_snapshot (tenant_id,synced_time) VALUES ('T','2026-09-15')");
        jdbc.update("INSERT INTO bi_sales_contact_city_dim VALUES ('T','BJ','北京'),('T','SH','上海')");
        jdbc.update("INSERT INTO bi_sales_contact_fact (tenant_id,store_id,region_code,city_name,submitted_at,review_status) VALUES ('T','S1','BJ','北京','2026-09-01','PENDING'),('T','S1','BJ','北京','2026-09-02','APPROVED'),('T','S2','BJ','北京','2026-09-02','FLAGGED'),('T','S2','BJ','北京','2026-09-02','APPROVED'),('OTHER','S3','BJ','北京','2026-09-02','PENDING'),('T','S4','BJ','北京','2026-08-01','PENDING'),('T','S5',NULL,'未关联城市','2026-09-02','PENDING')");
        jdbc.update("UPDATE bi_sales_contact_fact SET owner_staff_code='E2' WHERE store_id='S2'");
        var store = new JdbcCityContactAnalyticsStore(ds);
        var from = Instant.parse("2026-09-01T00:00:00Z");
        var to = Instant.parse("2026-09-03T00:00:00Z");
        var all = store.read("T", from, to, null, null);
        assertThat(all.cities()).hasSize(3);
        assertThat(all.cities().stream().mapToLong(c -> c.contactedStores()).sum()).isEqualTo(3);
        var city = store.read("T", from, to, "BJ", null).cities().getFirst();
        assertThat(city.contactedStores()).isEqualTo(2);
        assertThat(city.approvedStores()).isEqualTo(1);
        assertThat(city.flaggedStores()).isEqualTo(1);
        assertThat(city.pendingStores()).isZero();
        assertThat(store.read("T", from, to, "SH", null).cities().getFirst().contactedStores()).isZero();
        assertThat(store.read("T", from, to, "BJ", "E1").cities().getFirst().contactedStores()).isEqualTo(1);
        assertThat(store.read("T", from, to, "BJ", "UNKNOWN").cities().getFirst().contactedStores()).isZero();
        assertThat(store.read("UNSYNCED", from, to, null, null).syncedAt()).isNull();
        jdbc.update("UPDATE bi_sales_contact_snapshot SET business_links_ready=0");
        assertThat(store.read("T", from, to, "BJ", "E1").cities()).isEmpty();
        assertThat(store.read("T", from, to, "BJ", null).cities().getFirst().crmLinkedStores()).isNull();
    }
}
