package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.util.UUID;

class TransportFeeArchivePersistenceTest {
    JdbcTemplate db;
    JdbcTransportFeeArchiveStore store;
    TransactionTemplate tx;

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        String ddl =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V77__transport_fee_import_file.sql"))
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace("UNIQUE KEY uk_transport_fee_file", "UNIQUE")
                        .replace(
                                "    KEY idx_transport_fee_file_tenant_created (tenant_id,"
                                        + " created_time)\n",
                                "")
                        .replace("file_hash),", "file_hash)");
        db.execute(ddl);
        store = new JdbcTransportFeeArchiveStore(db);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test
    void repeatsReuseOwnOriginalAndDoNotExposeOtherTenantOrUploader() {
        var a = tx.execute(x -> store.insert("T", "U", "hash", "原件.xlsx", "private/A", 20));
        var repeated =
                tx.execute(x -> store.insert("T", "U", "hash", "另一个名字.xlsx", "private/B", 20));
        assertThat(repeated).isEqualTo(a);
        assertThat(store.find("OTHER", "U", "hash")).isNull();
        assertThat(store.find("T", "OTHER", "hash")).isNull();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM order_transport_fee_import_file",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void archiveMetadataRollsBackWithItsTransaction() {
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        x -> {
                                            store.insert(
                                                    "T", "U", "hash", "原件.xlsx", "private/A", 20);
                                            throw new IllegalStateException("failure");
                                        }))
                .hasMessage("failure");
        assertThat(store.find("T", "U", "hash")).isNull();
    }
}
