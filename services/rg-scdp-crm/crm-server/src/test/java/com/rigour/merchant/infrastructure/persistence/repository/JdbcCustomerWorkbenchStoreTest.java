package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreContactModels.*;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class JdbcCustomerWorkbenchStoreTest {
 static JdbcCustomerWorkbenchStore workbench;static JdbcStoreContactStore contacts;
 JdbcStorePoolStoreTest fixture;
 final Instant from=Instant.parse("2026-10-31T16:00:00Z"),to=Instant.parse("2026-11-30T16:00:00Z");
 @BeforeAll static void setup(){JdbcStorePoolStoreTest.setup();workbench=new JdbcCustomerWorkbenchStore(JdbcStorePoolStoreTest.jdbc);contacts=new JdbcStoreContactStore(JdbcStorePoolStoreTest.jdbc,JdbcStorePoolStoreTest.store);}
 @AfterAll static void stop(){JdbcStorePoolStoreTest.stopContainer();}
 @BeforeEach void init(){fixture=new JdbcStorePoolStoreTest();fixture.tenant();}
 StoreScope scope(){return fixture.hangzhou("crm:pool:private-read","2");}
 String store(String name){return fixture.create("330105",name);}
 void order(long id,String store,String employee,String time,boolean valid){fixture.jdbc.update("INSERT INTO crm_store_order_fact(tenant_id,order_id,store_id,ownership_id,salesperson_id,source_sequence,effective_at,valid_order,pending_authorization,observed_at,snapshot_json) VALUES(?,?,?,1,?,1,?,?,0,UTC_TIMESTAMP(6),'{\"payableAmount\":100}')",fixture.tenant,id,store,employee,Timestamp.from(Instant.parse(time)),valid);}
 void first(String store,String date){fixture.jdbc.update("UPDATE crm_store_summary SET first_cooperated_at=? WHERE tenant_id=? AND store_id=?",date==null?null:Timestamp.from(Instant.parse(date)),fixture.tenant,store);}
 long count(String kind){long value=workbench.customerCount(scope(),kind,from,to);assertThat(value).isEqualTo(workbench.customers(scope(),kind,from,to,null,null,null,20).total());return value;}
 @Test void newCustomerBuyingTwiceIsNotCrossPeriodReturningAndHistoryIncludesOtherSalespeople(){
  String fresh=store("新客"),old=store("老客"),samePeriodOther=store("本期其他人首单"),unknown=store("未知历史");
  order(1,fresh,"2","2026-11-01T00:00:00Z",true);order(2,fresh,"2","2026-11-03T00:00:00Z",true);first(fresh,"2026-11-01T00:00:00Z");
  order(3,old,"3","2026-10-01T00:00:00Z",true);order(4,old,"2","2026-11-02T00:00:00Z",true);first(old,"2026-10-01T00:00:00Z");
  order(5,samePeriodOther,"3","2026-11-01T00:00:00Z",true);order(6,samePeriodOther,"2","2026-11-02T00:00:00Z",true);first(samePeriodOther,"2026-11-01T00:00:00Z");
  order(7,unknown,"2","2026-11-02T00:00:00Z",true);
  assertThat(count("PURCHASING")).isEqualTo(4);assertThat(count("NEW")).isEqualTo(1);assertThat(count("RETURNING")).isEqualTo(1);assertThat(count("UNKNOWN_HISTORY")).isEqualTo(1);
 }
 @Test void tenantValidityPeriodAndPaginationUseSameCohort(){
  String a=store("甲"),b=store("乙"),cancelled=store("取消");
  order(1,a,"2","2026-10-31T16:00:00Z",true);order(2,b,"2","2026-11-30T15:59:59Z",true);order(3,cancelled,"2","2026-11-01T00:00:00Z",false);
  order(4,cancelled,"2","2026-11-30T16:00:00Z",true);
  var page=workbench.customers(scope(),"PURCHASING",from,to,null,null,null,1);
  assertThat(page.total()).isEqualTo(2);assertThat(page.items()).hasSize(1);assertThat(page.nextCursor()).isNotNull();
  var next=workbench.customers(scope(),"PURCHASING",from,to,null,null,page.nextCursor(),1);
  assertThat(next.total()).isEqualTo(2);assertThat(next.items()).hasSize(1);assertThat(next.items().getFirst().id()).isNotEqualTo(page.items().getFirst().id());
  fixture.tenant=UUID.randomUUID().toString();assertThat(count("COOPERATED")).isZero();
 }
 @Test void historicalRelationshipDoesNotGrantPrivateContactsOrOtherSalespersonOrders(){
  String id=store("已转交");fixture.claim(id,fixture.hangzhou("crm:pool:claim","3"));order(1,id,"2","2026-11-02T00:00:00Z",true);order(2,id,"3","2026-11-03T00:00:00Z",true);
  var history=workbench.customers(scope(),"COOPERATED",from,to,null,"TRANSFERRED",null,20);
  assertThat(history.items()).singleElement().satisfies(row->{assertThat(row.relation()).isEqualTo("TRANSFERRED");assertThat(row.orderCount()).isEqualTo(1);});
  assertThat(workbench.orders(scope(),id,null,20).total()).isEqualTo(1);
  assertThatThrownBy(()->contacts.list(scope(),id)).isInstanceOf(AuthorizationDeniedException.class);
  assertThatThrownBy(()->workbench.orders(scope(),store("未合作"),null,20)).isInstanceOf(AuthorizationDeniedException.class);
 }
 @Test void onePersonAcrossTwoStoresCountsOnceAndPendingCountMatchesList(){
  String a=store("一店"),b=store("二店");fixture.claim(a,fixture.hangzhou("crm:pool:claim","2"));fixture.claim(b,fixture.hangzhou("crm:pool:claim","2"));
  var edit=fixture.hangzhou("crm:store:visit-write","2");
  var person=fixture.inTx(()->contacts.create(edit,a,new Create(UUID.randomUUID().toString(),"王老板","老板",true,"13800000000",null),from.plusSeconds(60)));
  fixture.inTx(()->contacts.link(edit,b,new Link(UUID.randomUUID().toString(),a,person.id(),"老板",true,"确认两家店"),from.plusSeconds(70)));
  var list=workbench.contacts(scope(),true,from,to,null,null,20);
  assertThat(list.total()).isEqualTo(1);assertThat(workbench.newContactCount(scope(),from,to)).isEqualTo(list.total());assertThat(list.items()).hasSize(1);
  assertThat(list.items().getFirst().stores()).extracting(s->s.id()).containsExactlyInAnyOrder(a,b);
  assertThat(workbench.contactPending(scope())).isEqualTo(2);assertThat(count("CONTACT_PENDING")).isEqualTo(2);
  var method=person.methods().getFirst();fixture.inTx(()->contacts.verifyMethod(edit,a,person.id(),method.id(),new VerifyMethod(UUID.randomUUID().toString(),method.revision(),"VERIFIED","ON_SITE","本人确认"),Instant.now()));
  assertThat(workbench.contactPending(scope())).isZero();assertThat(count("CONTACT_PENDING")).isZero();
  assertThat(workbench.contacts(fixture.hangzhou("crm:pool:private-read","3"),false,from,to,null,null,20).total()).isZero();
 }
}
