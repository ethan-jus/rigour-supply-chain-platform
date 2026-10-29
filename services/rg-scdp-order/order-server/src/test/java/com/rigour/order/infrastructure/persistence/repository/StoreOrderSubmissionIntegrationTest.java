package com.rigour.order.infrastructure.persistence.repository;
import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreWorkModels.OrderPermit;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
@Testcontainers
class StoreOrderSubmissionIntegrationTest {
    @Container static MySQLContainer mysql=new MySQLContainer("mysql:8.4");
    static JdbcTemplate db;static TransactionTemplate tx;
    JdbcStoreOrderSubmissionStore store;String tenant,user;long id;Instant now;
    @BeforeAll static void database(){var ds=new DriverManagerDataSource(mysql.getJdbcUrl()+"?connectionTimeZone=UTC",mysql.getUsername(),mysql.getPassword());Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/bootstrap").load().migrate();db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));}
    <T>T transaction(Supplier<T> work){return tx.execute(s->work.get());}
    @BeforeEach void fixture(){tenant=UUID.randomUUID().toString();user=UUID.randomUUID().toString();now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);store=new JdbcStoreOrderSubmissionStore(db,org.mockito.Mockito.mock(OrderDataScope.class));db.update("INSERT INTO order_sales_order(tenant_id,order_no,customer_id,customer_name_snapshot,order_date,payable_amount,created_by) VALUES(?,'DEV-STORE',1,'测试买方',?,5000,?)",tenant,Timestamp.from(now),user);id=db.queryForObject("SELECT id FROM order_sales_order WHERE tenant_id=?",Long.class,tenant);}
    @Test void pendingDoesNotCountAsCooperationAndExpiresAtomically(){String request=UUID.randomUUID().toString();transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now));var first=transaction(()->store.facts(tenant,"100","200",now.plusSeconds(119)));assertThat(first.pendingAuthorization()).isTrue();assertThat(first.orders()).isEmpty();assertThat(first.firstStoreOrderAt()).isNull();assertThat(transaction(()->store.facts(tenant,"100","200",now.plusSeconds(120))).pendingAuthorization()).isFalse();assertThatThrownBy(()->transaction(()->{store.authorize(tenant,user,id,request,new OrderPermit("100","200","1","10","测试",2,now),now.plusSeconds(121));return null;})).hasMessageContaining("过期");}
    @Test void requestCannotBeReusedForAnotherStoreOrUser(){String request=UUID.randomUUID().toString();var first=transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now));assertThat(transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now.plusSeconds(60)))).isEqualTo(first);assertThatThrownBy(()->transaction(()->store.reserve(tenant,user,id,1,"101","200",request,now))).hasMessageContaining("其他门店");assertThatThrownBy(()->transaction(()->store.reserve(tenant,"another",id,1,"100","200",request,now))).hasMessageContaining("其他门店");}
    @Test void authoritativeFactsExcludeDraftCancelledOtherTenantAndPriorCycleFromCurrentProtection(){String request=UUID.randomUUID().toString();transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now));transaction(()->{store.authorize(tenant,user,id,request,new OrderPermit("100","200","1","10","测试",2,now),now);return null;});db.update("UPDATE order_sales_order SET order_status_code='SUBMITTED',store_submission_status='COMPLETED',store_effective_at=store_authorized_at WHERE id=?",id);var own=transaction(()->store.facts(tenant,"100","200",now));assertThat(own.orders()).hasSize(1);assertThat(own.firstStoreOrderAt()).isEqualTo(now);var next=transaction(()->store.facts(tenant,"100","201",now));assertThat(next.orders()).isEmpty();assertThat(next.firstStoreOrderAt()).isEqualTo(now);assertThat(transaction(()->store.facts(UUID.randomUUID().toString(),"100","200",now)).orders()).isEmpty();db.update("UPDATE order_sales_order SET order_status_code='CANCELLED' WHERE id=?",id);assertThat(transaction(()->store.facts(tenant,"100","200",now)).firstStoreOrderAt()).isNull();}
    @Test void normalRefundDoesNotChangeCooperationTime(){String request=UUID.randomUUID().toString();transaction(()->store.reserve(tenant,user,id,1,"100","200",request,now));db.update("UPDATE order_sales_order SET order_status_code='COMPLETED',store_submission_status='COMPLETED',store_effective_at=?,store_salesperson_id=1,paid_amount=0 WHERE id=?",Timestamp.from(now),id);db.update("INSERT INTO order_refund_record(tenant_id,refund_no,order_id,refund_time,refund_amount) VALUES(?,'DEV-REFUND',?,?,5000)",tenant,id,Timestamp.from(now.plusSeconds(1800)));assertThat(transaction(()->store.facts(tenant,"100","200",now.plusSeconds(3600))).orders().getFirst().effectiveAt()).isEqualTo(now);}
}
