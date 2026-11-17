package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.application.port.out.OrderSalesShipmentStore.*;
import com.rigour.order.infrastructure.persistence.mapper.*;
import com.rigour.shared.context.*;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 通过实际 Mapper 核对来源占位发货补明细、后续编辑、租户与版本保护。 */
class SalesShipmentLineReplacementTest {
    SqlSession session;
    JdbcTemplate jdbc;
    MybatisPlusSalesShipmentRepository repository;
    OrderDataScope scopes = mock(OrderDataScope.class);
    UUID connector = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE order_sales_shipment (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(64),shipment_no VARCHAR(50),
                  connector_id VARCHAR(36),source_system_code VARCHAR(64),source_document_no VARCHAR(128),
                  sales_order_id BIGINT,sales_order_no_snapshot VARCHAR(50),customer_id BIGINT,
                  customer_code_snapshot VARCHAR(50),customer_name_snapshot VARCHAR(200),
                  contact_phone_snapshot VARCHAR(50),region_code VARCHAR(64),owner_employee_code VARCHAR(50),
                  warehouse_id BIGINT,stock_out_order_id BIGINT,stock_out_no VARCHAR(50),
                  shipment_status_code VARCHAR(64),logistics_company VARCHAR(120),tracking_no VARCHAR(120),
                  ship_time TIMESTAMP,total_quantity DECIMAL(20,6),remark VARCHAR(1000),revision INT,
                  created_by VARCHAR(50),created_time TIMESTAMP,updated_by VARCHAR(50),updated_time TIMESTAMP,
                  deleted INT,source_warehouse_name VARCHAR(200),stock_out_time TIMESTAMP,
                  source_updated_at TIMESTAMP,source_payload_json LONGTEXT)
                """);
        jdbc.execute("""
                CREATE TABLE order_sales_shipment_line (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(64),shipment_id BIGINT,
                  sales_order_line_id BIGINT,line_no INT,product_id BIGINT,product_variant_id BIGINT,
                  product_code_snapshot VARCHAR(50),sku_code_snapshot VARCHAR(50),product_name_snapshot VARCHAR(200),
                  specification_snapshot VARCHAR(500),unit_code VARCHAR(64),shipped_quantity DECIMAL(20,6),
                  remark VARCHAR(1000),revision INT,created_by VARCHAR(50),created_time TIMESTAMP,
                  updated_by VARCHAR(50),updated_time TIMESTAMP,deleted INT)
                """);
        jdbc.execute("CREATE INDEX idx_shipment_head ON order_sales_shipment_line(tenant_id,shipment_id,line_no)");
        jdbc.execute("""
                CREATE TABLE order_sales_order(id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),shipment_time TIMESTAMP,
                  updated_by VARCHAR(50),updated_time TIMESTAMP,revision INT,deleted INT)
                """);
        jdbc.update("INSERT INTO order_sales_order(id,tenant_id,revision,deleted) VALUES(1,'T',1,0)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(InternalSalesShipmentMapper.class);
        config.addMapper(InternalSalesShipmentLineMapper.class);
        config.addMapper(InternalSalesOrderMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        session = factory.getObject().openSession(true);
        repository = new MybatisPlusSalesShipmentRepository(session.getMapper(InternalSalesShipmentMapper.class),
                session.getMapper(InternalSalesShipmentLineMapper.class),session.getMapper(InternalSalesOrderMapper.class),
                Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"),ZoneOffset.UTC),scopes,jdbc);
        UUID actor = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",actor,UUID.randomUUID(),null,null,
                UUID.randomUUID(),0,0,0,Set.of(),Set.of("order:shipment:create","order:shipment:update")));
    }

    @AfterEach
    void close() {
        TestAuthorizationContext.clear();
        if (session != null) session.close();
    }

    @Test
    void emptySourcePlaceholderReceivesLinesThenEditsSameShipmentWithoutChangingOtherLines() {
        var first = repository.create("T","FH-1",command("FH-1",1,List.of()),"actor");
        var other = repository.create("T","FH-2",command("FH-2",1,List.of(line("8"))),"actor");
        var filled = repository.update("T",first.id(),command("FH-1",1,List.of(line("36"))),"actor");
        assertThat(filled.id()).isEqualTo(first.id());
        assertThat(filled.lines()).singleElement().satisfies(l -> {
            assertThat(l.unitCode()).isEqualTo("BUCKET");
            assertThat(l.shippedQuantity()).isEqualByComparingTo("36");
        });
        var edited = repository.update("T",first.id(),command("FH-1",2,List.of(line("24"))),"actor");
        assertThat(edited.lines()).singleElement().satisfies(l -> assertThat(l.shippedQuantity()).isEqualByComparingTo("24"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_sales_shipment_line WHERE shipment_id=? AND deleted=1",
                Integer.class,first.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT shipped_quantity FROM order_sales_shipment_line WHERE shipment_id=? AND deleted=0",
                BigDecimal.class,other.id())).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_sales_shipment",Integer.class)).isEqualTo(2);
    }

    @Test
    void staleVersionAndWrongTenantCannotReplaceSavedLines() {
        var first = repository.create("T","FH-1",command("FH-1",1,List.of(line("36"))),"actor");
        assertThatThrownBy(() -> repository.update("T",first.id(),command("FH-1",9,List.of(line("1"))),"actor"))
                .hasMessageContaining("已被其他人修改");
        assertThatThrownBy(() -> repository.update("OTHER",first.id(),command("FH-1",1,List.of(line("1"))),"actor"))
                .hasMessageContaining("不存在");
        assertThat(jdbc.queryForObject("SELECT shipped_quantity FROM order_sales_shipment_line WHERE deleted=0",BigDecimal.class))
                .isEqualByComparingTo("36");
        verify(scopes).requireRelated("T",first.id(),"order:shipment:update","order_sales_shipment");
        verify(scopes).requireRelatedOrder("T",1L,"order:shipment:update");
    }

    @Test
    void deletingShipmentRetiresOnlyItsLines() {
        var first = repository.create("T","FH-1",command("FH-1",1,List.of(line("36"))),"actor");
        var other = repository.create("T","FH-2",command("FH-2",1,List.of(line("8"))),"actor");
        repository.delete("T",first.id(),1,"actor");
        assertThat(jdbc.queryForObject("SELECT deleted FROM order_sales_shipment WHERE id=?",Integer.class,first.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted FROM order_sales_shipment_line WHERE shipment_id=?",Integer.class,first.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted FROM order_sales_shipment_line WHERE shipment_id=?",Integer.class,other.id())).isZero();
    }

    @Test
    void visibleSourceShipmentNumberIsSearchableWithoutEscapingTenantStatusOrDataScope() {
        var first = repository.create("T","DHB-INTERNAL-1",command("FH-SOURCE-1",1,List.of()),"actor");
        repository.create("OTHER","DHB-INTERNAL-2",command("FH-SOURCE-1",1,List.of()),"actor");
        var outOfScope = repository.create("T","DHB-INTERNAL-3",command("FH-SOURCE-2",1,List.of()),"actor");
        jdbc.update("UPDATE order_sales_shipment SET warehouse_id=2 WHERE id=?",outOfScope.id());
        doAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> query = call.getArgument(0);
            return query.apply("warehouse_id=1");
        }).when(scopes).applyRelated(any(),eq("order:shipment:read"),eq("order_sales_shipment"));

        var bySource = repository.shipments("T",0,20,
                new SalesShipmentSearchCriteria("FH-SOURCE",null,null,null,"SIGNED",null,null));
        assertThat(bySource.total()).isEqualTo(1);
        assertThat(bySource.items()).singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(first.id()));
        assertThat(repository.shipments("T",0,20,
                new SalesShipmentSearchCriteria("DHB-INTERNAL-1",null,null,null,null,null,null)).total()).isEqualTo(1);
        assertThat(repository.shipments("T",0,20,
                new SalesShipmentSearchCriteria("FH-SOURCE",null,null,null,"DRAFT",null,null)).total()).isZero();
        repository.delete("T",first.id(),1,"actor");
        assertThat(repository.shipments("T",0,20,
                new SalesShipmentSearchCriteria("FH-SOURCE",null,null,null,null,null,null)).total()).isZero();
    }

    private SalesShipmentWrite command(String sourceNo,int revision,List<SalesShipmentLineWrite> lines) {
        return new SalesShipmentWrite(connector,"DINGHUOBAO",sourceNo,1L,"DH-1",1L,"C-1","客户",
                "123","CITY","EMP-1",1L,10L,"OUT-1","SIGNED","顺丰","SF-1",
                Instant.parse("2026-11-17T00:00:00Z"),new BigDecimal("36"),lines,"备注",revision);
    }

    private SalesShipmentLineWrite line(String quantity) {
        return new SalesShipmentLineWrite(1L,1,15L,29L,"P1","SKU1","商品","规格","BUCKET",new BigDecimal(quantity),null);
    }
}
