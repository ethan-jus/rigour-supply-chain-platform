package com.rigour.order.infrastructure.persistence.repository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
class JdbcCustomerOrderStoreTest {
 JdbcTemplate jdbc;OrderDataScope scopes;JdbcCustomerOrderStore store;
 @BeforeEach void setup(){
  jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"));
  jdbc.execute("CREATE TABLE order_sales_order(id BIGINT,tenant_id VARCHAR,store_id BIGINT,owner_employee_code VARCHAR,region_code VARCHAR,deleted INT,order_status_code VARCHAR,payment_status_code VARCHAR,order_date TIMESTAMP,payable_amount DECIMAL(18,2),unpaid_amount DECIMAL(18,2),customer_name_snapshot VARCHAR,order_no VARCHAR)");
  jdbc.execute("CREATE TABLE order_attribution_snapshot(tenant_id VARCHAR,order_id BIGINT,employee_code VARCHAR,state VARCHAR)");
  scopes=mock(OrderDataScope.class);var policy=mock(SupplyAuthorizationView.class);when(policy.employeeCode()).thenReturn("self");when(scopes.policy("order:read")).thenReturn(policy);
  when(scopes.predicate("order:read","o.",null)).thenReturn(new OrderDataScope.Predicate("o.region_code=?",List.of("allowed")));store=new JdbcCustomerOrderStore(jdbc,scopes);
 }
 void order(int id,String tenant,int storeId,String owner,String region,String status,int unpaid){jdbc.update("INSERT INTO order_sales_order VALUES(?,?,?,?,?,0,?,'UNPAID',CURRENT_TIMESTAMP,100,?,'门店',?)",id,tenant,storeId,owner,region,status,unpaid,"O-"+id);}
 @Test void storeSummaryAndPagesKeepTenantFrozenOwnerAndDataScope(){
  order(1,"t",10,"self","allowed","SUBMITTED",40);order(2,"t",10,"changed","allowed","COMPLETED",0);
  jdbc.update("INSERT INTO order_attribution_snapshot VALUES('t',2,'self','FROZEN')");
  order(3,"t",11,"self","allowed","SUBMITTED",100);order(4,"other",10,"self","allowed","SUBMITTED",100);order(5,"t",10,"other","allowed","SUBMITTED",100);order(6,"t",10,"self","blocked","SUBMITTED",100);order(7,"t",10,"self","allowed","CANCELLED",100);
  var result=store.read("t","10",false,0,1);assertThat(result.total()).isEqualTo(2);assertThat(result.amount()).isEqualByComparingTo("200");assertThat(result.unpaid()).isEqualByComparingTo("40");assertThat(result.items()).hasSize(1);
  assertThat(store.read("t","10",false,1,1).items()).hasSize(1);assertThat(store.read("t","10",true,0,20).total()).isEqualTo(1);
 }
 @Test void missingEmployeeDoesNotFallBackToTenantWideOrders(){when(scopes.policy("order:read")).thenReturn(null);assertThatThrownBy(()->store.read("t","10",false,0,20)).isInstanceOf(AuthorizationDeniedException.class);}
}
