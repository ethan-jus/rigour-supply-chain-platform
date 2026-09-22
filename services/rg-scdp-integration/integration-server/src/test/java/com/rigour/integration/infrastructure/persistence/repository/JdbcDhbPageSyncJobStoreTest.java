package com.rigour.integration.infrastructure.persistence.repository;
import static org.assertj.core.api.Assertions.*;
import com.rigour.integration.api.v1.model.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class JdbcDhbPageSyncJobStoreTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    static JdbcDhbPageSyncJobStore store;
    @BeforeAll static void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V25__dhb_page_sync_jobs.sql")).execute(ds);
        store=new JdbcDhbPageSyncJobStore(new JdbcTemplate(ds),JsonMapper.builder().build());
    }
    DhbPageSyncCommand command(UUID c){return new DhbPageSyncCommand(DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE,c,null,null,500,true);}
    @Test void concurrentSubmissionsShareOneDurableActiveJob() throws Exception {
        UUID tenant=UUID.randomUUID(),connector=UUID.randomUUID();
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<Future<DhbPageSyncJob>>();
            for(int i=0;i<8;i++) tasks.add(pool.submit(()->{gate.await();return store.reserve(tenant,UUID.randomUUID(),command(connector),Instant.now());}));
            gate.countDown();
            var ids=new HashSet<UUID>();
            for(var task:tasks)ids.add(task.get(10,TimeUnit.SECONDS).jobId());
            assertThat(ids).hasSize(1);
            UUID id=ids.iterator().next();
            assertThat(store.claim(tenant,id,Instant.now())).isTrue();
            assertThat(store.claim(tenant,id,Instant.now())).isFalse();
            assertThat(store.find(UUID.randomUUID(),id)).isEmpty();
        }
    }
    @Test void terminalResultSurvivesNewStoreAndAllowsNextJobButUnknownDoesNot() {
        UUID tenant=UUID.randomUUID(),connector=UUID.randomUUID(),id=UUID.randomUUID();
        Instant now=Instant.now();
        store.reserve(tenant,id,command(connector),now);store.claim(tenant,id,now);
        store.finish(tenant,id,"UNKNOWN","暂不可确认",null,now);
        assertThat(store.reserve(tenant,UUID.randomUUID(),command(connector),now).jobId()).isEqualTo(id);
        var result=new DhbSyncOrchestrationResult(id,"SUCCEEDED","MANUAL",now,now,List.of());
        store.finish(tenant,id,"SUCCEEDED","结束",result,now);
        var reread=new JdbcDhbPageSyncJobStore(new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())),JsonMapper.builder().build());
        assertThat(reread.find(tenant,id).orElseThrow().result().batchId()).isEqualTo(id);
        UUID next=UUID.randomUUID();
        assertThat(store.reserve(tenant,next,command(connector),now).jobId()).isEqualTo(next);
        assertThat(store.reserve(tenant,id,command(connector),now).status()).isEqualTo("SUCCEEDED");
    }
}
