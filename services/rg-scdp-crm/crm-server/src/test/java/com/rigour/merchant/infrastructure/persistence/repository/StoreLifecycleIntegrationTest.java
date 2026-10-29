package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.merchant.application.service.StoreLifecycleService;
import com.rigour.order.api.v1.model.StoreOrderFacts;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class StoreLifecycleIntegrationTest {
    @Container static MySQLContainer mysql=new MySQLContainer("mysql:8.4");
    static JdbcTemplate db;static TransactionTemplate tx;
    JdbcStorePoolStore pool;JdbcStoreLifecycleStore lifecycle;JdbcStoreVisitStore visits;
    String tenant,store,ownership;Instant t;
    @BeforeAll static void database(){var ds=new DriverManagerDataSource(mysql.getJdbcUrl()+"?connectionTimeZone=UTC",mysql.getUsername(),mysql.getPassword());Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/bootstrap").load().migrate();db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));}
    <T>T transaction(Supplier<T> work){return tx.execute(s->work.get());}
    StoreScope scope(String action,String employee){return new StoreScope(new StoreAccessView(tenant,"actor-"+employee,action,1,employee,"E"+employee,"员工"+employee,"10",Set.of(action),List.of(new StoreAccessView.Grant("ALL",List.of(),false,true,List.of())),false,List.of()),new AdminDivisionCatalog("TEST","fixture","fixture",List.of(new AdminDivisionCatalog.Node("330000","浙江","PROVINCE",null,"EAST"),new AdminDivisionCatalog.Node("330100","杭州","PREFECTURE","330000",null),new AdminDivisionCatalog.Node("330102","上城","COUNTY","330100",null))),Map.of());}
    @BeforeEach void fixture(){
        pool=new JdbcStorePoolStore(db);lifecycle=new JdbcStoreLifecycleStore(db);visits=new JdbcStoreVisitStore(db,pool,lifecycle);tenant=UUID.randomUUID().toString();var s=scope("crm:store:manage","1");
        transaction(()->{pool.initializePolicy(s);return null;});
        var lead=transaction(()->pool.submit(s,new LeadCommand(UUID.randomUUID().toString(),"测试门店","测试地址","330102",new BigDecimal("120.1"),new BigDecimal("30.1"),null)));
        store=transaction(()->pool.review(s,lead.id(),new Review(UUID.randomUUID().toString(),1,true,"测试核实"))).resultStoreId();
        transaction(()->{pool.claim(s,store,new Claim(UUID.randomUUID().toString(),1));return null;});ownership=lifecycle.current(tenant,store).id();t=Instant.now().minusSeconds(100*86400L).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update("UPDATE crm_store_ownership SET started_at=?,deadline_at=? WHERE tenant_id=? AND id=?",Timestamp.from(t),Timestamp.from(t.plusSeconds(86400)),tenant,ownership);
        db.update("UPDATE crm_store_summary SET deadline_at=? WHERE tenant_id=? AND store_id=?",Timestamp.from(t.plusSeconds(86400)),tenant,store);
    }
    StoreOrderFacts facts(Instant now,boolean pending,StoreOrderFacts.Fact... orders){return new StoreOrderFacts(tenant,store,ownership,now,"STORE_ORDER_V1",List.of(orders),pending,orders.length==0?null:orders[0].effectiveAt(),orders.length==0?null:orders[0].effectiveAt());}
    RefreshResult refresh(Instant now,StoreOrderFacts facts){var c=lifecycle.current(tenant,store);return transaction(()->lifecycle.reconcile(c,facts,null,now));}
    Visit visitAt(Instant time){var s=scope("crm:store:visit-write","1");var v=transaction(()->visits.checkIn(s,store,new CheckIn(UUID.randomUUID().toString(),ownership,new BigDecimal("120.1"),new BigDecimal("30.1"),"GCJ02",BigDecimal.ONE),new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy("fixture",300),time));v=submit(v,time.plusSeconds(600));var value=v;return transaction(()->visits.review(scope("crm:store:visit-review","2"),store,value.id(),new ReviewVisit(value.revision(),"EFFECTIVE","主管核验证据通过"),time.plusSeconds(650)));}
    Visit submit(Visit v,Instant now){return transaction(()->visits.submit(scope("crm:store:visit-write","1"),store,v.id(),new SubmitVisit(v.revision(),"拜访结果",List.of("dev/photo.jpg")),now));}
    @Test void firstVisitExpiresExactlyAtBoundaryAndOnlyOriginalEmployeeCools(){
        assertThat(refresh(t.plusSeconds(86400-1),facts(t,false)).outcome()).isEqualTo("PROTECTED");
        assertThat(refresh(t.plusSeconds(86400),facts(t,false)).outcome()).isEqualTo("RELEASED");
        assertThat(db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,ended_at,cooldown_until) FROM crm_store_ownership WHERE id=?",Long.class,ownership)).isEqualTo(172800);
        assertThat(db.queryForObject("SELECT owner_employee_id FROM crm_store_ownership WHERE id=?",Long.class,ownership)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT pool_type FROM crm_store_summary WHERE tenant_id=? AND store_id=?",String.class,tenant,store)).isEqualTo("PUBLIC");
    }
    @Test void firstAndRepeatVisitExtendSevenDaysAndHaveCompanySequence(){var first=visitAt(t.plusSeconds(3600));var second=visitAt(t.plusSeconds(2*86400));assertThat(first.visitType()).isEqualTo("INITIAL");assertThat(second.visitType()).isEqualTo("REVISIT");assertThat(second.storeVisitNo()).isEqualTo(2);assertThat(lifecycle.current(tenant,store).deadline()).isEqualTo(t.plusSeconds(9*86400));assertThat(refresh(t.plusSeconds(9*86400),facts(t,false)).outcome()).isEqualTo("RELEASED");}
    @Test void currentOrderEnablesSixtyDaysAndVisitExtendsWithoutChangingOrderTime(){var order=new StoreOrderFacts.Fact("123",ownership,"1",t.plusSeconds(3600),1);assertThat(refresh(t.plusSeconds(7200),facts(t,false,order)).phase()).isEqualTo("COOPERATING");visitAt(t.plusSeconds(50*86400));assertThat(lifecycle.current(tenant,store).deadline()).isEqualTo(t.plusSeconds(110*86400));assertThat(db.queryForObject("SELECT last_order_at FROM crm_store_summary WHERE tenant_id=? AND store_id=?",Timestamp.class,tenant,store).toInstant()).isEqualTo(order.effectiveAt());assertThat(refresh(t.plusSeconds(110*86400),facts(t,false,order)).outcome()).isEqualTo("RELEASED");assertThat(pool.detail(scope("crm:store:manage","2"),store).cooperationStatus()).isEqualTo("COOPERATED");}
    @Test void pendingReviewAndOrderPreventRecoveryButDraftDoesNot(){var s=scope("crm:store:visit-write","1");var v=transaction(()->visits.checkIn(s,store,new CheckIn(UUID.randomUUID().toString(),ownership,new BigDecimal("120.1"),new BigDecimal("30.1"),"GCJ02",null),new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy("fixture",300),t.plusSeconds(3600)));submit(v,t.plusSeconds(7200));assertThat(refresh(t.plusSeconds(86400),facts(t,false)).holdReason()).isEqualTo("PENDING_REVIEW");}
    @Test void sourceFailureHoldsAndRecoveryResumesAfterAuthorityReturns(){var c=lifecycle.current(tenant,store);assertThat(transaction(()->lifecycle.hold(c,t.plusSeconds(86400))).holdReason()).isEqualTo("SOURCE_UNAVAILABLE");assertThat(lifecycle.current(tenant,store)).isNotNull();assertThat(refresh(t.plusSeconds(86460),facts(t,false)).outcome()).isEqualTo("RELEASED");}
    @Test void authorizationRevisionInvalidatesPreviouslyReadRecoveryCandidate(){var before=lifecycle.current(tenant,store);var s=scope("crm:store:order-write","1");var c=new OrderAuthorization("55",ownership,UUID.randomUUID().toString());var permit=transaction(()->visits.authorize(s,store,c,t.plusSeconds(60)));assertThat(transaction(()->visits.authorize(s,store,c,t.plusSeconds(120)))).isEqualTo(permit);assertThat(transaction(()->lifecycle.reconcile(before,facts(t,false),null,t.plusSeconds(86400))).outcome()).isEqualTo("RETRY");}
    @Test void oldCycleOrderCannotProtectThisOwner(){var order=new StoreOrderFacts.Fact("123","999","2",t.plusSeconds(60),1);assertThat(refresh(t.plusSeconds(86400),facts(t,false,order)).outcome()).isEqualTo("RELEASED");assertThat(pool.detail(scope("crm:store:manage","2"),store).cooperationStatus()).isEqualTo("COOPERATED");}
    @Test void cancelledOrderAndRevokedVisitRecalculateWithoutRewardingNewTime(){var v=visitAt(t.plusSeconds(3600));var order=new StoreOrderFacts.Fact("123",ownership,"1",t.plusSeconds(7200),1);refresh(t.plusSeconds(8000),facts(t,false,order));assertThat(refresh(t.plusSeconds(9000),facts(t,false)).deadline()).isEqualTo(t.plusSeconds(3600+7*86400));transaction(()->visits.review(scope("crm:store:visit-review","2"),store,v.id(),new ReviewVisit(v.revision(),"VOID","重复或错误证据撤销"),t.plusSeconds(10000)));assertThat(lifecycle.current(tenant,store).deadline()).isEqualTo(t.plusSeconds(86400));}
    @Test void checkinRetryIsIdempotentAndLateNewWorkIsRejected(){var s=scope("crm:store:visit-write","1");var c=new CheckIn(UUID.randomUUID().toString(),ownership,new BigDecimal("120.1"),new BigDecimal("30.1"),"GCJ02",null);var first=transaction(()->visits.checkIn(s,store,c,new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy("fixture",300),t.plusSeconds(3600)));assertThat(transaction(()->visits.checkIn(s,store,c,new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy("fixture",300),t.plusSeconds(7200))).visitedAt()).isEqualTo(first.visitedAt());assertThatThrownBy(()->submit(first,t.plusSeconds(86400))).hasMessageContaining("到期");assertThat(refresh(t.plusSeconds(86400),facts(t,false)).outcome()).isEqualTo("RELEASED");}
    @Test void automaticEvidenceUsesOriginalVisitTimeAndDoesNotOverrideManualRejection(){
        String capture=UUID.randomUUID().toString();var s=scope("crm:store:visit-write","1");
        var v=transaction(()->visits.checkIn(s,store,new CheckIn(UUID.randomUUID().toString(),ownership,new BigDecimal("120.1"),new BigDecimal("30.1"),"GCJ02",BigDecimal.ONE,capture),new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy("fixture",500),t.plusSeconds(600)));
        var submitted=submit(v,t.plusSeconds(1200));
        var evidence=new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Outcome(v.id(),capture,"E1","fixture",v.visitedAt(),t.plusSeconds(1200),t.plusSeconds(1200),"EFFECTIVE","证据完整",1);
        var captures=new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Facts(tenant,store,ownership,t.plusSeconds(86400),List.of(evidence));
        var before=lifecycle.current(tenant,store);
        var result=transaction(()->lifecycle.reconcile(before,facts(t,false),captures,t.plusSeconds(86400)));
        assertThat(result.phase()).isEqualTo("FOLLOW_UP");assertThat(result.deadline()).isEqualTo(v.visitedAt().plusSeconds(7*86400));
        var effective=visits.visits(s,store).getFirst();assertThat(effective.status()).isEqualTo("EFFECTIVE");assertThat(effective.storeVisitNo()).isEqualTo(1);
        transaction(()->visits.review(scope("crm:store:visit-review","2"),store,v.id(),new ReviewVisit(effective.revision(),"VOID","主管确认采集重复"),t.plusSeconds(86500)));
        var current=lifecycle.current(tenant,store);
        transaction(()->lifecycle.reconcile(current,facts(t,false),captures,t.plusSeconds(86600)));
        assertThat(visits.visits(s,store).getFirst().status()).isEqualTo("VOID");assertThat(lifecycle.current(tenant,store)).isNull();
    }
    @Test void cancellingCaptureDoesNotCreateProtectionAndAllowsFreshCheckin(){
        var s=scope("crm:store:visit-write","1");
        var policy=new com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy("fixture",300);
        var first=transaction(()->visits.checkIn(s,store,new CheckIn(UUID.randomUUID().toString(),ownership,new BigDecimal("120.1"),new BigDecimal("30.1"),"GCJ02",BigDecimal.ONE),policy,t.plusSeconds(600)));
        var command=new CancelVisit(first.revision(),"并发采集冲突");
        var cancelled=transaction(()->visits.cancel(s,store,first.id(),command,t.plusSeconds(601)));
        assertThat(cancelled.status()).isEqualTo("INVALID");assertThat(cancelled.qualifiedAt()).isNull();
        assertThat(transaction(()->visits.cancel(s,store,first.id(),command,t.plusSeconds(602)))).isEqualTo(cancelled);
        var next=transaction(()->visits.checkIn(s,store,new CheckIn(UUID.randomUUID().toString(),ownership,new BigDecimal("120.1"),new BigDecimal("30.1"),"GCJ02",BigDecimal.ONE),policy,t.plusSeconds(603)));
        assertThat(next.id()).isNotEqualTo(first.id());
        assertThat(refresh(t.plusSeconds(86400),facts(t,false)).outcome()).isEqualTo("RELEASED");
    }

}
