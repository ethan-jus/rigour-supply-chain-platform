package com.rigour.sales.infrastructure.cache;

import com.rigour.sales.infrastructure.persistence.*;
import com.rigour.shared.cache.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SalesPolicyCacheTest {
    final UUID tenant=UUID.randomUUID(),profile=UUID.randomUUID(),city=UUID.randomUUID(),policy=UUID.randomUUID();
    final Instant now=Instant.parse("2026-11-13T12:00:00Z");
    DriverManagerDataSource source; JdbcTemplate jdbc;
    JdbcSalesWorkQueryRepository query; JdbcSalesVisitPolicyReadRepository snapshots;
    Map<String,byte[]> values;
    @BeforeEach void setup() {
        source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=spy(new JdbcTemplate(source));
        jdbc.execute("CREATE TABLE sales_visit_policy(id BINARY(16),tenant_id BINARY(16),policy_code VARCHAR(30),policy_name VARCHAR(100))");
        jdbc.execute("""
            CREATE TABLE sales_visit_policy_version(id BINARY(16),tenant_id BINARY(16),policy_id BINARY(16),version_no INT,
            publish_status VARCHAR(30),require_assigned_target BOOLEAN DEFAULT FALSE,allow_prospect_target BOOLEAN DEFAULT FALSE,
            check_in_radius_meters INT,minimum_dwell_minutes INT DEFAULT 0,required_photo_count INT DEFAULT 1,
            recording_enabled BOOLEAN DEFAULT FALSE,minimum_recording_seconds INT DEFAULT 0,maximum_clip_gap_seconds INT DEFAULT 30,
            capture_rules_json VARCHAR(2000),ai_asr_enabled BOOLEAN DEFAULT FALSE,ai_relevance_enabled BOOLEAN DEFAULT FALSE,
            ai_duplicate_enabled BOOLEAN DEFAULT FALSE,ai_auto_confirm_threshold DECIMAL(10,2),effective_from TIMESTAMP,effective_to TIMESTAMP)
            """);
        jdbc.execute("CREATE TABLE sales_policy_scope(tenant_id BINARY(16),policy_type VARCHAR(20),policy_version_id BINARY(16),status VARCHAR(20),effective_from TIMESTAMP,effective_to TIMESTAMP,scope_type VARCHAR(20),scope_id BINARY(16))");
        jdbc.execute("CREATE TABLE sales_team_member(tenant_id BINARY(16),team_id BINARY(16),sales_profile_id BINARY(16),status VARCHAR(20),effective_from TIMESTAMP,effective_to TIMESTAMP)");
        jdbc.update("INSERT INTO sales_visit_policy VALUES(?,?,?,?)",bin(policy),bin(tenant),"APP","拜访规则");
        values=new HashMap<>();
        CacheStore store=new CacheStore() {
            public Optional<byte[]> get(String t,String k){return Optional.ofNullable(values.get(t+k));}
            public void put(String t,String k,byte[] v,Duration ttl){values.put(t+k,v);}
            public void evict(String t,String k){values.remove(t+k);}
            public boolean tryLock(String t,String k,String token,Duration ttl){return true;}
            public void unlock(String t,String k,String token){}
        };
        var cache=new QueryCache(store,QueryCacheConfiguration.immutableVersions(),()->"authorized-test",()->false,null,"policy-test",100000,4);
        query=new JdbcSalesWorkQueryRepository(jdbc);snapshots=new JdbcSalesVisitPolicyReadRepository(jdbc,JsonMapper.builder().build());
        ReflectionTestUtils.setField(query,"queryCache",cache);ReflectionTestUtils.setField(snapshots,"queryCache",cache);
    }
    @AfterEach void close(){jdbc.execute("DROP ALL OBJECTS");}
    byte[] bin(UUID id){return SalesUuidCodec.encode(id);}
    UUID publish(int number,int radius,Instant from) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO sales_visit_policy_version(id,tenant_id,policy_id,version_no,publish_status,check_in_radius_meters,effective_from,capture_rules_json) VALUES(?,?,?,?,?,?,?,?)",
            bin(id),bin(tenant),bin(policy),number,"PUBLISHED",radius,java.sql.Timestamp.from(from),"{\"requireLocationOnOpen\":true,\"maximumLocationAccuracyMeters\":100,\"automaticRecording\":true,\"automaticCheckout\":false,\"allowPhotoImport\":false,\"allowRecordingImport\":false}");return id;
    }
    long reads(String match){return mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")&&i.getArguments()[1] instanceof org.springframework.jdbc.core.RowMapper&&i.getArguments()[0].toString().contains(match)).count();}
    @Test void versionContentIsLoadedOnceAndRestoredFromSharedCache() {
        UUID id=publish(1,200,now.minusSeconds(60));
        assertThat(snapshots.snapshot(tenant,id).radiusMeters()).isEqualTo(200);
        assertThat(snapshots.snapshot(tenant,id).captureRules().automaticRecording()).isTrue();
        assertThat(reads("SELECT * FROM sales_visit_policy_version")).isEqualTo(1);
        assertThat(query.findVisitPolicy(tenant,id)).isEqualTo(query.findVisitPolicy(tenant,id));
        assertThat(reads("SELECT fpv.id, fp.policy_code")).isEqualTo(1);
    }
    @Test void newPublicationSelectsNewVersionWithoutOverwritingHistoricalPolicy() {
        UUID old=publish(1,200,now.minusSeconds(60));assertThat(query.findActiveVisitPolicy(tenant,profile,city,now).orElseThrow().id()).isEqualTo(old);
        snapshots.snapshot(tenant,old);UUID next=publish(2,500,now);
        assertThat(query.findActiveVisitPolicy(tenant,profile,city,now).orElseThrow().id()).isEqualTo(next);
        assertThat(snapshots.snapshot(tenant,next).radiusMeters()).isEqualTo(500);
        assertThat(snapshots.snapshot(tenant,old).radiusMeters()).isEqualTo(200);
    }
    @Test void scopeChangesAreCheckedEvenWithWarmRuleContents() {
        UUID id=publish(1,200,now.minusSeconds(60));query.findActiveVisitPolicy(tenant,profile,city,now);
        jdbc.update("INSERT INTO sales_policy_scope VALUES(?,?,?,?,?,NULL,?,?)",bin(tenant),"VISIT",bin(id),"ACTIVE",java.sql.Timestamp.from(now.minusSeconds(1)),"EMPLOYEE",bin(UUID.randomUUID()));
        assertThat(query.findActiveVisitPolicy(tenant,profile,city,now)).isEmpty();
    }
    @Test void scheduledEffectiveTimeAndTenantRemainPartOfSelection() {
        UUID old=publish(1,200,now.minusSeconds(60)),future=publish(2,100,now.plusSeconds(60));
        assertThat(query.findActiveVisitPolicy(tenant,profile,city,now).orElseThrow().id()).isEqualTo(old);
        assertThat(query.findActiveVisitPolicy(tenant,profile,city,now.plusSeconds(61)).orElseThrow().id()).isEqualTo(future);
        assertThat(snapshots.snapshot(UUID.randomUUID(),old)).isNull();
    }
    @Test void writeTransactionBypassesCacheAndRollbackCannotPollutePublishedSnapshot() {
        UUID id=publish(1,200,now.minusSeconds(60));snapshots.snapshot(tenant,id);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.execute(status->{jdbc.update("UPDATE sales_visit_policy_version SET check_in_radius_meters=999 WHERE id=?",bin(id));
            assertThat(snapshots.snapshot(tenant,id).radiusMeters()).isEqualTo(999);status.setRollbackOnly();return null;});
        assertThat(snapshots.snapshot(tenant,id).radiusMeters()).isEqualTo(200);
    }
}
