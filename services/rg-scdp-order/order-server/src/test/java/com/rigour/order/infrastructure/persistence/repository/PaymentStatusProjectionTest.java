package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.infrastructure.persistence.mapper.OrderAnalyticsSourceMapper;
import com.rigour.order.infrastructure.persistence.mapper.OrderAnalyticsSourceSql;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.*;

public class PaymentStatusProjectionTest {
    // H2's MySQL-mode UNIX_TIMESTAMP returns INT; match MySQL decimal seconds for microsecond
    // versions.
    public static java.math.BigDecimal unixTimestamp(java.time.LocalDateTime value) {
        return value == null
                ? null
                : java.math.BigDecimal.valueOf(value.toEpochSecond(java.time.ZoneOffset.UTC))
                        .add(java.math.BigDecimal.valueOf(value.getNano(), 9));
    }

    @Test
    void statusProjectionIsTenantScopedAndDoesNotChangeOriginalPaymentContract() throws Exception {
        var ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:payment_status_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;BUILTIN_ALIAS_OVERRIDE=TRUE",
                        "sa",
                        "",
                        true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute(
                    "CREATE ALIAS UNIX_TIMESTAMP FOR"
                        + " 'com.rigour.order.infrastructure.persistence.repository.PaymentStatusProjectionTest.unixTimestamp'");
            jdbc.execute(
                    "CREATE TABLE order_payment_record(id BIGINT,tenant_id"
                        + " VARCHAR,payment_status_code VARCHAR,revision INT DEFAULT 1,updated_time"
                        + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            jdbc.update(
                    "INSERT INTO order_payment_record(id,tenant_id,payment_status_code)"
                        + " VALUES(1,'T','RECEIVED'),(2,'T','CANCELLED'),(3,'OTHER','CHECKED')");
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(OrderAnalyticsSourceMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setConfiguration(config);
            var session = new SqlSessionTemplate(factory.getObject());
            var store =
                    new MybatisPlusAnalyticsSourceSnapshotStore(
                            session.getMapper(OrderAnalyticsSourceMapper.class));
            var page = store.page("T", "ORDER_PAYMENT_STATUS", "");
            assertThat(OrderAnalyticsSourceSql.dataset("ORDER_PAYMENT_RECORD").columns())
                    .containsExactly(
                            "id",
                            "tenant_id",
                            "payment_no",
                            "source_system_code",
                            "source_document_no",
                            "order_id",
                            "sales_order_no_snapshot",
                            "customer_id",
                            "customer_code_snapshot",
                            "customer_name_snapshot",
                            "collector_staff_code",
                            "collector_name_snapshot",
                            "payment_time",
                            "payment_method_code",
                            "paid_amount",
                            "created_time",
                            "updated_time",
                            "deleted");
            assertThat(page.items())
                    .containsExactly(
                            Map.of("id", "1", "tenant_id", "T", "payment_status_code", "RECEIVED"),
                            Map.of(
                                    "id",
                                    "2",
                                    "tenant_id",
                                    "T",
                                    "payment_status_code",
                                    "CANCELLED"));
            assertThat(store.page("T", "ORDER_PAYMENT_STATUS", "1").items()).hasSize(1);
            assertThat(page.version()).isEqualTo(store.version("T", "ORDER_PAYMENT_STATUS"));
            jdbc.update(
                    "UPDATE order_payment_record SET"
                        + " payment_status_code='CHECKED',revision=revision+1 WHERE tenant_id='T'"
                        + " AND id=1");
            assertThat(store.version("T", "ORDER_PAYMENT_STATUS")).isNotEqualTo(page.version());
            assertThatThrownBy(
                            () ->
                                    store.version(
                                            "T",
                                            "ORDER_PAYMENT_STATUS; DROP TABLE"
                                                    + " order_payment_record"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.page("T", "ORDER_PAYMENT_STATUS", "1 OR 1=1"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            ds.destroy();
        }
    }
}
