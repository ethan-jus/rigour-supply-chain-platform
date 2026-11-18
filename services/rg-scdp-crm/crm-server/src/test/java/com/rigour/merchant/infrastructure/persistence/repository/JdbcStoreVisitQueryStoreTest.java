package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.application.service.StoreScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;

class JdbcStoreVisitQueryStoreTest {
    static JdbcStoreVisitQueryStore workbench;
    JdbcStorePoolStoreTest fixture;
    final Instant from=Instant.parse("2026-10-31T16:00:00Z"),to=Instant.parse("2026-11-30T16:00:00Z");
    @BeforeAll static void setup(){JdbcStorePoolStoreTest.setup();workbench=new JdbcStoreVisitQueryStore(JdbcStorePoolStoreTest.jdbc);}
    @AfterAll static void stop(){JdbcStorePoolStoreTest.stopContainer();}
    @BeforeEach void init(){fixture=new JdbcStorePoolStoreTest();fixture.tenant();}
    StoreScope scope(){return fixture.hangzhou("crm:store:history-read","2");}
    String store(){String id=fixture.create("330105","测试门店"+UUID.randomUUID());fixture.claim(id,fixture.hangzhou("crm:pool:claim","2"));return id;}
    long visit(String store,String employee,String state,Instant time,boolean left) {
        long id=Math.abs(System.nanoTime());
        var cycle=fixture.jdbc.queryForObject("SELECT id FROM crm_store_ownership WHERE tenant_id=? AND store_id=? AND ended_at IS NULL",Long.class,fixture.tenant,store);
        var stamp=Timestamp.from(time);
        fixture.jdbc.update("""
            INSERT INTO crm_visit(id,tenant_id,created_at,updated_at,created_by,updated_by,store_id,ownership_id,salesperson_id,status_code,visited_at,accepted_at,checkin_accepted_at,checked_out_at,submitted_at,qualified_at,checkin_longitude,checkin_latitude,checkin_coordinate_system,distance_meters,store_snapshot,validation_policy_version,request_id,capture_ref)
            VALUES(?,?,?,?,'test','test',?,?,?,?,?,?,?,?,?,?,120,30,'GCJ02',0,'{"name":"当时门店","address":"测试路8号","areaCode":"330105"}','test',?,?)
            """,id,fixture.tenant,stamp,stamp,store,cycle,employee,state,stamp,stamp,stamp,
            left?Timestamp.from(time.plusSeconds(600)):null,
            state.equals("IN_PROGRESS")?null:stamp,state.equals("EFFECTIVE")?stamp:null,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        return id;
    }
    @Test void ownTenantPeriodSearchAndPaginationKeepCountsAligned() {
        String a=store(),b=store();
        visit(a,"2","EFFECTIVE",from.plusSeconds(10),true);
        visit(b,"2","SUBMITTED",from.plusSeconds(20),true);
        visit(b,"3","EFFECTIVE",from.plusSeconds(30),true);
        visit(b,"2","SUBMITTED",to,true);
        var page=workbench.list(scope(),from,to,"all","ALL",null,1,1);
        assertThat(page.total()).isEqualTo(2);assertThat(page.summary().totalVisitCount()).isEqualTo(2);
        assertThat(page.summary().effectiveVisitCount()).isEqualTo(1);assertThat(page.summary().firstVisitCount()).isEqualTo(2);
        var next=workbench.list(scope(),from,to,"all","ALL",null,2,1);
        assertThat(next.items().getFirst().id()).isNotEqualTo(page.items().getFirst().id());
        assertThat(next.items().getFirst().name()).isEqualTo("当时门店");
        assertThat(workbench.list(scope(),from,to,"all","ALL","missing",1,20).total()).isZero();
        fixture.tenant=UUID.randomUUID().toString();assertThat(workbench.list(scope(),from,to,"all","ALL",null,1,20).total()).isZero();
    }
    @Test void firstVisitUsesEmployeeStoreAndStableTieBreaker() {
        String a=store(),b=store();Instant time=from.plusSeconds(20);
        visit(a,"2","SUBMITTED",from.minusSeconds(1),true);visit(a,"2","EFFECTIVE",time,true);
        visit(b,"3","SUBMITTED",from.minusSeconds(1),true);visit(b,"2","SUBMITTED",time,true);visit(b,"2","SUBMITTED",time,true);
        var result=workbench.list(scope(),from,to,"first","ALL",null,1,20);
        assertThat(result.total()).isEqualTo(1);assertThat(result.summary().firstVisitCount()).isEqualTo(1);
        assertThat(result.items().getFirst().storeId()).isEqualTo(b);
    }
    @Test void departureDoesNotMeanEffectiveAndActiveIgnoresHistoricalFilter() {
        String a=store();long active=visit(a,"2","IN_PROGRESS",to.plusSeconds(60),false);
        visit(a,"2","IN_PROGRESS",from.plusSeconds(10),true);
        var pending=workbench.list(scope(),from,to,"all","PENDING_SUBMIT",null,1,20);
        assertThat(pending.total()).isEqualTo(1);assertThat(pending.items().getFirst().status()).isEqualTo("PENDING_SUBMIT");
        assertThat(pending.summary().effectiveVisitCount()).isZero();
        assertThat(pending.active().id()).isEqualTo(Long.toString(active));
        assertThat(workbench.list(scope(),from,to,"effective","ALL",null,1,20).total()).isZero();
    }
    @Test void cancelledRecordsRemainAuditableButDoNotCountOrStealTheFirstVisit() {
        String a=store();long cancelled=visit(a,"2","INVALID",from.plusSeconds(10),true);
        fixture.jdbc.update("UPDATE crm_visit SET review_source='CANCELLED' WHERE tenant_id=? AND id=?",fixture.tenant,cancelled);
        visit(a,"2","SUBMITTED",from.plusSeconds(20),true);
        var result=workbench.list(scope(),from,to,"all","ALL",null,1,20);
        assertThat(result.total()).isEqualTo(2);assertThat(result.summary().totalVisitCount()).isEqualTo(1);assertThat(result.summary().firstVisitCount()).isEqualTo(1);
        assertThat(workbench.list(scope(),from,to,"all","CANCELLED",null,1,20).items().getFirst().status()).isEqualTo("CANCELLED");
        assertThat(workbench.list(scope(),from,to,"first","ALL",null,1,20).total()).isEqualTo(1);
    }
}
