package com.rigour.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Map;
import java.util.UUID;

class ExpenseDictionaryMigrationTest {
    @Test
    void reimbursementCategoriesUseTenantKeyAndPreserveExistingTenantValues() {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:expense_dictionary_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(dataSource);
        // V33 established tenant_key for dictionary ownership; tenant_id is not a column.
        jdbc.execute(
                """
                CREATE TABLE data_dictionary_item (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_key VARCHAR(36) NOT NULL DEFAULT 'GLOBAL',
                    dictionary_code VARCHAR(50) NOT NULL,
                    dictionary_item_level INT NOT NULL DEFAULT 1,
                    parent_dictionary_item_code VARCHAR(50),
                    dictionary_item_code VARCHAR(50) NOT NULL,
                    dictionary_item_name VARCHAR(100) NOT NULL,
                    ordinal INT NOT NULL DEFAULT 0,
                    revision INT NOT NULL DEFAULT 1,
                    created_by VARCHAR(50) NOT NULL,
                    updated_by VARCHAR(50),
                    deleted INT NOT NULL DEFAULT 0,
                    UNIQUE (tenant_key, dictionary_code, dictionary_item_code)
                )
                """);
        jdbc.update(
                """
                INSERT INTO data_dictionary_item
                    (tenant_key, dictionary_code, dictionary_item_code,
                     dictionary_item_name, created_by)
                VALUES ('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE','TRAVEL','差旅','SYSTEM'),
                       ('TEST_TENANT','REIMBURSEMENT_EXPENSE_TYPE','FREIGHT','租户运输费用','TEST')
                """);

        new ResourceDatabasePopulator(
                        new ClassPathResource(
                                "db/migration/V42__reimbursement_expense_categories.sql"))
                .execute(dataSource);

        var globalItems =
                jdbc.query(
                        """
                        SELECT dictionary_item_code, dictionary_item_name
                        FROM data_dictionary_item
                        WHERE tenant_key='GLOBAL' AND dictionary_code='REIMBURSEMENT_EXPENSE_TYPE'
                        """,
                        result -> {
                            var items = new java.util.HashMap<String, String>();
                            while (result.next())
                                items.put(result.getString(1), result.getString(2));
                            return items;
                        });
        assertThat(globalItems)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "TRAVEL",
                                "差旅",
                                "FIXED_ASSET",
                                "办公固定资产",
                                "CONSUMABLE",
                                "办公耗材",
                                "NETWORK",
                                "网络费用",
                                "OFFICE_MOVING",
                                "办公搬运",
                                "FREIGHT",
                                "货物运输"));
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT dictionary_item_name FROM data_dictionary_item
                                WHERE tenant_key='TEST_TENANT' AND dictionary_item_code='FREIGHT'
                                """,
                                String.class))
                .isEqualTo("租户运输费用");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_dictionary_item", Integer.class))
                .isEqualTo(7);
        jdbc.execute("SHUTDOWN");
    }
}
