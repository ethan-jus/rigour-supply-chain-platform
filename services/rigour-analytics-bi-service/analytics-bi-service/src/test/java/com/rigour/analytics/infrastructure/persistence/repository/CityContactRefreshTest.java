package com.rigour.analytics.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 在隔离来源表执行刷新 SQL，保证最终口径仅要求已提交和未删除，不依赖微信截图。 */
public class CityContactRefreshTest {
    public static String identity(String value) { return value; }

    @Test void projectsSubmittedVisitsWithoutRequiringScreenshotAndRemovesWithdrawnRows() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:contact_refresh_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE ALIAS UUID_TO_BIN FOR 'com.rigour.analytics.infrastructure.persistence.repository.CityContactRefreshTest.identity'");
        jdbc.execute("CREATE ALIAS BIN_TO_UUID FOR 'com.rigour.analytics.infrastructure.persistence.repository.CityContactRefreshTest.identity'");
        jdbc.execute("CREATE SCHEMA rigour_crm");
        jdbc.execute("CREATE SCHEMA rigour_sales_work");
        jdbc.execute("CREATE TABLE rigour_crm.crm_customer_area (tenant_id VARCHAR, area_code VARCHAR, area_name VARCHAR, parent_area_code VARCHAR, deleted INT, status VARCHAR)");
        jdbc.execute("CREATE TABLE rigour_sales_work.temp_sales_checkin_store (tenant_id VARCHAR, id VARCHAR, city VARCHAR)");
        // 刻意不建任何微信截图列：用户最终口径不应依赖它。
        jdbc.execute("CREATE TABLE rigour_sales_work.temp_sales_checkin_submission (tenant_id VARCHAR, id VARCHAR, store_id VARCHAR, status VARCHAR, deletion_state VARCHAR, submitted_at TIMESTAMP, review_status VARCHAR, salesperson_id VARCHAR DEFAULT 'P1')");
        jdbc.execute("CREATE TABLE rigour_sales_work.temp_sales_checkin_employee_link (tenant_id VARCHAR, salesperson_id VARCHAR, employee_code VARCHAR)");
        jdbc.execute("CREATE TABLE rigour_sales_work.temp_sales_checkin_customer_link (tenant_id VARCHAR, store_id VARCHAR, customer_id BIGINT, customer_code VARCHAR)");
        jdbc.execute("CREATE TABLE bi_sales_contact_fact (tenant_id VARCHAR, submission_id VARCHAR, store_id VARCHAR, city_name VARCHAR, region_code VARCHAR, submitted_at TIMESTAMP, review_status VARCHAR, salesperson_id VARCHAR, owner_staff_code VARCHAR, customer_id BIGINT, customer_code VARCHAR)");
        jdbc.execute("CREATE TABLE bi_sales_contact_snapshot (tenant_id VARCHAR, synced_time TIMESTAMP, business_links_ready INT)");
        jdbc.execute("CREATE TABLE bi_sales_contact_city_dim (tenant_id VARCHAR, region_code VARCHAR, city_name VARCHAR)");
        jdbc.update("INSERT INTO rigour_crm.crm_customer_area VALUES ('T','REGION','华北',NULL,0,'ACTIVE'),('T','BJ','北京','REGION',0,'ACTIVE')");
        jdbc.update("INSERT INTO rigour_sales_work.temp_sales_checkin_store VALUES ('T','S1','北京'),('T','S2','北京'),('OTHER','S1','北京')");
        jdbc.update("INSERT INTO rigour_sales_work.temp_sales_checkin_submission (tenant_id,id,store_id,status,deletion_state,submitted_at,review_status) VALUES ('T','V1','S1','SUBMITTED','NONE','2026-09-01','PENDING'),('T','V2','S1','SUBMITTED','NONE','2026-09-02','PENDING'),('T','V3','S2','DRAFT','NONE','2026-09-01','PENDING'),('T','V4','S2','SUBMITTED','NONE','2026-09-02','FLAGGED'),('T','V5','S2','SUBMITTED','DELETED','2026-09-02','PENDING'),('OTHER','V6','S1','SUBMITTED','NONE','2026-09-02','PENDING')");
        jdbc.update("INSERT INTO rigour_sales_work.temp_sales_checkin_employee_link VALUES ('T','P1','E1'),('OTHER','P1','E9')");
        jdbc.update("INSERT INTO rigour_sales_work.temp_sales_checkin_customer_link VALUES ('T','S1',1,'C1'),('OTHER','S2',9,'C9')");
        jdbc.update("UPDATE rigour_sales_work.temp_sales_checkin_submission SET salesperson_id='UNLINKED' WHERE id='V4'");
        var store = new JdbcCityContactAnalyticsStore(ds);
        var now = Instant.parse("2026-09-15T00:00:00Z");
        assertThat(store.refresh("T", now).pulledCount()).isEqualTo(3);
        var result = store.read("T", Instant.parse("2026-09-01T00:00:00Z"), now, "BJ", null);
        assertThat(result.businessLinksReady()).isTrue();
        assertThat(result.cities()).singleElement().satisfies(city -> {
            assertThat(city.contactedStores()).isEqualTo(2);
            assertThat(city.flaggedStores()).isEqualTo(1);
            assertThat(city.crmLinkedStores()).isEqualTo(1);
            assertThat(city.crmUnlinkedStores()).isEqualTo(1);
        });
        assertThat(store.read("T", Instant.EPOCH, now, "BJ", "E1").cities().getFirst().contactedStores()).isEqualTo(1);
        assertThat(store.read("T", Instant.EPOCH, now, "BJ", "E9").cities().getFirst().contactedStores()).isZero();
        assertThat(jdbc.queryForObject("SELECT customer_code FROM bi_sales_contact_fact WHERE submission_id='V1'", String.class)).isEqualTo("C1");
        assertThat(jdbc.queryForObject("SELECT customer_code FROM bi_sales_contact_fact WHERE submission_id='V4'", String.class)).isNull();
        jdbc.update("UPDATE rigour_sales_work.temp_sales_checkin_submission SET deletion_state='DELETED' WHERE id='V4'");
        store.refresh("T", now.plusSeconds(1));
        assertThat(store.read("T", Instant.EPOCH, now, "BJ", null).cities().getFirst().contactedStores()).isEqualTo(1);
    }
}
