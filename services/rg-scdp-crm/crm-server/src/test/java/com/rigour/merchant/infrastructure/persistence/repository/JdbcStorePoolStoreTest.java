package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.StateConflictException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

class JdbcStorePoolStoreTest {
    static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    static JdbcTemplate jdbc;
    static JdbcStorePoolStore store;
    static TransactionTemplate tx;
    String tenant;
    static final String VERSION="TEST-2026";
    static final AdminDivisionCatalog CATALOG=new AdminDivisionCatalog(VERSION,"isolated test fixture","test-only",List.of(
            new AdminDivisionCatalog.Node("330000","浙江省","PROVINCE",null,"EAST"),
            new AdminDivisionCatalog.Node("330100","杭州市","PREFECTURE","330000",null),
            new AdminDivisionCatalog.Node("330105","拱墅区","COUNTY","330100",null),
            new AdminDivisionCatalog.Node("330106","西湖区","COUNTY","330100",null),
            new AdminDivisionCatalog.Node("330200","宁波市","PREFECTURE","330000",null),
            new AdminDivisionCatalog.Node("330203","海曙区","COUNTY","330200",null),
            new AdminDivisionCatalog.Node("320000","江苏省","PROVINCE",null,"EAST"),
            new AdminDivisionCatalog.Node("320100","南京市","PREFECTURE","320000",null),
            new AdminDivisionCatalog.Node("320102","玄武区","COUNTY","320100",null),
            new AdminDivisionCatalog.Node("110000","北京市","PROVINCE",null,"NORTH"),
            new AdminDivisionCatalog.Node("110101","东城区","COUNTY","110000",null),
            new AdminDivisionCatalog.Node("440000","广东省","PROVINCE",null,"SOUTH"),
            new AdminDivisionCatalog.Node("441900","东莞市","PREFECTURE","440000",null),
            new AdminDivisionCatalog.Node("420000","湖北省","PROVINCE",null,"CENTRAL"),
            new AdminDivisionCatalog.Node("429004","仙桃市","COUNTY","420000",null),
            new AdminDivisionCatalog.Node("620000","甘肃省","PROVINCE",null,"NORTHWEST"),
            new AdminDivisionCatalog.Node("620100","兰州市","PREFECTURE","620000",null),
            new AdminDivisionCatalog.Node("620102","城关区","COUNTY","620100",null)));
    @BeforeAll static void setup(){
        String url=System.getenv("CRM_STORE_TEST_JDBC_URL");
        if(url!=null&&!url.matches("jdbc:mysql://[^/]+/crm_map_test_[a-f0-9]+(?:\\?.*)?"))throw new IllegalArgumentException("Only isolated crm_map_test schemas are allowed");
        if(url==null)MYSQL.start();
        var ds=url==null?new DriverManagerDataSource(MYSQL.getJdbcUrl()+"?connectionTimeZone=UTC",MYSQL.getUsername(),MYSQL.getPassword()):
                new DriverManagerDataSource(url,System.getenv("CRM_STORE_TEST_DB_USER"),System.getenv("CRM_STORE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/bootstrap")
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();
        jdbc=new JdbcTemplate(ds);store=new JdbcStorePoolStore(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @AfterAll static void stopContainer(){if(MYSQL.isRunning())MYSQL.stop();}
    @BeforeEach void tenant(){tenant=UUID.randomUUID().toString();inTx(()->{store.initializePolicy(national("crm:store:manage","1"));return null;});}
    <T>T inTx(Supplier<T> operation){return tx.execute(status->operation.get());}
    StoreAccessView.Grant nationalGrant(){return new StoreAccessView.Grant("ALL",List.of(),false,true,List.of());}
    StoreAccessView.Grant self(String city){return new StoreAccessView.Grant("SELF",List.of("10"),false,false,List.of(new StoreAccessView.Area(VERSION,city)));}
    StoreScope scope(String action,String employee,List<StoreAccessView.Grant> grants,boolean limited,List<StoreAccessView.Area> ceiling,Map<String,List<String>> members){
        return new StoreScope(new StoreAccessView(tenant,"account-"+employee,action,1,employee,"E"+employee,"测试员工"+employee,"10",Set.of(action),grants,limited,ceiling),CATALOG,members);
    }
    StoreScope national(String action,String employee){return scope(action,employee,List.of(nationalGrant()),false,List.of(),Map.of());}
    StoreScope hangzhou(String action,String employee){return scope(action,employee,List.of(self("330100")),false,List.of(),Map.of());}
    Query query(String view,String city){return new Query(view,null,null,null,city,null,null,null,null,null,null,20);}
    String create(String area,String name){
        var admin=national("crm:lead:review","1");
        var lead=inTx(()->store.submit(admin,new LeadCommand(UUID.randomUUID().toString(),name,"测试地址"+name,area,new BigDecimal("120.1234567"),new BigDecimal("30.1234567"),"0571-00000000")));
        return inTx(()->store.review(admin,lead.id(),new Review(UUID.randomUUID().toString(),lead.revision(),true,"隔离测试核实"))).resultStoreId();
    }
    void claim(String id,StoreScope scope){long revision=store.detail(scope,id).revision();inTx(()->{store.claim(scope,id,new Claim(UUID.randomUUID().toString(),revision));return null;});}

    @Test void nationwideAndCityScopesFilterRowsCountsDetailsAndOptions(){
        String hz=create("330105","杭州门店"),lz=create("620102","兰州门店");
        var all=store.list(national("crm:pool:public-read","1"),query("PUBLIC",null));assertThat(all.total()).isEqualTo(2);
        var user=hangzhou("crm:pool:public-read","2");
        assertThat(store.list(user,query("PUBLIC",null)).items()).extracting(Row::id).containsExactly(hz);
        assertThat(store.list(user,query("PUBLIC","620100")).total()).isZero();
        assertThatThrownBy(()->store.detail(user,lz)).isInstanceOf(AuthorizationDeniedException.class);
        assertThat(user.context().scopeLabel()).isEqualTo("杭州市");
        assertThat(user.context().areas()).extracting(Area::code).doesNotContain("620000","620100","620102");
        assertThat(national("crm:pool:public-read","1").context().national()).isTrue();
    }
    @Test void personalCeilingCannotBeRemovedByClearingFiltersOrNationalRole(){
        create("330105","杭州");create("620102","兰州");
        var capped=scope("crm:pool:public-read","1",List.of(nationalGrant()),true,List.of(new StoreAccessView.Area(VERSION,"330100")),Map.of());
        assertThat(store.list(capped,query("PUBLIC",null)).total()).isEqualTo(1);
        assertThat(capped.context().national()).isFalse();
        var empty=scope("crm:pool:public-read","1",List.of(nationalGrant()),true,List.of(),Map.of());
        assertThat(store.list(empty,query("PUBLIC",null)).total()).isZero();
    }
    @Test void selfHangzhouAndTeamNanjingDoNotBecomeTeamHangzhou(){
        String hz=create("330105","杭州团队店"),nj=create("320102","南京团队店");
        claim(hz,national("crm:pool:claim","2"));claim(nj,national("crm:pool:claim","2"));
        var team=new StoreAccessView.Grant("DEPARTMENT",List.of("20"),false,false,List.of(new StoreAccessView.Area(VERSION,"320100")));
        var user=scope("crm:pool:team-read","1",List.of(self("330100"),team),false,List.of(),Map.of("20:false",List.of("E2")));
        assertThat(store.list(user,query("TEAM",null)).items()).extracting(Row::id).containsExactly(nj);
        assertThatThrownBy(()->store.detail(user,hz)).isInstanceOf(AuthorizationDeniedException.class);
    }
    @Test void publicReadCannotFetchOwnedStoreEvenWhenOwnerMatches(){
        String id=create("330105","私海可见性");claim(id,hangzhou("crm:pool:claim","2"));
        assertThatThrownBy(()->store.detail(hangzhou("crm:pool:public-read","2"),id)).isInstanceOf(AuthorizationDeniedException.class);
        assertThat(store.detail(hangzhou("crm:pool:private-read","2"),id).poolType()).isEqualTo("PRIVATE");
    }
    @Test void simultaneousClaimsHaveExactlyOneWinnerAndNoOrphanedSummary()throws Exception{
        String id=create("330105","并发认领");var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var results=new ArrayList<Future<Boolean>>();
            for(String owner:List.of("2","3"))results.add(executor.submit(()->{start.await();try{inTx(()->{store.claim(hangzhou("crm:pool:claim",owner),id,new Claim(UUID.randomUUID().toString(),1));return null;});return true;}catch(StateConflictException | AuthorizationDeniedException e){return false;}}));
            start.countDown();int wins=0;for(var r:results)if(r.get(15,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_store_ownership WHERE tenant_id=? AND store_id=? AND ended_at IS NULL",Integer.class,tenant,id)).isEqualTo(1);
        assertThat(store.list(national("crm:pool:public-read","1"),query("PUBLIC",null)).total()).isZero();
    }
    @Test void releasePreservesCooperationAndOldOwnershipButBlocksSameEmployeeFor48Hours(){
        String id=create("330105","合作老客户");var a=hangzhou("crm:pool:claim","2");claim(id,a);
        jdbc.update("UPDATE crm_store_summary SET cooperation_status='COOPERATED',customer_tier='C',activity_status='DORMANT',last_order_at='2025-01-01 00:00:00' WHERE tenant_id=? AND store_id=?",tenant,id);
        var own=store.detail(a,id);
        inTx(()->{store.release(hangzhou("crm:pool:release","2"),id,new Release(UUID.randomUUID().toString(),own.revision(),"主动退回"));return null;});
        var publicRow=store.detail(a,id);
        assertThat(publicRow.cooperationStatus()).isEqualTo("COOPERATED");assertThat(publicRow.customerTier()).isEqualTo("C");assertThat(publicRow.claimBlockedReason()).isEqualTo("本人冷却中");
        assertThatThrownBy(()->inTx(()->{store.claim(a,id,new Claim(UUID.randomUUID().toString(),publicRow.revision()));return null;})).isInstanceOf(StateConflictException.class);
        var b=hangzhou("crm:pool:claim","3");claim(id,b);
        var history=store.ownerships(b,id);assertThat(history).hasSize(2);assertThat(history.getFirst().phase()).isEqualTo("FIRST_VISIT");assertThat(history.get(1).ownerEmployeeId()).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(HOUR,ended_at,cooldown_until) FROM crm_store_ownership WHERE tenant_id=? AND store_id=? AND owner_employee_id=2",Integer.class,tenant,id)).isEqualTo(48);
    }
    @Test void retryingAClaimIsIdempotentAndCannotReuseAnotherStoreRequest(){
        String id=create("330105","幂等门店"),other=create("330105","另一门店");var scope=hangzhou("crm:pool:claim","2");var c=new Claim(UUID.randomUUID().toString(),1);
        inTx(()->{store.claim(scope,id,c);return null;});inTx(()->{store.claim(scope,id,c);return null;});
        assertThat(store.ownerships(scope,id)).hasSize(1);
        assertThatThrownBy(()->inTx(()->{store.claim(scope,other,c);return null;})).isInstanceOf(StateConflictException.class);
    }
    @Test void profileChangesPersistActorsTimestampsAndBeforeAfterEvidence(){
        String id=create("330105","原名称");var scope=national("crm:store:manage","1");var before=store.detail(scope,id);
        inTx(()->{store.update(scope,id,new Update(before.revision(),UUID.randomUUID().toString(),"新名称","新地址","0571-12345678",null,"实地核实更名"));return null;});
        var after=store.detail(scope,id);assertThat(after.name()).isEqualTo("新名称");assertThat(after.createdAt()).isEqualTo(before.createdAt());assertThat(after.createdBy()).isEqualTo("account-1");
        assertThat(after.updatedAt()).isNotNull();assertThat(after.revision()).isEqualTo(before.revision()+1);
        var event=store.events(scope,id).getFirst();assertThat(event.beforeData()).contains("原名称");assertThat(event.afterData()).contains("新名称");assertThat(event.reason()).isEqualTo("实地核实更名");
    }
    @Test void duplicateManualStoreCannotBeAdmittedAgain(){
        create("330105","重复门店");
        assertThatThrownBy(()->inTx(()->store.submit(national("crm:lead:create","1"),new LeadCommand(UUID.randomUUID().toString(),"重复门店","测试地址重复门店","330105",BigDecimal.ONE,BigDecimal.ONE,null)))).isInstanceOf(StateConflictException.class);
    }
    @Test void differentTenantsAndUnknownAdministrativeVersionsAreDenied(){
        String id=create("330105","租户门店");tenant=UUID.randomUUID().toString();
        assertThat(store.list(national("crm:pool:public-read","1"),query("PUBLIC",null)).total()).isZero();
        assertThatThrownBy(()->store.detail(national("crm:pool:public-read","1"),id)).isInstanceOf(AuthorizationDeniedException.class);
        var stale=scope("crm:pool:public-read","1",List.of(new StoreAccessView.Grant("SELF",List.of(),false,false,List.of(new StoreAccessView.Area("OLD","330100")))),false,List.of(),Map.of());
        assertThat(stale.context().areas()).isEmpty();
    }
    @Test void mapCountsEveryStoreBeyondListPaginationAndDrillsIntoCoincidentStores(){
        for(int i=0;i<105;i++)create("330105","重合门店"+i);
        var scope=national("crm:pool:public-read","1");
        var query=query("PUBLIC",null);
        assertThat(store.list(scope,query).items()).hasSize(20);
        var map=store.map(scope,query,Set.of(),new MapWindow(null,null,null,null,4d));
        assertThat(map.total()).isEqualTo(105);assertThat(map.located()).isEqualTo(105);assertThat(map.visible()).isEqualTo(105);
        assertThat(map.points()).hasSize(1);var point=map.points().getFirst();assertThat(point.count()).isEqualTo(105);assertThat(point.storeId()).isNull();
        var b=point.bounds();var window=new MapWindow(b.west(),b.south(),b.east(),b.north(),20d);
        var first=store.mapStores(scope,query,Set.of(),window,point.coordinateSystem());
        assertThat(first.total()).isEqualTo(105);assertThat(first.items()).hasSize(20);assertThat(first.nextCursor()).isNotNull();
        var nextQuery=new Query("PUBLIC",null,null,null,null,null,null,null,null,null,first.nextCursor(),20);
        var next=store.mapStores(scope,nextQuery,Set.of(),window,point.coordinateSystem());
        assertThat(next.items()).hasSize(20);assertThat(next.items()).extracting(Row::id).doesNotContainAnyElementsOf(first.items().stream().map(Row::id).toList());
        assertThat(store.map(scope,nextQuery,Set.of(),window).visible()).isEqualTo(105);
    }
    @Test void administrativeLevelsKeepOneNamedPointPerAreaAndWholeAreaCountsWhenPanning(){
        create("330105","杭州北店");create("330106","杭州西店");String nb=create("330203","宁波店");create("320102","南京店");
        jdbc.update("UPDATE crm_store_master SET longitude=121.55,latitude=29.87 WHERE id=?",nb);
        var scope=national("crm:pool:public-read","1");var q=query("PUBLIC",null);
        var province=store.map(scope,q,Set.of(),new MapWindow(null,null,null,null,5d));
        assertThat(province.points()).extracting(MapPoint::name).containsExactlyInAnyOrder("浙江省","江苏省");
        assertThat(province.points().stream().filter(p->"浙江省".equals(p.name())).findFirst().orElseThrow().count()).isEqualTo(3);
        var city=store.map(scope,q,Set.of(),new MapWindow(null,null,null,null,8d));
        assertThat(city.points()).extracting(MapPoint::name).containsExactlyInAnyOrder("杭州市","宁波市","南京市");
        var county=store.map(scope,q,Set.of(),new MapWindow(null,null,null,null,11d));
        assertThat(county.points()).extracting(MapPoint::name).containsExactlyInAnyOrder("拱墅区","西湖区","海曙区","玄武区");
        var cropped=store.map(scope,q,Set.of(),new MapWindow(120.12,30.12,120.13,30.13,5d));
        assertThat(cropped.points().stream().filter(p->"浙江省".equals(p.name())).findFirst().orElseThrow().count()).isEqualTo(3);
        var restricted=store.map(hangzhou("crm:pool:public-read","2"),q,Set.of(),new MapWindow(null,null,null,null,5d));
        assertThat(restricted.points()).hasSize(1);assertThat(restricted.points().getFirst().count()).isEqualTo(2);
    }
    @Test void specialAdministrativeHierarchiesAndMixedCoordinateSystemsDoNotSplitAreaCounts(){
        String bj=create("110101","北京店"),dg=create("441900","东莞店"),xt=create("429004","仙桃店");
        jdbc.update("UPDATE crm_store_master SET longitude=116.4,latitude=39.9 WHERE id=?",bj);
        jdbc.update("UPDATE crm_store_master SET longitude=113.7,latitude=23 WHERE id=?",dg);
        jdbc.update("UPDATE crm_store_master SET longitude=113.4,latitude=30.3 WHERE id=?",xt);
        String gcj=create("330105","杭州GCJ店"),gps=create("330105","杭州GPS店");
        jdbc.update("UPDATE crm_store_master SET longitude=119,latitude=29,coordinate_system='WGS84' WHERE id=?",gps);
        var scope=national("crm:pool:public-read","1");var q=query("PUBLIC",null);
        var city=store.map(scope,q,Set.of(),new MapWindow(null,null,null,null,8d));
        assertThat(city.points()).extracting(MapPoint::name).containsExactlyInAnyOrder("北京市","东莞市","仙桃市","杭州市");
        var hangzhou=city.points().stream().filter(p->"330100".equals(p.areaCode())).findFirst().orElseThrow();
        assertThat(hangzhou.count()).isEqualTo(2);assertThat(hangzhou.coordinateSystem()).isEqualTo("GCJ02");
        assertThat(hangzhou.longitude()).isEqualTo(120.1234567);assertThat(hangzhou.storeId()).isNull();
        var county=store.map(scope,q,Set.of(),new MapWindow(null,null,null,null,11d));
        assertThat(county.points()).extracting(MapPoint::name).containsExactlyInAnyOrder("东城区","东莞市","仙桃市","拱墅区");
        assertThat(county.points().stream().filter(p->"441900".equals(p.areaCode())).findFirst().orElseThrow().areaLevel()).isEqualTo("PREFECTURE");
        var street=store.map(scope,q,Set.of(),new MapWindow(null,null,null,null,13d));
        assertThat(street.level()).isEqualTo("STORE");assertThat(street.points()).extracting(MapPoint::storeId).contains(gcj,gps);
    }
    @Test void mapSharesCityOwnershipTenantAndGeographicCeilingPermissions(){
        String hz=create("330105","杭州地图"),lz=create("620102","兰州地图");
        var window=new MapWindow(null,null,null,null,4d);
        var scope=hangzhou("crm:pool:public-read","2");
        assertThat(store.map(scope,query("PUBLIC",null),Set.of(),window).total()).isEqualTo(1);
        assertThat(store.map(scope,query("PUBLIC","620100"),Set.of(),window).points()).isEmpty();
        claim(hz,national("crm:pool:claim","3"));
        assertThat(store.map(scope,query("PUBLIC",null),Set.of(),window).total()).isZero();
        assertThat(store.map(hangzhou("crm:pool:private-read","2"),query("MINE",null),Set.of(),window).total()).isZero();
        assertThat(store.map(hangzhou("crm:pool:private-read","3"),query("MINE",null),Set.of(),new MapWindow(null,null,null,null,16d)).points().getFirst().storeId()).isEqualTo(hz);
        var capped=scope("crm:pool:public-read","2",List.of(nationalGrant()),true,List.of(new StoreAccessView.Area(VERSION,"330100")),Map.of());
        assertThat(store.map(capped,query("PUBLIC",null),Set.of(),window).total()).isZero();
        tenant=UUID.randomUUID().toString();
        assertThat(store.map(national("crm:pool:public-read","1"),query("PUBLIC",null),Set.of(),window).total()).isZero();
    }
    @Test void mapFiltersTypesWithoutLosingMissingLocationsAndCountsCooperationSeparately(){
        String a=create("330105","有类型"),b=create("330105","位置待完善"),c=create("330105","未分类");
        jdbc.update("UPDATE crm_store_master SET store_type_code='BILLIARDS' WHERE id IN (?,?)",a,b);
        jdbc.update("UPDATE crm_store_master SET longitude=NULL,latitude=NULL,coordinate_system=NULL WHERE id=?",b);
        jdbc.update("UPDATE crm_store_summary SET cooperation_status='COOPERATED' WHERE store_id=?",a);
        var scope=national("crm:pool:public-read","1");
        var q=new Query("PUBLIC",null,null,null,null,null,null,null,null,null,null,20,"SPORTS");
        var map=store.map(scope,q,Set.of("SPORTS","BILLIARDS"),new MapWindow(null,null,null,null,4d));
        assertThat(map.total()).isEqualTo(2);assertThat(map.located()).isEqualTo(1);assertThat(map.visible()).isEqualTo(2);
        assertThat(map.points().getFirst().cooperatedCount()).isEqualTo(1);assertThat(map.points().getFirst().privateCount()).isZero();
        assertThat(store.list(scope,q,Set.of("SPORTS","BILLIARDS")).total()).isEqualTo(map.total());
        var unclassified=new Query("PUBLIC",null,null,null,null,null,null,null,null,null,null,20,"__UNCLASSIFIED__");
        assertThat(store.map(scope,unclassified,Set.of(),new MapWindow(null,null,null,null,16d)).points().getFirst().storeId()).isEqualTo(c);
    }
    @Test void viewportAndZoomRefineClustersWithoutTruncatingAnyMatchingStore(){
        for(int i=0;i<50;i++){
            String id=create("330105","分散门店"+i);
            jdbc.update("UPDATE crm_store_master SET longitude=?,latitude=? WHERE id=?",120+i*.001,30+i*.001,id);
        }
        var scope=national("crm:pool:public-read","1");var q=query("PUBLIC",null);
        var overview=store.map(scope,q,Set.of(),new MapWindow(null,null,null,null,4d));
        var street=store.map(scope,q,Set.of(),new MapWindow(120d,30d,120.01,30.01,18d));
        assertThat(overview.visible()).isEqualTo(50);assertThat(street.total()).isEqualTo(50);assertThat(street.visible()).isEqualTo(11);
        assertThat(street.points()).hasSize(11);assertThat(street.points()).allMatch(p->p.storeId()!=null&&p.count()==1);
        assertThat(store.map(scope,q,Set.of(),new MapWindow(100d,20d,101d,21d,10d)).visible()).isZero();
    }
    @Test void gpsBorderPointsRetainTheirCoordinateSystemAndDoNotDisappearBeforeConversion(){
        String id=create("330105","GPS门店");
        jdbc.update("UPDATE crm_store_master SET longitude=119.995,latitude=30,coordinate_system='WGS84' WHERE id=?",id);
        var map=store.map(national("crm:pool:public-read","1"),query("PUBLIC",null),Set.of(),new MapWindow(120d,29.99,120.01,30.01,18d));
        assertThat(map.visible()).isEqualTo(1);assertThat(map.points().getFirst().coordinateSystem()).isEqualTo("WGS84");
        assertThat(map.points().getFirst().longitude()).isEqualTo(119.995);
    }

}
