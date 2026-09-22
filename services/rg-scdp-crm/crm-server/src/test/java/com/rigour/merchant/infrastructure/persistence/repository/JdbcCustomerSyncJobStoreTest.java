package com.rigour.merchant.infrastructure.persistence.repository;
import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class JdbcCustomerSyncJobStoreTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @Test void durableDedupTenantIsolationAndTerminalRelease() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V26__customer_sync_jobs.sql")).execute(ds);
        var store=new JdbcCustomerSyncJobStore(new JdbcTemplate(ds),JsonMapper.builder().build());
        UUID tenant=UUID.randomUUID(),connector=UUID.randomUUID(),id=UUID.randomUUID();
        Instant now=Instant.now();
        assertThat(store.reserve(tenant,id,connector,now).jobId()).isEqualTo(id);
        assertThat(store.reserve(tenant,UUID.randomUUID(),connector,now).jobId()).isEqualTo(id);
        assertThat(store.claim(tenant,id,now)).isTrue();
        assertThat(store.claim(tenant,id,now)).isFalse();
        assertThat(store.find(UUID.randomUUID(),id)).isEmpty();
        store.update(tenant,id,"RUNNING","客户已核对 200 / 401 条",null,now);
        assertThat(store.find(tenant,id).orElseThrow().stage()).contains("200 / 401");
        store.update(tenant,id,"SUCCEEDED","结束",new SyncResult(id,"SUCCEEDED",List.of()),now);
        var restarted=new JdbcCustomerSyncJobStore(new JdbcTemplate(ds),JsonMapper.builder().build());
        assertThat(restarted.find(tenant,id).orElseThrow().result().status()).isEqualTo("SUCCEEDED");
        UUID next=UUID.randomUUID();
        assertThat(restarted.reserve(tenant,next,connector,now).jobId()).isEqualTo(next);
    }
}
