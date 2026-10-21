package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;
import com.rigour.order.infrastructure.persistence.mapper.OrderCostSourceMapper;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.regex.Pattern;

/** 在隔离 H2 中执行仓储生成的过滤条件，覆盖缺仓导入单的可见边界。 */
class OrderCostSourceVisibilityTest {
    SingleConnectionDataSource ds;
    MybatisPlusOrderCostSourceRepository store;
    LogisticsWarehouseDataScope scope;

    @BeforeEach
    void setup() {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:sources_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE order_sales_order(id BIGINT,tenant_id VARCHAR,deleted"
                    + " INT,order_status_code VARCHAR,selected_warehouse_id BIGINT,region_code"
                    + " VARCHAR,order_no VARCHAR,customer_name_snapshot VARCHAR,source_order_no"
                    + " VARCHAR)");
        jdbc.execute(
                "INSERT INTO order_sales_order VALUES"
                    + " (1,'T1',0,'COMPLETED',NULL,'C1','S1','客户','D1'),(2,'T1',0,'COMPLETED',7,'C1','S2','客户','D2'),(3,'T2',0,'COMPLETED',NULL,'C1','S3','客户','D3'),(4,'T1',0,'CANCELLED',NULL,'C1','S4','客户','D4'),(5,'T1',0,'COMPLETED',NULL,'C2','S5','客户','D5')");
        scope = mock(LogisticsWarehouseDataScope.class);
        when(scope.predicate(anyString(), any(String[].class)))
                .thenReturn(new LogisticsWarehouseDataScope.Predicate("1=1", List.of()));
        var mapper = mock(OrderCostSourceMapper.class);
        when(mapper.deliveries(any()))
                .thenAnswer(
                        call -> {
                            QueryWrapper<?> query = call.getArgument(0);
                            var matcher =
                                    Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}")
                                            .matcher(query.getCustomSqlSegment());
                            var values = new ArrayList<>();
                            var sql = new StringBuilder();
                            while (matcher.find()) {
                                values.add(query.getParamNameValuePairs().get(matcher.group(1)));
                                matcher.appendReplacement(sql, "?");
                            }
                            matcher.appendTail(sql);
                            var json = JsonMapper.builder().build();
                            return jdbc
                                    .queryForList(
                                            "SELECT id FROM order_sales_order o " + sql,
                                            Long.class,
                                            values.toArray())
                                    .stream()
                                    .map(
                                            id ->
                                                    json.readValue(
                                                            "{\"id\":" + id + "}",
                                                            OrderDeliveryCostSourceView.class))
                                    .toList();
                        });
        store = new MybatisPlusOrderCostSourceRepository(mapper, scope);
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void allWarehouseAccessIncludesMissingWarehouseButKeepsTenantCityAndStatus() {
        assertThat(store.deliveries("T1", "", List.of(), "C1", 0, "cost:read"))
                .extracting(OrderDeliveryCostSourceView::id)
                .containsExactly(2L, 1L);
    }

    @Test
    void restrictedWarehouseAccessStillExcludesMissingWarehouseIncludingDirectIds() {
        when(scope.predicate(anyString(), any(String[].class)))
                .thenReturn(
                        new LogisticsWarehouseDataScope.Predicate(
                                "o.selected_warehouse_id=?", List.of(7L)));
        assertThat(store.deliveries("T1", "", List.of(), "C1", 0, "cost:read"))
                .extracting(OrderDeliveryCostSourceView::id)
                .containsExactly(2L);
        assertThat(store.deliveries("T1", "", List.of(1L), "C1", 0, "cost:read")).isEmpty();
    }
}
