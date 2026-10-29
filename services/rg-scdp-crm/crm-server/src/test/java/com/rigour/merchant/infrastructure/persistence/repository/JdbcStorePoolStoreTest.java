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

@Testcontainers
class JdbcStorePoolStoreTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    static JdbcTemplate jdbc;
    static JdbcStorePoolStore store;
    static TransactionTemplate tx;
    String tenant;
    static final String VERSION="TEST-2026";
    static final AdminDivisionCatalog CATALOG=new AdminDivisionCatalog(VERSION,"isolated test fixture","test-only",List.of(
            new AdminDivisionCatalog.Node("330000","浙江省","PROVINCE",null,"EAST"),
            new AdminDivisionCatalog.Node("330100","杭州市","PREFECTURE","330000",null),
            new AdminDivisionCatalog.Node("330105","拱墅区","COUNTY","330100",null),
            new AdminDivisionCatalog.Node("320000","江苏省","PROVINCE",null,"EAST"),
            new AdminDivisionCatalog.Node("320100","南京市","PREFECTURE","320000",null),
            new AdminDivisionCatalog.Node("320102","玄武区","COUNTY","320100",null),
            new AdminDivisionCatalog.Node("620000","甘肃省","PROVINCE",null,"NORTHWEST"),
            new AdminDivisionCatalog.Node("620100","兰州市","PREFECTURE","620000",null),
            new AdminDivisionCatalog.Node("620102","城关区","COUNTY","620100",null)));
    @BeforeAll static void setup(){
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl()+"?connectionTimeZone=UTC",MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/bootstrap")
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();
        jdbc=new JdbcTemplate(ds);store=new JdbcStorePoolStore(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
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
}
