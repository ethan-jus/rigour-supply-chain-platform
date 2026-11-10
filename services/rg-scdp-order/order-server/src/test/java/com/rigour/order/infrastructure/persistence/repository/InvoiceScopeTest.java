package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.OrderInvoiceStore.InvoicePageCriteria;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceScopeTest {
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @Test void invoiceOnlyListAndCountsRespectTenantAndVisibleOrders() {
        var user=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,UUID.randomUUID(),user,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("order:invoice:read")));
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE order_sales_order(tenant_id VARCHAR,id BIGINT,deleted INT,customer_name_snapshot VARCHAR)");
        jdbc.execute("CREATE TABLE order_invoice(tenant_id VARCHAR,id BIGINT,sales_order_id BIGINT,order_no VARCHAR,title VARCHAR,invoice_type VARCHAR,amount DECIMAL,status VARCHAR,applied_by VARCHAR,applied_at TIMESTAMP,invoice_no VARCHAR,invoiced_at TIMESTAMP,attachment_keys_json VARCHAR,updated_at TIMESTAMP,deleted INT)");
        jdbc.update("INSERT INTO order_sales_order VALUES('t',1,0,'可见'),('t',2,0,'不可见'),('other',1,0,'其他租户')");
        jdbc.update("INSERT INTO order_invoice(tenant_id,id,sales_order_id,order_no,status,deleted) VALUES('t',1,1,'ONE','PENDING',0),('t',2,2,'TWO','PENDING',0),('other',3,1,'OTHER','PENDING',0)");
        var scope=mock(OrderDataScope.class);
        when(scope.predicate("order:invoice:read","o.",null)).thenReturn(new OrderDataScope.Predicate("o.id=?",List.of(1L)));
        var store=new JdbcOrderInvoiceStore(jdbc,scope);
        var criteria=new InvoicePageCriteria(null,null,null,null,null);
        var page=store.page("t",0,20,criteria);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting("orderNo").containsExactly("ONE");
        assertThat(store.statusCounts("t",criteria)).containsExactlyEntriesOf(Map.of("PENDING",1L));
    }
}
