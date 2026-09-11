package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 从真实 V22 数据升级，验证旧照片来源未知、原同意值保留及照片租户外键。 */
@Testcontainers(disabledWithoutDocker=true)
class TemporaryCheckinV23MigrationTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work").withUsername("photo_test").withPassword("photo_test_password");

    @Test void migratesLegacyPhotoWithoutInventingEvidenceAndAllowsUnacceptedNewRecords() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).target("22").load().migrate();
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        TemporaryCheckinV20MigrationTests.seedV19Catalog(jdbc);
        TemporaryCheckinV20MigrationTests.insertLegacySubmission(jdbc);
        jdbc.update("UPDATE temp_sales_checkin_submission SET storefront_photo_object_key='legacy/photo.jpg',"
                +"storefront_photo_content_type='image/jpeg',storefront_photo_size_bytes=4,storefront_photo_sha256=?,"
                +"storefront_photo_original_filename='门头.jpg'","a".repeat(64));
        var result=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).target("23").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        var photo=jdbc.queryForMap("SELECT *,BIN_TO_UUID(photo_id) AS stable_id FROM temp_sales_checkin_photo");
        assertThat(photo.get("stable_id")).isEqualTo("40000000-0000-0000-0000-000000000001");
        assertThat(photo.get("object_key")).isEqualTo("legacy/photo.jpg");
        assertThat(photo.get("capture_source")).isNull();
        assertThat(photo.get("uploaded_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT privacy_accepted FROM temp_sales_checkin_submission",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE temp_sales_checkin_submission SET privacy_accepted=0");
        assertThatThrownBy(()->jdbc.update("UPDATE temp_sales_checkin_submission SET privacy_accepted=2"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE temp_sales_checkin_photo SET tenant_id=UUID_TO_BIN(UUID())"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("DELETE FROM temp_sales_checkin_submission");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_photo",Integer.class)).isZero();
    }
}
