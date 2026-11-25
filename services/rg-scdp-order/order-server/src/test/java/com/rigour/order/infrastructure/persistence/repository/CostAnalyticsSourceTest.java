package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.infrastructure.persistence.mapper.OrderLogisticsCostMapper;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class CostAnalyticsSourceTest {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
            new org.testcontainers.mysql.MySQLContainer("mysql:8.4");

    @Test
    void reads1001RowsWithoutDuplicationAndVersionRollsBack() throws Exception {
        var ds =
                new SingleConnectionDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute(
                    "CREATE TABLE order_logistics_cost("
                            + String.join(
                                    ",",
                                    MybatisPlusCostAnalyticsSource.COLUMNS.stream()
                                            .map(
                                                    c ->
                                                            c
                                                                    + (c.equals("amount")
                                                                            ? " DECIMAL(18,2)"
                                                                            : " LONGTEXT"))
                                            .toList())
                            + ")");
            jdbc.execute(
                    "ALTER TABLE order_logistics_cost ADD COLUMN deleted INT NOT NULL DEFAULT 0");
            jdbc.execute(
                    "CREATE TABLE order_logistics_cost_guard(tenant_id VARCHAR(36) PRIMARY"
                        + " KEY,version BIGINT)");
            jdbc.execute(
                    "CREATE TABLE order_logistics_import_order(tenant_id VARCHAR(36),cost_id"
                        + " VARCHAR(36),source_channel VARCHAR(20))");
            jdbc.execute(
                    "CREATE TABLE order_transport_shipment_source(tenant_id VARCHAR(36),cost_id"
                        + " VARCHAR(36))");
            jdbc.execute(
                    "CREATE TABLE order_logistics_cost_attachment(tenant_id VARCHAR(36),entry_id"
                        + " VARCHAR(36),deleted INT)");
            jdbc.update("INSERT INTO order_logistics_cost_guard VALUES('tenant',1)");
            for (int n = 1; n <= 1001; n++)
                jdbc.update(
                        "INSERT INTO order_logistics_cost(id,tenant_id,amount) VALUES(?,"
                                + " 'tenant',0.01)",
                        String.format("00000000-0000-0000-0000-%012d", n));
            jdbc.update(
                    "INSERT INTO order_logistics_cost(id,tenant_id,amount)"
                            + " VALUES('foreign','another',999)");
            jdbc.update(
                    "INSERT INTO order_logistics_cost(id,tenant_id,amount,deleted)"
                            + " VALUES('removed','tenant',999,1)");
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(OrderLogisticsCostMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setConfiguration(config);
            var mapper =
                    new SqlSessionTemplate(factory.getObject())
                            .getMapper(OrderLogisticsCostMapper.class);
            var source =
                    new MybatisPlusCostAnalyticsSource(
                            mapper, tools.jackson.databind.json.JsonMapper.builder().build());
            var first = source.page("tenant", "");
            assertThat(first.items()).hasSize(1000);
            assertThat(first.items().getFirst().get("amount")).isEqualTo("0.01");
            assertThat(first.items().getFirst()).containsEntry("status_code", "DRAFT");
            assertThat(first.items().getFirst().get("data_json")).contains("ORDER,EVIDENCE");
            var second = source.page("tenant", first.items().getLast().get("id"));
            assertThat(second.items()).hasSize(1);
            assertThat(first.items())
                    .allSatisfy(r -> assertThat(r.get("status_code")).isEqualTo("DRAFT"));
            assertThat(second.items().getFirst().get("id")).endsWith("001001");
            assertThatThrownBy(() -> source.page("tenant", "bad cursor"))
                    .isInstanceOf(IllegalArgumentException.class);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.executeWithoutResult(
                    status -> {
                        mapper.lock("tenant");
                        mapper.advanceAnalyticsVersion("tenant");
                        assertThat(source.version("tenant"))
                                .isEqualTo("2:transport-types-v6-payment");
                        status.setRollbackOnly();
                    });
            assertThat(source.version("tenant")).isEqualTo("1:transport-types-v6-payment");
        } finally {
            ds.destroy();
        }
    }
}
