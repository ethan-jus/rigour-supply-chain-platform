package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.hr.infrastructure.persistence.mapper.HrExpenseMapper;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

class CostAnalyticsSourceTest {
    @Test
    void reads1001RowsWithoutDuplicationAndVersionRollsBack() throws Exception {
        var ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:cost_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute(
                    "CREATE TABLE hr_expense("
                            + String.join(
                                    ",",
                                    MybatisPlusCostAnalyticsSource.COLUMNS.stream()
                                            .map(
                                                    c ->
                                                            c
                                                                    + (c.equals("amount")
                                                                            ? " DECIMAL(18,2)"
                                                                            : " VARCHAR(1000)"))
                                            .toList())
                            + ")");
            jdbc.execute(
                    "CREATE TABLE hr_expense_guard(tenant_id VARCHAR PRIMARY KEY,version BIGINT)");
            jdbc.update("INSERT INTO hr_expense_guard VALUES('tenant',1)");
            for (int n = 1; n <= 1001; n++)
                jdbc.update(
                        "INSERT INTO hr_expense(id,tenant_id,amount) VALUES(?, 'tenant',0.01)",
                        String.format("00000000-0000-0000-0000-%012d", n));
            jdbc.update(
                    "INSERT INTO hr_expense(id,tenant_id,amount) VALUES('foreign','another',999)");
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(HrExpenseMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setConfiguration(config);
            var mapper =
                    new SqlSessionTemplate(factory.getObject()).getMapper(HrExpenseMapper.class);
            var source = new MybatisPlusCostAnalyticsSource(mapper);
            var first = source.page("tenant", "");
            assertThat(first.items()).hasSize(1000);
            assertThat(first.items().getFirst().get("amount")).isEqualTo("0.01");
            var second = source.page("tenant", first.items().getLast().get("id"));
            assertThat(second.items()).hasSize(1);
            assertThat(second.items().getFirst().get("id")).endsWith("001001");
            assertThatThrownBy(() -> source.page("tenant", "bad cursor"))
                    .isInstanceOf(IllegalArgumentException.class);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.executeWithoutResult(
                    status -> {
                        mapper.lock("tenant");
                        mapper.advanceAnalyticsVersion("tenant");
                        assertThat(source.version("tenant")).isEqualTo("2");
                        status.setRollbackOnly();
                    });
            assertThat(source.version("tenant")).isEqualTo("1");
        } finally {
            ds.destroy();
        }
    }
}
