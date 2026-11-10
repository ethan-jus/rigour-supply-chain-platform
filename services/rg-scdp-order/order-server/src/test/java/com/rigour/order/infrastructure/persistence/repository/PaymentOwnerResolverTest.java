package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.OrderAttributionClient;
import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PaymentOwnerResolverTest {
    final String tenant = UUID.randomUUID().toString();
    final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    final OrderAttributionClient owners = mock(OrderAttributionClient.class);
    @BeforeEach void prepare() {
        jdbc.execute("CREATE TABLE order_payment_record(id BIGINT,tenant_id VARCHAR,customer_id BIGINT,payment_time TIMESTAMP,deleted INT,payment_owner_code VARCHAR,payment_owner_name VARCHAR,payment_owner_evidence VARCHAR,payment_owner_department_id BIGINT,payment_owner_department_name VARCHAR,payment_owner_region_code VARCHAR,updated_time TIMESTAMP,revision INT,paid_amount DECIMAL(12,2),collector_staff_code VARCHAR)");
        jdbc.update("INSERT INTO order_payment_record(id,tenant_id,customer_id,payment_time,deleted,revision,paid_amount,collector_staff_code) VALUES(1,?,2,'2026-11-10 08:00:00',0,1,156,'ORDER-SELLER')",tenant);
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",UUID.randomUUID(),UUID.fromString(tenant),null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of()));
    }
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    CustomerOrderAttributionView owner(String t,long customer,String code,String name,boolean usable) {
        return new CustomerOrderAttributionView(t,customer,"C2","客户",code,name,7L,"杭州",List.of(7L),"HZ",List.of("HZ"),"version1",1,1,1,Instant.now(),usable,usable?null:"员工不在有效在职状态");
    }
    @Test void freezesCurrentCustomerOwnerEvenWhenNotEligibleForNewOrdersAndNeverOverwrites() {
        when(owners.resolve(any(),eq(2L))).thenReturn(owner(tenant,2,"CURRENT","当前主责",false));
        var resolver = new PaymentOwnerResolver(jdbc,owners);
        resolver.resolve(tenant,1);
        assertThat(jdbc.queryForObject("SELECT payment_owner_code FROM order_payment_record",String.class)).isEqualTo("CURRENT");
        assertThat(jdbc.queryForObject("SELECT payment_owner_department_id FROM order_payment_record",Long.class)).isEqualTo(7);
        when(owners.resolve(any(),eq(2L))).thenReturn(owner(tenant,2,"LATER","后来主责",true));
        resolver.resolve(tenant,1);
        verify(owners,times(1)).resolve(any(),eq(2L));
        assertThat(jdbc.queryForObject("SELECT collector_staff_code FROM order_payment_record",String.class)).isEqualTo("ORDER-SELLER");
        assertThat(jdbc.queryForObject("SELECT paid_amount FROM order_payment_record",java.math.BigDecimal.class)).isEqualByComparingTo("156");
    }
    @Test void missingOrMismatchedIdentityStaysUnassignedWithoutGuessing() {
        for (var owner : List.of(owner(tenant,2,null,null,false),owner(UUID.randomUUID().toString(),2,"X","错租户",true),owner(tenant,3,"X","错客户",true))) {
            when(owners.resolve(any(),eq(2L))).thenReturn(owner);
            new PaymentOwnerResolver(jdbc,owners).resolve(tenant,1);
            assertThat(jdbc.queryForObject("SELECT payment_owner_code FROM order_payment_record",String.class)).isNull();
        }
    }
}
