package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.NearbyModels;
import com.rigour.merchant.api.v1.model.StoreModels.Query;
import com.rigour.shared.core.exception.RequestValidationException;
import java.util.*;
import org.junit.jupiter.api.*;

class NearbyStoreQueryTest {
    JdbcStorePoolStoreTest f;
    final NearbyModels.Origin origin=new NearbyModels.Origin(120.12,30.12,120.1234567,30.1234567);
    @BeforeAll static void setup(){JdbcStorePoolStoreTest.setup();}
    @AfterAll static void close(){JdbcStorePoolStoreTest.stopContainer();}
    @BeforeEach void init(){f=new JdbcStorePoolStoreTest();f.tenant();}
    Query query(String view,int size){return new Query(view,null,null,null,null,null,null,null,null,null,null,size);}
    void point(String id,Double lng,Double lat,String system){f.jdbc.update("UPDATE crm_store_master SET longitude=?,latitude=?,coordinate_system=? WHERE tenant_id=? AND id=?",lng,lat,system,f.tenant,id);}
    @Test void indexedNearestPagingPreservesTiesExpandsRangeAndPlacesUnknownLast(){
        String a=f.create("330105","店甲"),b=f.create("330105","店乙"),far=f.create("330105","店远"),unknown=f.create("330105","店未知"),outside=f.create("320102","越权最近店");
        point(far,120.5,30.5,"GCJ02");point(unknown,null,null,null);
        var scope=f.hangzhou("crm:pool:public-read","2");var ids=new ArrayList<String>();String cursor=null;
        for(int i=0;i<4;i++){
            var page=f.store.nearby(scope,query("PUBLIC",1),Set.of(),origin,cursor);
            assertThat(page.total()).isEqualTo(4);assertThat(page.items()).hasSize(1);
            var row=page.items().getFirst();ids.add(row.store().id());
            if(i==3){assertThat(row.distanceMeters()).isNull();assertThat(page.nextCursor()).isNull();}
            else {assertThat(row.distanceMeters()).isNotNull();assertThat(page.nextCursor()).isNotNull();}
            cursor=page.nextCursor();
        }
        assertThat(ids).containsExactly(a,b,far,unknown).doesNotContain(outside);
    }
    @Test void wgs84UsesOriginalGpsPointAndChangedAnchorRejectsCursor(){
        String gps=f.create("330105","GPS店"),gcj=f.create("330105","高德店");point(gps,120.12,30.12,"WGS84");
        var scope=f.hangzhou("crm:pool:public-read","2");var page=f.store.nearby(scope,query("PUBLIC",1),Set.of(),origin,null);
        assertThat(page.items().getFirst().distanceMeters()).isZero();
        assertThatThrownBy(()->f.store.nearby(scope,query("PUBLIC",1),Set.of(),new NearbyModels.Origin(121,31,121.005,31.002),page.nextCursor())).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->f.store.nearby(scope,query("PUBLIC",1),Set.of(),origin,"invalid")).isInstanceOf(RequestValidationException.class);
    }
    @Test void privateAndCooperatedListsKeepTheirOwnMembership(){
        String mine=f.create("330105","我的"),other=f.create("330105","他人"),publicId=f.create("330105","公海");
        f.claim(mine,f.hangzhou("crm:pool:claim","2"));f.claim(other,f.hangzhou("crm:pool:claim","3"));
        var scope=f.hangzhou("crm:pool:private-read","2");
        assertThat(f.store.nearby(scope,query("MINE",20),Set.of(),origin,null).items()).extracting(i->i.store().id()).containsExactly(mine);
        f.jdbc.update("INSERT INTO crm_store_order_fact(tenant_id,order_id,store_id,ownership_id,salesperson_id,source_sequence,effective_at,valid_order,pending_authorization,observed_at,snapshot_json) VALUES(?,1,?,1,'2',1,UTC_TIMESTAMP(6),1,0,UTC_TIMESTAMP(6),'{}')",f.tenant,other);
        var workbench=new JdbcCustomerWorkbenchStore(f.jdbc);
        var result=workbench.nearby(scope,"COOPERATED",java.time.Instant.EPOCH,java.time.Instant.now(),null,null,null,20,origin);
        assertThat(result.items()).singleElement().satisfies(i->{assertThat(i.store().id()).isEqualTo(other);assertThat(i.store().relation()).isEqualTo("TRANSFERRED");assertThat(i.distanceMeters()).isZero();});
        assertThat(workbench.nearby(scope,"COOPERATED",java.time.Instant.EPOCH,java.time.Instant.now(),null,"MINE",null,20,origin).total()).isZero();
    }
    @Test void smallScopeAvoidsRepeatedExpansionAndStaleCountCannotTruncateResults(){
        String a=f.create("330105","近店"),b=f.create("330105","远店"),unknown=f.create("330105","无坐标店");
        point(b,121.5,31.5,"GCJ02");point(unknown,null,null,null);
        var measured=org.mockito.Mockito.spy(f.jdbc);
        String from=" FROM crm_store_summary q JOIN crm_store_master s ON s.tenant_id=q.tenant_id AND s.id=q.store_id";
        var args=List.<Object>of(f.tenant);
        var first=NearbyStoreQuery.search(measured,from,"q.tenant_id=?",args,origin,null,1,1);
        assertThat(first.hits()).extracting(NearbyStoreQuery.Hit::id).containsExactly(a);
        org.mockito.Mockito.verify(measured,org.mockito.Mockito.atMost(3)).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<NearbyStoreQuery.Hit>>any(),org.mockito.ArgumentMatchers.<Object[]>any());
        var second=NearbyStoreQuery.search(f.jdbc,from,"q.tenant_id=?",args,origin,first.nextCursor(),1,1);
        assertThat(second.hits()).extracting(NearbyStoreQuery.Hit::id).containsExactly(b);
        var third=NearbyStoreQuery.search(f.jdbc,from,"q.tenant_id=?",args,origin,second.nextCursor(),1,1);
        assertThat(third.hits()).extracting(NearbyStoreQuery.Hit::id).containsExactly(unknown);
        assertThat(third.nextCursor()).isNull();
    }
    @Test void nearbyCacheSeparatesLocationAndClaimInvalidatesBothPools(){
        String first=f.create("330105","缓存近店"),second=f.create("330106","缓存另店");
        var observed=org.mockito.Mockito.spy(new org.springframework.jdbc.core.JdbcTemplate(f.jdbc.getDataSource()));
        var cached=new JdbcStorePoolStore(observed);
        var values=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
        var backend=new com.rigour.shared.cache.CacheStore(){
            public java.util.Optional<byte[]> get(String t,String k){return java.util.Optional.ofNullable(values.get(t+k));}
            public void put(String t,String k,byte[] v,java.time.Duration ttl){values.put(t+k,v);}
            public void evict(String t,String k){values.remove(t+k);}
            public boolean tryLock(String t,String k,String token,java.time.Duration ttl){return true;}
            public void unlock(String t,String k,String token){}
        };
        var versions=new com.rigour.merchant.infrastructure.cache.JdbcCacheVersions(f.jdbc);
        var cache=new com.rigour.shared.cache.QueryCache(backend,versions,()->"verified-test-actor",()->false,null,"nearby-test",2097152,4);
        org.springframework.test.util.ReflectionTestUtils.setField(cached,"queryCache",cache);
        var scope=f.hangzhou("crm:pool:public-read","2");
        var page=f.inTx(()->cached.nearby(scope,query("PUBLIC",20),Set.of(),origin,null));
        org.mockito.Mockito.clearInvocations(observed);
        assertThat(f.inTx(()->cached.nearby(scope,query("PUBLIC",20),Set.of(),origin,null))).isEqualTo(page);
        org.mockito.Mockito.verifyNoInteractions(observed);
        var moved=new NearbyModels.Origin(121,31,121.005,31.002);
        assertThat(f.inTx(()->cached.nearby(scope,query("PUBLIC",20),Set.of(),moved,null)).items().getFirst().distanceMeters()).isNotEqualTo(page.items().getFirst().distanceMeters());
        var claimer=f.hangzhou("crm:pool:claim","2");
        f.inTx(()->{cached.claim(claimer,first,new com.rigour.merchant.api.v1.model.StoreModels.Claim(UUID.randomUUID().toString(),cached.detail(claimer,first).revision()));return null;});
        assertThat(f.inTx(()->cached.nearby(scope,query("PUBLIC",20),Set.of(),origin,null)).items()).extracting(i->i.store().id()).containsExactly(second);
        assertThat(f.inTx(()->cached.nearby(f.hangzhou("crm:pool:private-read","2"),query("MINE",20),Set.of(),origin,null)).items()).extracting(i->i.store().id()).containsExactly(first);
        assertThat(f.inTx(()->cached.nearby(f.hangzhou("crm:pool:private-read","3"),query("MINE",20),Set.of(),origin,null)).total()).isZero();
    }
    @Test void sphericalBoundsIncludePolesAndDateLine(){
        var ordinary=NearbyStoreQuery.box(120,30,2000);assertThat(ordinary.west()).isLessThan(120);assertThat(ordinary.east()).isGreaterThan(120);
        var polar=NearbyStoreQuery.box(0,89.999,2000);assertThat(polar.west()).isEqualTo(-180);assertThat(polar.east()).isEqualTo(180);
        var wrapped=NearbyStoreQuery.box(179.999,0,2000);assertThat(wrapped.west()).isEqualTo(-180);assertThat(wrapped.east()).isEqualTo(180);
    }
}
