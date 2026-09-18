package com.rigour.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;

/** 在与共享环境同类的MySQL事务及约束下重复验证历史资金接续。 */
@Testcontainers
class HistoryOrderSyncMySqlTest extends HistoryOrderSyncTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Override
    DataSource dataSource() {
        var ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(ds);
        for (String table :
                java.util.List.of(
                        "order_sync_product_allocation",
                        "order_sync_allocation",
                        "order_sync_receipt_revision",
                        "order_sync_receipt",
                        "order_history_member",
                        "order_history_group",
                        "order_sync_source",
                        "order_payment_record",
                        "order_sales_order_line",
                        "order_sales_order")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        return ds;
    }
}
