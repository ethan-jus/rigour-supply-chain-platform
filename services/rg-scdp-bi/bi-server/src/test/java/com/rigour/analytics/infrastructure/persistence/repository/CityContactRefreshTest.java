package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

/** 在隔离的领域镜像上执行刷新 SQL，验证租户隔离、固定关联和来源撤销后的投影替换。 */
public class CityContactRefreshTest {
    public static String identity(String value) {
        return value;
    }

    @Test
    void projectsTenantDomainSnapshotAndRemovesWithdrawnRows() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:contact_refresh_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE ALIAS UUID_TO_BIN FOR"
                    + " 'com.rigour.analytics.infrastructure.persistence.repository.CityContactRefreshTest.identity'");
        jdbc.execute(
                "CREATE ALIAS BIN_TO_UUID FOR"
                    + " 'com.rigour.analytics.infrastructure.persistence.repository.CityContactRefreshTest.identity'");
        jdbc.execute(
                "CREATE TABLE bi_source_crm_crm_customer_area (tenant_id VARCHAR, area_code"
                    + " VARCHAR, area_name VARCHAR, parent_area_code VARCHAR, deleted INT, status"
                    + " VARCHAR)");
        // 刻意不建任何微信截图列：用户最终口径不应依赖它。
        jdbc.execute(
                "CREATE TABLE bi_sales_contact_fact (tenant_id VARCHAR, submission_id VARCHAR,"
                    + " store_id VARCHAR, city_name VARCHAR, region_code VARCHAR, submitted_at"
                    + " TIMESTAMP, review_status VARCHAR, salesperson_id VARCHAR, owner_staff_code"
                    + " VARCHAR, customer_id BIGINT, customer_code VARCHAR)");
        jdbc.execute(
                "CREATE TABLE bi_sales_contact_snapshot (tenant_id VARCHAR, synced_time TIMESTAMP,"
                    + " business_links_ready INT)");
        jdbc.execute(
                "CREATE TABLE bi_sales_contact_city_dim (tenant_id VARCHAR, region_code VARCHAR,"
                    + " city_name VARCHAR)");
        jdbc.update(
                "INSERT INTO bi_source_crm_crm_customer_area VALUES"
                    + " ('T','REGION','华北',NULL,0,'ACTIVE'),('T','BJ','北京','REGION',0,'ACTIVE')");
        jdbc.execute(
                "CREATE TABLE bi_source_sales_sales_submitted_visit(tenant_id VARCHAR,id"
                    + " VARCHAR,store_id VARCHAR,city_name VARCHAR,submitted_at"
                    + " TIMESTAMP,review_status VARCHAR,salesperson_id VARCHAR,owner_staff_code"
                    + " VARCHAR,customer_id BIGINT,customer_code VARCHAR)");
        jdbc.update(
                "INSERT INTO bi_source_sales_sales_submitted_visit VALUES"
                    + " ('T','V1','S1','北京','2026-09-01','PENDING','P1','E1',1,'C1'),('T','V2','S1','北京','2026-09-02','PENDING','P1','E1',1,'C1'),('T','V4','S2','北京','2026-09-02','FLAGGED','UNLINKED',NULL,NULL,NULL),('OTHER','V6','S1','北京','2026-09-02','PENDING','P1','E9',9,'C9')");
        var store = new JdbcCityContactAnalyticsStore(ds);
        var now = Instant.parse("2026-09-15T00:00:00Z");
        assertThat(store.refresh("T", now).pulledCount()).isEqualTo(3);
        var result = store.read("T", Instant.parse("2026-09-01T00:00:00Z"), now, "BJ", null);
        assertThat(result.businessLinksReady()).isTrue();
        assertThat(result.cities())
                .singleElement()
                .satisfies(
                        city -> {
                            assertThat(city.contactedStores()).isEqualTo(2);
                            assertThat(city.flaggedStores()).isEqualTo(1);
                            assertThat(city.crmLinkedStores()).isEqualTo(1);
                            assertThat(city.crmUnlinkedStores()).isEqualTo(1);
                        });
        assertThat(
                        store.read("T", Instant.EPOCH, now, "BJ", "E1")
                                .cities()
                                .getFirst()
                                .contactedStores())
                .isEqualTo(1);
        assertThat(
                        store.read("T", Instant.EPOCH, now, "BJ", "E9")
                                .cities()
                                .getFirst()
                                .contactedStores())
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_code FROM bi_sales_contact_fact WHERE"
                                    + " submission_id='V1'",
                                String.class))
                .isEqualTo("C1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_code FROM bi_sales_contact_fact WHERE"
                                    + " submission_id='V4'",
                                String.class))
                .isNull();
        jdbc.update(
                "DELETE FROM bi_source_sales_sales_submitted_visit WHERE tenant_id='T' AND"
                    + " id='V4'");
        store.refresh("T", now.plusSeconds(1));
        assertThat(
                        store.read("T", Instant.EPOCH, now, "BJ", null)
                                .cities()
                                .getFirst()
                                .contactedStores())
                .isEqualTo(1);
    }
}
