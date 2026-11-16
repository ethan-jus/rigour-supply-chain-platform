package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreContactModels.*;
import com.rigour.merchant.api.v1.model.StoreModels.Release;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.StateConflictException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class JdbcStoreContactStoreTest {
    static JdbcStoreContactStore contacts;
    JdbcStorePoolStoreTest fixture;
    String store;
    @BeforeAll static void setup(){JdbcStorePoolStoreTest.setup();contacts=new JdbcStoreContactStore(JdbcStorePoolStoreTest.jdbc,JdbcStorePoolStoreTest.store);}
    @AfterAll static void stop(){JdbcStorePoolStoreTest.stopContainer();}
    @BeforeEach void init(){fixture=new JdbcStorePoolStoreTest();fixture.tenant();store=fixture.create("330105","联系人联调门店");fixture.claim(store,fixture.hangzhou("crm:pool:claim","2"));}
    Create create(String name,boolean primary){return new Create(UUID.randomUUID().toString(),name,"店长",primary,"13800000000","测试昵称");}
    Contact add(String name,boolean primary){return fixture.inTx(()->contacts.create(fixture.hangzhou("crm:store:visit-write","2"),store,create(name,primary),Instant.now()));}
    @Test void contactChangesAdvanceQueryVersionOnlyOnCommit() {
        var versions=new com.rigour.merchant.infrastructure.cache.JdbcCacheVersions(fixture.jdbc);
        var cache=new com.rigour.shared.cache.QueryCache(null,versions,()->null,()->true,null,"test",0,1);
        org.springframework.test.util.ReflectionTestUtils.setField(contacts,"queryCache",cache);
        long before=versions.current(fixture.tenant);
        try {
            add("缓存失效",true);assertThat(versions.current(fixture.tenant)).isEqualTo(before+1);
            assertThatThrownBy(()->fixture.inTx(()->{add("回滚",false);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
            assertThat(versions.current(fixture.tenant)).isEqualTo(before+1);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(contacts,"queryCache",com.rigour.shared.cache.QueryCache.disabled());}
    }
    @Test void twoPeopleSharePhoneWithoutMergeAndOnlyOnePrimary(){
        var first=add("前台小李",true);var second=add("王店长",true);
        var rows=contacts.list(fixture.hangzhou("crm:pool:private-read","2"),store);
        assertThat(rows).hasSize(2);assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(rows.stream().filter(Contact::primary)).extracting(Contact::id).containsExactly(second.id());
        assertThat(rows).allSatisfy(c->assertThat(c.methods()).allMatch(m->"PENDING".equals(m.status())));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM crm_store_master WHERE tenant_id=?",Integer.class,fixture.tenant)).isEqualTo(1);
    }
    @Test void retriesDoNotDuplicateAndChangingBodyWithSameKeyConflicts(){
        var c=create("王店长",false);var s=fixture.hangzhou("crm:store:visit-write","2");
        var first=fixture.inTx(()->contacts.create(s,store,c,Instant.now()));
        assertThat(fixture.inTx(()->contacts.create(s,store,c,Instant.now()))).isEqualTo(first);
        assertThatThrownBy(()->fixture.inTx(()->contacts.create(s,store,new Create(c.requestId(),"其他人",c.role(),false,c.phone(),null),Instant.now()))).isInstanceOf(StateConflictException.class);
        assertThat(contacts.list(s,store)).hasSize(1);assertThat(contacts.changes(s,store,first.id())).hasSize(1);
    }
    @Test void addingAndVerifyingPhoneDoesNotChangeProtectionAndNicknameCannotVerify(){
        var s=fixture.hangzhou("crm:store:visit-write","2");var before=fixture.store.detail(s,store);var p=add("王店长",true);
        var added=fixture.inTx(()->contacts.addMethod(s,store,p.id(),new AddMethod(UUID.randomUUID().toString(),"PHONE","057188888888","后续取得座机"),Instant.now()));
        var phone=added.methods().stream().filter(m->m.value().startsWith("0571")).findFirst().orElseThrow();
        var verified=fixture.inTx(()->contacts.verifyMethod(s,store,p.id(),phone.id(),new VerifyMethod(UUID.randomUUID().toString(),phone.revision(),"VERIFIED","PHONE_CALL","已拨通并确认本人"),Instant.now()));
        assertThat(verified.methods()).anySatisfy(m->{assertThat(m.id()).isEqualTo(phone.id());assertThat(m.status()).isEqualTo("VERIFIED");assertThat(m.verifiedBy()).isEqualTo("account-2");assertThat(m.verifiedAt()).isNotNull();});
        var hint=p.methods().stream().filter(m->m.type().equals("WECHAT_HINT")).findFirst().orElseThrow();
        assertThatThrownBy(()->fixture.inTx(()->contacts.verifyMethod(s,store,p.id(),hint.id(),new VerifyMethod(UUID.randomUUID().toString(),1,"VERIFIED","ON_SITE","昵称不是建联证据"),Instant.now()))).isInstanceOf(StateConflictException.class);
        var after=fixture.store.detail(s,store);assertThat(after.deadlineAt()).isEqualTo(before.deadlineAt());assertThat(after.currentOwnershipId()).isEqualTo(before.currentOwnershipId());
        assertThat(contacts.list(s,store)).hasSize(1);
    }
    @Test void samePersonCanBeExplicitlyLinkedToTwoStoresAndLeavingOneDoesNotInvalidatePhone(){
        var s=fixture.hangzhou("crm:store:visit-write","2");var p=add("王老板",true);String other=fixture.create("330105","第二门店");fixture.claim(other,fixture.hangzhou("crm:pool:claim","2"));
        var linked=fixture.inTx(()->contacts.link(s,other,new Link(UUID.randomUUID().toString(),store,p.id(),"老板",true,"本人确认负责两家门店"),Instant.now()));
        assertThat(linked.id()).isEqualTo(p.id());assertThat(linked.role()).isEqualTo("老板");
        fixture.inTx(()->contacts.update(s,store,p.id(),new Update(UUID.randomUUID().toString(),p.revision(),p.personRevision(),p.name(),p.role(),false,"LEFT","已不负责此店"),Instant.now()));
        assertThat(contacts.list(s,other).getFirst().status()).isEqualTo("ACTIVE");
        assertThat(contacts.list(s,other).getFirst().methods()).isEqualTo(p.methods());
    }
    @Test void publicOtherOwnerCrossCityAndOtherTenantCannotReadPrivateContacts(){
        add("王店长",true);
        assertThatThrownBy(()->contacts.list(fixture.hangzhou("crm:pool:private-read","3"),store)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->contacts.list(fixture.hangzhou("crm:pool:public-read","2"),store)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->fixture.inTx(()->contacts.create(fixture.hangzhou("crm:store:visit-write","3"),store,create("越权",false),Instant.now()))).isInstanceOf(AuthorizationDeniedException.class);
        String other=fixture.create("620102","异地门店");fixture.claim(other,fixture.national("crm:pool:claim","2"));
        assertThatThrownBy(()->contacts.list(fixture.hangzhou("crm:pool:private-read","2"),other)).isInstanceOf(AuthorizationDeniedException.class);
        fixture.tenant=UUID.randomUUID().toString();assertThatThrownBy(()->contacts.list(fixture.national("crm:store:manage","2"),store)).isInstanceOf(AuthorizationDeniedException.class);
    }
    @Test void releaseImmediatelyRevokesPrivateAccessIncludingIdempotentReplay(){
        var s=fixture.hangzhou("crm:store:visit-write","2");var c=create("王店长",true);fixture.inTx(()->contacts.create(s,store,c,Instant.now()));
        var row=fixture.store.detail(s,store);fixture.inTx(()->{fixture.store.release(fixture.hangzhou("crm:pool:release","2"),store,new Release(UUID.randomUUID().toString(),row.revision(),"测试释放"));return null;});
        assertThatThrownBy(()->contacts.list(s,store)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->fixture.inTx(()->contacts.create(s,store,c,Instant.now()))).isInstanceOf(AuthorizationDeniedException.class);
    }
    @Test void receptionCanBeEmptyAndSnapshotsPreserveIdentityAtVisitTime(){
        var s=fixture.hangzhou("crm:store:visit-write","2");var p=add("王店长",true);String visit="1";
        var row=fixture.store.detail(s,store);
        fixture.jdbc.update("INSERT INTO crm_visit(id,tenant_id,store_id,ownership_id,salesperson_id,visited_at,accepted_at,checkin_accepted_at,checkin_longitude,checkin_latitude,checkin_coordinate_system,distance_meters,store_snapshot,validation_policy_version,request_id,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),120,30,'GCJ02',0,'{}','TEST',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'test','test')",visit,fixture.tenant,store,row.currentOwnershipId(),"2",UUID.randomUUID().toString());
        var empty=fixture.inTx(()->contacts.reception(s,store,visit,new Reception(UUID.randomUUID().toString(),List.of()),Instant.now()));assertThat(empty).isEmpty();
        var command=new Reception(UUID.randomUUID().toString(),List.of(p.id()));fixture.inTx(()->contacts.reception(s,store,visit,command,Instant.now()));
        fixture.inTx(()->contacts.update(s,store,p.id(),new Update(UUID.randomUUID().toString(),p.revision(),p.personRevision(),"王老板","老板",true,"ACTIVE","核实名字和角色"),Instant.now()));
        assertThat(contacts.reception(s,store,visit).getFirst().name()).isEqualTo("王店长");
        fixture.jdbc.update("UPDATE crm_visit SET status_code='SUBMITTED' WHERE tenant_id=? AND id=?",fixture.tenant,visit);
        assertThat(fixture.inTx(()->contacts.reception(s,store,visit,command,Instant.now()))).hasSize(1);
        assertThatThrownBy(()->fixture.inTx(()->contacts.reception(s,store,visit,new Reception(UUID.randomUUID().toString(),List.of()),Instant.now()))).isInstanceOf(StateConflictException.class);
    }
}
