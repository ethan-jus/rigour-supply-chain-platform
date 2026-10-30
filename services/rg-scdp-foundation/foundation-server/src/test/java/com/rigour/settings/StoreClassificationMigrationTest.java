package com.rigour.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class StoreClassificationMigrationTest {
    @Test
    void seedsHierarchyWithoutChangingTenantDictionary() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:store_types_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE data_dictionary (
                  tenant_key VARCHAR(36), dictionary_code VARCHAR(50), remark VARCHAR(500),
                  allow_new_items BOOLEAN DEFAULT FALSE, revision INT DEFAULT 1,
                  updated_by VARCHAR(50), deleted INT DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE data_dictionary_item (
                  tenant_key VARCHAR(36), dictionary_code VARCHAR(50), dictionary_item_level INT,
                  parent_dictionary_item_code VARCHAR(50), dictionary_item_code VARCHAR(50),
                  dictionary_item_name VARCHAR(100), remark VARCHAR(500), ordinal INT, revision INT,
                  created_by VARCHAR(50), updated_by VARCHAR(50), deleted INT,
                  UNIQUE(tenant_key,dictionary_code,dictionary_item_code),
                  UNIQUE(tenant_key,dictionary_code,parent_dictionary_item_code,dictionary_item_name))
                """);
        jdbc.update("INSERT INTO data_dictionary(tenant_key,dictionary_code,remark) VALUES"
                + "('GLOBAL','CRM_STORE_TYPE','旧说明'),('TENANT','CRM_STORE_TYPE','租户说明')");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V47__crm_store_industry_classifications.sql")).execute(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_dictionary_item", Integer.class)).isEqualTo(242);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_dictionary_item WHERE dictionary_item_level=3", Integer.class)).isEqualTo(141);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM data_dictionary_item c LEFT JOIN data_dictionary_item p
                ON p.dictionary_item_code=c.parent_dictionary_item_code
                AND p.tenant_key=c.tenant_key AND p.dictionary_code=c.dictionary_code
                WHERE c.dictionary_item_level>1 AND
                (p.dictionary_item_code IS NULL OR p.dictionary_item_level<>c.dictionary_item_level-1)
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT dictionary_item_name FROM data_dictionary_item WHERE dictionary_item_code='POI_080113'", String.class)).isEqualTo("台球厅");
        assertThat(jdbc.queryForObject("SELECT allow_new_items FROM data_dictionary WHERE tenant_key='GLOBAL'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT remark FROM data_dictionary WHERE tenant_key='TENANT'", String.class)).isEqualTo("租户说明");
        jdbc.execute("SHUTDOWN");
    }
}
