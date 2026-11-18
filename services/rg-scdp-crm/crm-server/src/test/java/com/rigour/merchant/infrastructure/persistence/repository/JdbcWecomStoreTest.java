package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.WecomModels.*;
import com.rigour.merchant.api.v1.model.StoreContactModels.Create;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.StateConflictException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class JdbcWecomStoreTest {
    static JdbcWecomStore wecom; static JdbcStoreContactStore contacts;
    JdbcStorePoolStoreTest f; String store; Instant now=Instant.parse("2026-11-17T08:00:00Z");
    @BeforeAll static void setup(){JdbcStorePoolStoreTest.setup();contacts=new JdbcStoreContactStore(JdbcStorePoolStoreTest.jdbc,JdbcStorePoolStoreTest.store);wecom=new JdbcWecomStore(JdbcStorePoolStoreTest.jdbc,contacts);}
    @AfterAll static void stop(){JdbcStorePoolStoreTest.stopContainer();}
    @BeforeEach void init(){f=new JdbcStorePoolStoreTest();f.tenant();store=f.create("330105","星河门店");f.claim(store,f.hangzhou("crm:pool:claim","2"));}
    CustomerFact customer(String id,String origin){return new CustomerFact(id,"王店长",now.minusSeconds(10),origin);}
    void sync(String employee,long sequence,CustomerFact... people){f.inTx(()->{wecom.accept(new Snapshot(f.tenant,"corp:app",employee,"销售"+employee,sequence,now,true,List.of(people)),now);return null;});}
    Page rows(String employee){return wecom.list(f.hangzhou("crm:pool:private-read",employee),true,false,null,20);}
    @Test void noConnectionIsUnknownAndBaselineIsNotAcquisition(){
        var s=f.hangzhou("crm:pool:private-read","2");assertThat(wecom.summary(s,true,now.minusSeconds(100),now).activeContacts()).isNull();
        sync("2",1,customer("first","NEW"));var result=wecom.summary(s,true,now.minusSeconds(100),now);
        assertThat(result.activeContacts()).isEqualTo(1);assertThat(result.newContacts()).isZero();assertThat(result.pendingContacts()).isEqualTo(1);
        sync("2",2,customer("first","NEW"),customer("second","NEW"));assertThat(wecom.summary(s,true,now.minusSeconds(100),now).newContacts()).isEqualTo(1);
        sync("2",3);assertThat(rows("2").total()).isZero();assertThat(wecom.summary(s,true,now.minusSeconds(100),now).newContacts()).isEqualTo(1);
        sync("2",4,new CustomerFact("second","王店长",now,"NEW"));assertThat(wecom.summary(s,true,now.minusSeconds(100),now).newContacts()).isEqualTo(1);
    }
    @Test void explicitBindingReusesPersonAndDeduplicatesSalesAndPagination(){
        sync("2",1,customer("first","BASELINE"),customer("second","BASELINE"));sync("3",1,customer("first","BASELINE"));
        var s=f.hangzhou("crm:store:visit-write","2");var p=f.inTx(()->contacts.create(s,store,new Create(UUID.randomUUID().toString(),"王老板","老板",true,null,null),now));
        var before=f.store.detail(s,store);
        var list=rows("2");for(var identity:list.items())f.inTx(()->wecom.bind(s,identity.id(),new Bind(UUID.randomUUID().toString(),store,p.id(),null,"老板","已现场核实同一人"),now));
        assertThat(rows("2").total()).isEqualTo(1);assertThat(rows("2").items().getFirst().stores()).extracting(Store::name).containsExactly("星河门店");
        assertThat(contacts.list(s,store)).hasSize(1);assertThat(contacts.list(s,store).getFirst().wecomRelations()).hasSize(2);
        var all=f.national("crm:store:manage","1");assertThat(wecom.summary(all,false,now.minusSeconds(100),now).activeContacts()).isEqualTo(1);
        assertThat(f.store.detail(s,store).deadlineAt()).isEqualTo(before.deadlineAt());
        assertThat(wecom.list(s,true,true,null,1).total()).isZero();
    }
    @Test void creationWithoutPhoneIsIdempotentAndCannotBindSomeoneElsesRelation(){
        sync("2",1,customer("one","BASELINE"));var identity=rows("2").items().getFirst().id();var s=f.hangzhou("crm:store:visit-write","2");
        var command=new Bind(UUID.randomUUID().toString(),store,null,"张老板","老板","本人确认负责门店");
        var first=f.inTx(()->wecom.bind(s,identity,command,now));var retry=f.inTx(()->wecom.bind(s,identity,command,now));
        assertThat(first.id()).isEqualTo(retry.id());assertThat(first.methods()).isEmpty();assertThat(first.wecomRelations()).hasSize(1);
        assertThatThrownBy(()->f.inTx(()->wecom.bind(f.hangzhou("crm:store:visit-write","3"),identity,command,now))).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->f.inTx(()->wecom.bind(s,identity,new Bind(command.requestId(),store,null,"另一人","老板","变化的命令"),now))).isInstanceOf(StateConflictException.class);
        f.tenant=UUID.randomUUID().toString();assertThat(rows("2").total()).isZero();
    }
    @Test void staleSnapshotsCannotResurrectDeletedRelationsAndTotalIgnoresPageSize(){
        sync("2",1,customer("one","BASELINE"),customer("two","BASELINE"));var s=f.hangzhou("crm:pool:private-read","2");
        var page=wecom.list(s,true,false,null,1);assertThat(page.total()).isEqualTo(2);assertThat(page.items()).hasSize(1);assertThat(page.nextCursor()).isNotNull();
        assertThat(wecom.list(s,true,false,page.nextCursor(),1).items().getFirst().id()).isNotEqualTo(page.items().getFirst().id());
        sync("2",2);sync("2",1,customer("one","BASELINE"));assertThat(rows("2").total()).isZero();
        assertThatThrownBy(()->sync("2",2,customer("one","BASELINE"))).isInstanceOf(StateConflictException.class);
    }
}
