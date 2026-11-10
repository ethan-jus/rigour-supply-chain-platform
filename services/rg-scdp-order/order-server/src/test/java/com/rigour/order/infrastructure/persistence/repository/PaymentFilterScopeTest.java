package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.PaymentFilterApi.Person;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentFilterScopeTest {
    @Test void peopleAndCreatorsComeOnlyFromVisibleTenantPayments() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:payment_filters;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE order_sales_order(tenant_id VARCHAR, id BIGINT, deleted INT, region_code VARCHAR, source_creator_name VARCHAR)");
        jdbc.execute("CREATE TABLE order_attribution_snapshot(tenant_id VARCHAR,order_id BIGINT,state VARCHAR,region_code VARCHAR)");
        jdbc.execute("CREATE TABLE order_payment_record(tenant_id VARCHAR,order_id BIGINT,deleted INT,payment_owner_code VARCHAR,payment_owner_name VARCHAR,payment_owner_region_code VARCHAR,created_by VARCHAR)");
        jdbc.update("INSERT INTO order_sales_order VALUES('t',1,0,'BJ','visible'),('t',2,0,'SH','hidden'),('other',1,0,'BJ','other')");
        jdbc.update("INSERT INTO order_payment_record VALUES('t',1,0,'E1','visible',NULL,'u'),('t',2,0,'E2','hidden',NULL,'u'),('other',1,0,'E3','other',NULL,'u')");
        var scopes = mock(OrderDataScope.class);
        when(scopes.predicate("order:read","o.",null)).thenReturn(new OrderDataScope.Predicate("o.id=?",List.of(1L)));
        // H2 has no MySQL collation syntax; strip only that syntax, keeping actual bound scope SQL.
        var compatible = spy(jdbc);
        doAnswer(inv -> { jdbc.query(((String)inv.getArgument(0)).replace(" COLLATE utf8mb4_general_ci",""),
                (org.springframework.jdbc.core.RowCallbackHandler)inv.getArgument(1), (Object[])inv.getRawArguments()[2]); return null; })
                .when(compatible).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any(Object[].class));
        var result = new JdbcPaymentFilterStore(compatible,scopes).parties("t");
        assertThat(result.employees()).extracting(Person::employeeCode).containsExactly("E1");
        assertThat(result.creators()).containsExactly("visible");
    }
}
