package com.rigour.order.infrastructure.persistence.repository;
import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreWorkModels.OrderPermit;
import com.rigour.merchant.api.v1.model.StoreOrderResult;
import com.rigour.order.infrastructure.integration.CrmOrderDeliveryScheduler;
import com.rigour.shared.context.*;
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
import tools.jackson.databind.ObjectMapper;
@Testcontainers
class StoreOrderSubmissionIntegrationTest {
    @Container static MySQLContainer mysql=new MySQLContainer("mysql:8.4");
    static JdbcTemplate db;static TransactionTemplate tx;
    JdbcStoreOrderSubmissionStore store;JdbcStoreOrderOutbox outbox;String tenant,user;long id;Instant now;
    @BeforeAll static void database(){var ds=new DriverManagerDataSource(mysql.getJdbcUrl()+"?connectionTimeZone=UTC",mysql.getUsername(),mysql.getPassword());Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/bootstrap").load().migrate();db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));}
    <T>T transaction(Supplier<T> work){return tx.execute(s->work.get());}
    @BeforeEach void fixture(){tenant=UUID.randomUUID().toString();user=UUID.randomUUID().toString();now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);outbox=new JdbcStoreOrderOutbox(db,new ObjectMapper(),Clock.fixed(now,ZoneOffset.UTC));store=new JdbcStoreOrderSubmissionStore(db,org.mockito.Mockito.mock(OrderDataScope.class),outbox);db.update("INSERT INTO order_sales_order(tenant_id,order_no,customer_id,customer_name_snapshot,order_date,payable_amount,created_by) VALUES(?,'DEV-STORE',1,'测试买方',?,5000,?)",tenant,Timestamp.from(now),user);id=db.queryForObject("SELECT id FROM order_sales_order WHERE tenant_id=?",Long.class,tenant);}
    StoreOrderResult latest(){return new ObjectMapper().readValue(db.queryForObject("SELECT payload_json FROM order_crm_result_outbox WHERE tenant_id=? AND order_id=? ORDER BY source_revision DESC LIMIT 1",String.class,tenant,id),StoreOrderResult.class);}
    @Test void pendingExpiryIsAtomicallyDeliveredAndCannotBeAuthorizedLate(){String request=UUID.randomUUID().toString();transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now));assertThat(latest().submissionStatus()).isEqualTo("PENDING_AUTH");transaction(()->{new JdbcStoreOrderOutbox(db,new ObjectMapper(),Clock.fixed(now.plusSeconds(120),ZoneOffset.UTC)).expirePending();return null;});assertThat(latest().submissionStatus()).isEqualTo("EXPIRED");assertThat(latest().sequence()).isEqualTo(2);assertThatThrownBy(()->transaction(()->{store.authorize(tenant,user,id,request,new OrderPermit("100","200","1","10","测试",2,now),now.plusSeconds(121));return null;})).hasMessageContaining("过期");}
    @Test void requestCannotBeReusedForAnotherStoreOrUser(){String request=UUID.randomUUID().toString();var first=transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now));assertThat(transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now.plusSeconds(60)))).isEqualTo(first);assertThat(latest().sequence()).isEqualTo(1);assertThatThrownBy(()->transaction(()->store.reserve(tenant,user,id,1,"101","200",request,now))).hasMessageContaining("其他门店");assertThatThrownBy(()->transaction(()->store.reserve(tenant,"another",id,1,"100","200",request,now))).hasMessageContaining("其他门店");}
    @Test void rollbackRemovesBothBusinessChangeAndPendingMessage(){assertThatThrownBy(()->transaction(()->{store.reserve(tenant,user,id,1,"100","200",UUID.randomUUID().toString(),now);throw new IllegalStateException("rollback");})).hasMessage("rollback");assertThat(db.queryForObject("SELECT store_id FROM order_sales_order WHERE id=?",String.class,id)).isNull();assertThat(db.queryForObject("SELECT COUNT(*) FROM order_crm_result_outbox WHERE tenant_id=?",Long.class,tenant)).isZero();}
    @Test void cancellationKeepsImmutableOriginalAttributionAndAdvancesSourceVersion(){String request=UUID.randomUUID().toString();transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now));transaction(()->{store.authorize(tenant,user,id,request,new OrderPermit("100","200","1","10","测试",2,now),now);db.update("UPDATE order_sales_order SET order_status_code='SUBMITTED',store_submission_status='COMPLETED',store_effective_at=? WHERE id=?",Timestamp.from(now),id);outbox.enqueue(tenant,id);return null;});var first=latest();transaction(()->{db.update("UPDATE order_sales_order SET order_status_code='CANCELLED' WHERE id=?",id);outbox.enqueue(tenant,id);return null;});assertThat(latest().sequence()).isGreaterThan(first.sequence());assertThat(latest().orderStatus()).isEqualTo("CANCELLED");assertThat(latest().salespersonId()).isEqualTo(first.salespersonId());assertThat(latest().effectiveAt()).isEqualTo(first.effectiveAt());}
    @Test void failedCrmDeliveryRemainsDurableAndRetriesWithSamePayload()throws Exception{
        transaction(()->store.reserve(tenant,user,id,1,"100","200",UUID.randomUUID().toString(),now));
        var responseStatus=new java.util.concurrent.atomic.AtomicInteger(503);
        var received=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/crm/store-facts/orders",exchange->{received.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] body="{\"code\":\"OK\",\"data\":null}".getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(responseStatus.get(),body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{
            var keys=new ContextTrustProperties();keys.setKeysBase64(Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));
            var scheduler=new CrmOrderDeliveryScheduler(db,tx.getTransactionManager(),outbox,org.springframework.web.client.RestClient.builder(),new TrustedContextSigner(keys),Clock.fixed(now,ZoneOffset.UTC),"http://127.0.0.1:"+server.getAddress().getPort());
            scheduler.tick();
            assertThat(db.queryForObject("SELECT retry_count FROM order_crm_result_outbox WHERE tenant_id=? AND order_id=?",Integer.class,tenant,id)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT status_code FROM order_crm_result_outbox WHERE tenant_id=? AND order_id=?",String.class,tenant,id)).isEqualTo("PENDING");
            responseStatus.set(200);db.update("UPDATE order_crm_result_outbox SET next_retry_at=NULL WHERE tenant_id=?",tenant);scheduler.tick();
            assertThat(db.queryForObject("SELECT status_code FROM order_crm_result_outbox WHERE tenant_id=? AND order_id=?",String.class,tenant,id)).isEqualTo("DELIVERED");
            var own=received.stream().filter(s->s.contains(tenant)).toList();assertThat(own).hasSize(2);assertThat(own.get(0)).isEqualTo(own.get(1));
        }finally{server.stop(0);}
    }
}
