package com.rigour.merchant.infrastructure.persistence.repository;
import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.*;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.api.v1.model.StoreContactModels;
import com.rigour.shared.cache.QueryCache;
import com.rigour.merchant.infrastructure.cache.JdbcCacheVersions;
import java.time.*;import java.math.BigDecimal;import java.util.*;
import org.junit.jupiter.api.*;import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
class JdbcStoreVisitFieldworkStoreTest {
    JdbcStorePoolStoreTest f;JdbcStoreVisitFieldworkStore projection;JdbcStoreFactStore facts;String store;Visit visit;String capture;String policy;Instant now;
    @BeforeAll static void setup(){JdbcStorePoolStoreTest.setup();}
    @AfterAll static void stop(){JdbcStorePoolStoreTest.stopContainer();}
    @BeforeEach void init(){
        f=new JdbcStorePoolStoreTest();f.tenant();store=f.create("330105","拜访结构化隔离测试");f.claim(store,f.hangzhou("crm:pool:claim","2"));
        var lifecycle=new JdbcStoreLifecycleStore(f.jdbc,f.store);var visits=new JdbcStoreVisitStore(f.jdbc,f.store,lifecycle);capture=UUID.randomUUID().toString();policy=UUID.randomUUID().toString();now=Instant.now();
        var owner=f.store.detail(f.hangzhou("crm:store:visit-write","2"),store).currentOwnershipId();
        visit=f.inTx(()->visits.checkIn(f.hangzhou("crm:store:visit-write","2"),store,new CheckIn(UUID.randomUUID().toString(),owner,new BigDecimal("120.1234567"),new BigDecimal("30.1234567"),"GCJ02",BigDecimal.ONE,capture),new Policy(policy,500),now));
        projection=new JdbcStoreVisitFieldworkStore(f.jdbc,f.store,new ObjectMapper());facts=new JdbcStoreFactStore(f.jdbc,new ObjectMapper(),lifecycle);ReflectionTestUtils.setField(facts,"fieldwork",projection);
        var cache=new QueryCache(null,new JdbcCacheVersions(f.jdbc),()->null,()->true,null,"test",0,1);ReflectionTestUtils.setField(projection,"queryCache",cache);ReflectionTestUtils.setField(facts,"queryCache",cache);
    }
    Draft draft(String status){return new Draft(f.store.detail(f.hangzhou("crm:store:visit-write","2"),store).revision(),status,"现场负责人确认","TEST_TYPE","CONTACTED",List.of(),false,List.of(new Progress("TEST_LINE",null,"COOPERATED")),null,null,"CONTACT",LocalDate.now().plusDays(1));}
    Evidence event(Draft d,long revision){return new Evidence(f.tenant,store,visit.ownershipId(),visit.id(),capture,"E2",policy,revision,now.plusSeconds(revision),visit.visitedAt(),visit.visitedAt().plusSeconds(600),visit.visitedAt().plusSeconds(600),d.contactOutcome(),"结构化沟通结果",1,0L,null,null,null,false,new Accepted(d,"account-2","2",Map.of(),List.of()));}
    @Test void committedEvidenceUpdatesMasterProgressAndInvalidatesOnceWithoutFakingCooperation(){
        var d=draft("TEMP_CLOSED");var versions=new JdbcCacheVersions(f.jdbc);long before=versions.current(f.tenant);
        f.inTx(()->{facts.acceptEvidence(event(d,1),now.plusSeconds(601));return null;});
        assertThat(projection.syncStatus(f.tenant,visit.id())).isEqualTo("APPLIED");
        var row=f.store.detail(f.hangzhou("crm:store:visit-write","2"),store);assertThat(row.businessStatus()).isEqualTo("TEMP_CLOSED");assertThat(row.cooperationStatus()).isNotEqualTo("COOPERATED");
        assertThat(projection.businessType(f.tenant,store)).isEqualTo("TEST_TYPE");assertThat(projection.businesses(f.tenant,store)).containsExactly(new Progress("TEST_LINE",null,"COOPERATED"));
        assertThat(versions.current(f.tenant)).isEqualTo(before+1);
        f.inTx(()->{facts.acceptEvidence(event(d,2),now.plusSeconds(602));return null;});
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM crm_visit_fieldwork WHERE tenant_id=?",Integer.class,f.tenant)).isEqualTo(1);
        assertThat(projection.syncStatus(f.tenant,visit.id())).isEqualTo("APPLIED");
    }
    @Test void changedStoreRetainsObservationWithoutOverwritingCurrentProfile(){
        var d=draft("TEMP_CLOSED");f.jdbc.update("UPDATE crm_store_summary SET revision=revision+1 WHERE tenant_id=? AND store_id=?",f.tenant,store);
        f.inTx(()->{facts.acceptEvidence(event(d,1),now.plusSeconds(601));return null;});
        assertThat(projection.syncStatus(f.tenant,visit.id())).isEqualTo("CONFLICT");assertThat(projection.businesses(f.tenant,store)).isEmpty();
        assertThat(f.store.detail(f.hangzhou("crm:store:visit-write","2"),store).businessStatus()).isNotEqualTo("TEMP_CLOSED");
    }
    @Test void rollbackDoesNotLeaveProfileAuditOrCacheVersionAndRetrySucceeds(){
        var d=draft("OPEN");var versions=new JdbcCacheVersions(f.jdbc);long before=versions.current(f.tenant);
        assertThatThrownBy(()->f.inTx(()->{facts.acceptEvidence(event(d,1),now.plusSeconds(601));throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(projection.syncStatus(f.tenant,visit.id())).isEqualTo("NONE");assertThat(versions.current(f.tenant)).isEqualTo(before);
        f.inTx(()->{facts.acceptEvidence(event(d,1),now.plusSeconds(601));return null;});assertThat(projection.syncStatus(f.tenant,visit.id())).isEqualTo("APPLIED");
    }
    @Test void closureUsesExistingOwnershipRuleAndUnknownNeverErasesVerifiedStatus(){
        f.jdbc.update("UPDATE crm_store_master SET business_status='OPEN' WHERE tenant_id=? AND id=?",f.tenant,store);
        var d=draft("UNKNOWN");f.inTx(()->{facts.acceptEvidence(event(d,1),now.plusSeconds(601));return null;});
        assertThat(f.store.detail(f.hangzhou("crm:store:visit-write","2"),store).businessStatus()).isEqualTo("OPEN");
    }
    @Test void closedObservationEndsOwnershipThroughTheSharedDomainTransition(){
        var d=draft("CLOSED");f.inTx(()->{facts.acceptEvidence(event(d,1),now.plusSeconds(601));return null;});
        var row=f.store.detail(f.hangzhou("crm:store:visit-write","2"),store);assertThat(row.businessStatus()).isEqualTo("CLOSED");assertThat(row.currentOwnershipId()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT end_reason FROM crm_store_ownership WHERE tenant_id=? AND id=?",String.class,f.tenant,visit.ownershipId())).isEqualTo("STORE_CLOSED");
    }
    @Test void contactCanBeAddedWithoutPhoneAndRemainsUnverified(){
        var contacts=new JdbcStoreContactStore(f.jdbc,f.store);var scope=f.hangzhou("crm:store:visit-write","2");
        var result=f.inTx(()->contacts.create(scope,store,new StoreContactModels.Create(UUID.randomUUID().toString(),"前台小李","店员",false,null,null),now));
        assertThat(result.methods()).isEmpty();assertThat(result.wecomRelations()).isEmpty();
    }
    Evidence cancelled(long revision) {return new Evidence(f.tenant,store,visit.ownershipId(),visit.id(),capture,"E2",policy,revision,now.plusSeconds(revision+60),visit.visitedAt(),now.plusSeconds(60),null,null,null,0,0L,null,null,null,false,null,now.plusSeconds(60),"用户取消拜访",null,null);}
    @Test void cancellationReleasesActiveVisitWithoutUpdatingStoreOrRewardingItAndLateEvidenceCannotReviveIt(){
        var before=f.store.detail(f.hangzhou("crm:store:visit-write","2"),store);
        f.inTx(()->{facts.acceptEvidence(cancelled(2),now.plusSeconds(62));return null;});
        assertThat(f.jdbc.queryForObject("SELECT status_code FROM crm_visit WHERE tenant_id=? AND id=?",String.class,f.tenant,visit.id())).isEqualTo("INVALID");
        assertThat(f.jdbc.queryForObject("SELECT review_source FROM crm_visit WHERE tenant_id=? AND id=?",String.class,f.tenant,visit.id())).isEqualTo("CANCELLED");
        assertThat(projection.syncStatus(f.tenant,visit.id())).isEqualTo("NONE");
        assertThat(f.store.detail(f.hangzhou("crm:store:visit-write","2"),store).businessStatus()).isEqualTo(before.businessStatus());
        f.inTx(()->{facts.acceptEvidence(event(draft("CLOSED"),1),now.plusSeconds(63));facts.acceptEvidence(cancelled(3),now.plusSeconds(64));return null;});
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM crm_store_event WHERE tenant_id=? AND event_type='VISIT_CANCELLED'",Integer.class,f.tenant)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND status_code='IN_PROGRESS'",Integer.class,f.tenant)).isZero();
        assertThat(projection.syncStatus(f.tenant,visit.id())).isEqualTo("NONE");
    }
    @Test void workDayCutoffReleasesVisitForReviewWithoutInventingCheckoutOrApplyingDraft() {
        var cutoff = new Evidence(f.tenant,store,visit.ownershipId(),visit.id(),capture,"E2",policy,1,now.plusSeconds(60),visit.visitedAt(),null,null,null,null,0,0L,null,null,null,false,null,null,null,null,"WORK_DAY_CUTOFF");
        f.inTx(()->{facts.acceptEvidence(cutoff,now.plusSeconds(61));return null;});
        var row=f.jdbc.queryForMap("SELECT status_code,review_reason,checked_out_at,submitted_at FROM crm_visit WHERE tenant_id=? AND id=?",f.tenant,visit.id());
        assertThat(row.get("status_code")).isEqualTo("SUBMITTED");
        assertThat(row.get("review_reason")).isEqualTo("WORK_DAY_CUTOFF");
        assertThat(row.get("checked_out_at")).isNull();assertThat(row.get("submitted_at")).isNull();
        f.inTx(()->{facts.acceptEvidence(event(draft("CLOSED"),2),now.plusSeconds(601));return null;});
        assertThat(projection.syncStatus(f.tenant,visit.id())).isEqualTo("NONE");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND status_code='EFFECTIVE'",Integer.class,f.tenant)).isZero();
    }
    @Test void cancellationCannotOverwriteSubmittedEvidence(){
        f.inTx(()->{facts.acceptEvidence(event(draft("OPEN"),1),now.plusSeconds(601));return null;});
        assertThatThrownBy(()->f.inTx(()->{facts.acceptEvidence(cancelled(2),now.plusSeconds(602));return null;})).hasMessageContaining("已提交拜访");
        assertThat(f.jdbc.queryForObject("SELECT capture_revision FROM crm_visit WHERE tenant_id=? AND id=?",Long.class,f.tenant,visit.id())).isEqualTo(1);
    }
}
