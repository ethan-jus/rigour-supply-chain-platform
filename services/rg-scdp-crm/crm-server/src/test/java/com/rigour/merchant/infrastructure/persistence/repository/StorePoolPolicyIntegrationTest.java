package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StorePoolPolicyModels.*;
import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.domain.store.OwnershipProtection;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.*;

class StorePoolPolicyIntegrationTest {
    JdbcStorePoolStoreTest f;
    @BeforeAll static void setup(){JdbcStorePoolStoreTest.setup();}
    @AfterAll static void stop(){JdbcStorePoolStoreTest.stopContainer();}
    @BeforeEach void init(){f=new JdbcStorePoolStoreTest();f.tenant();}
    Publish command(int version){return new Publish(version,UUID.randomUUID().toString(),36,11,90,12,120,6,"测试规则发布");}
    Policy publish(Publish c){return f.inTx(()->f.store.publishPolicy(f.national("crm:store:manage","1"),c));}
    @Test void publicationPreservesOldOwnershipAndNewClaimUsesConfiguredDurations(){
        String old=f.create("330105","旧归属"),fresh=f.create("330105","新归属");
        var owner=f.hangzhou("crm:pool:claim","2");f.claim(old,owner);
        var before=f.jdbc.queryForMap("SELECT policy_id,deadline_at FROM crm_store_ownership WHERE tenant_id=? AND store_id=?",f.tenant,old);
        var policy=publish(command(1));f.claim(fresh,owner);
        assertThat(f.jdbc.queryForMap("SELECT policy_id,deadline_at FROM crm_store_ownership WHERE tenant_id=? AND store_id=?",f.tenant,old)).isEqualTo(before);
        assertThat(f.jdbc.queryForObject("SELECT TIMESTAMPDIFF(HOUR,started_at,deadline_at) FROM crm_store_ownership WHERE tenant_id=? AND store_id=?",Integer.class,f.tenant,fresh)).isEqualTo(36);
        var lifecycle=new JdbcStoreLifecycleStore(f.jdbc,f.store);var cycle=lifecycle.current(f.tenant,fresh);
        assertThat(cycle.policy()).isEqualTo(new OwnershipProtection.Policy(36,11,90,12));
        Instant now=Instant.now();
        assertThat(OwnershipProtection.calculate(cycle.policy(),now,now.plusSeconds(10),null).deadline()).isEqualTo(now.plusSeconds(10+11*86400));
        assertThat(OwnershipProtection.calculate(cycle.policy(),now,now.plusSeconds(10),now.plusSeconds(20)).deadline()).isEqualTo(now.plusSeconds(20+90*86400));
        f.inTx(()->{f.store.release(owner,fresh,new Release(UUID.randomUUID().toString(),f.store.detail(owner,fresh).revision(),"测试退回"));return null;});
        assertThat(f.jdbc.queryForObject("SELECT TIMESTAMPDIFF(HOUR,ended_at,cooldown_until) FROM crm_store_ownership WHERE tenant_id=? AND store_id=?",Integer.class,f.tenant,fresh)).isEqualTo(12);
        assertThat(f.store.policyHistory(f.tenant)).extracting(Policy::version).containsExactly(2,1);
        assertThat(policy.publishedBy()).isNotBlank();
    }
    @Test void optimisticVersionReplayPermissionAndTenantIsolation(){
        var c=command(1);var published=publish(c);assertThat(publish(c)).isEqualTo(published);
        assertThatThrownBy(()->publish(command(1))).hasMessageContaining("其他管理员");
        assertThatThrownBy(()->publish(new Publish(1,c.requestId(),37,11,90,12,120,6,c.description()))).hasMessageContaining("请求标识");
        assertThatThrownBy(()->f.inTx(()->f.store.publishPolicy(f.hangzhou("crm:pool:claim","2"),command(2)))).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        assertThat(f.store.currentPolicy(UUID.randomUUID().toString())).isNull();
        assertThat(f.store.policyHistory(UUID.randomUUID().toString())).isEmpty();
    }
    @Test void rollbackDoesNotPublishAndCurrentFiltersFollowConfiguredThresholds(){
        assertThatThrownBy(()->f.inTx(()->{f.store.publishPolicy(f.national("crm:store:manage","1"),command(1));throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(f.store.currentPolicy(f.tenant).version()).isEqualTo(1);
        String id=f.create("330105","提醒与活跃");var owner=f.hangzhou("crm:pool:claim","2");f.claim(id,owner);
        f.jdbc.update("UPDATE crm_store_summary SET deadline_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 12 HOUR),cooperation_status='COOPERATED',last_order_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 90 DAY),order_history_complete_to=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY) WHERE tenant_id=? AND store_id=?",f.tenant,id);
        var due=new Query("MINE",null,null,null,null,null,null,null,null,null,null,20,null,null,null,null,"DUE_SOON");
        assertThat(f.store.list(owner,due).total()).isEqualTo(1);
        assertThat(f.store.detail(owner,id).activityStatus()).isEqualTo("DORMANT");
        publish(command(1));
        assertThat(f.store.list(owner,due).total()).isZero();
        assertThat(f.store.detail(owner,id).activityStatus()).isEqualTo("ACTIVE");
    }

    @Test void sharedCachePublicationInvalidatesAfterCommitAndRollbackKeepsVersion(){
        var cached=new JdbcStorePoolStore(f.jdbc);
        var values=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
        var backend=new com.rigour.shared.cache.CacheStore(){
            public Optional<byte[]> get(String tenant,String key){return Optional.ofNullable(values.get(tenant+key));}
            public void put(String tenant,String key,byte[] value,Duration ttl){values.put(tenant+key,value);}
            public void evict(String tenant,String key){values.remove(tenant+key);}
            public boolean tryLock(String tenant,String key,String token,Duration ttl){return true;}
            public void unlock(String tenant,String key,String token){}
        };
        var versions=new com.rigour.merchant.infrastructure.cache.JdbcCacheVersions(f.jdbc);
        var cache=new com.rigour.shared.cache.QueryCache(backend,versions,()->"authorized-test",()->false,null,"pool-policy-test",2097152,4);
        org.springframework.test.util.ReflectionTestUtils.setField(cached,"queryCache",cache);
        var current=f.inTx(()->cached.currentPolicy(f.tenant));assertThat(values).isNotEmpty();
        long before=versions.current(f.tenant);
        assertThatThrownBy(()->f.inTx(()->{cached.publishPolicy(f.national("crm:store:manage","1"),command(1));throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(versions.current(f.tenant)).isEqualTo(before);
        assertThat(f.inTx(()->cached.currentPolicy(f.tenant))).isEqualTo(current);
        f.inTx(()->cached.publishPolicy(f.national("crm:store:manage","1"),command(1)));
        assertThat(versions.current(f.tenant)).isEqualTo(before+1);
        assertThat(f.inTx(()->cached.currentPolicy(f.tenant)).firstVisitHours()).isEqualTo(36);
        assertThat(f.inTx(()->cached.currentPolicy(UUID.randomUUID().toString()))).isNull();
    }
}
