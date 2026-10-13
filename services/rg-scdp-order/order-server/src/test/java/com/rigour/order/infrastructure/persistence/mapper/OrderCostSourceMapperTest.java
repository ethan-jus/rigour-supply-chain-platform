package com.rigour.order.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

class OrderCostSourceMapperTest {
    private final MybatisConfiguration config = new MybatisConfiguration();

    OrderCostSourceMapperTest() {
        config.addMapper(OrderCostSourceMapper.class);
    }

    @Test
    void managerQueryUsesAllSalesInCityAndKeepsTenantBound() {
        var values = new HashMap<String, Object>();
        values.put("tenant", "TENANT");
        values.put("employee", null);
        values.put("city", "CITY' OR 1=1");
        values.put("until", LocalDateTime.of(2026, 9, 30, 16, 0));
        var bound =
                config.getMappedStatement(OrderCostSourceMapper.class.getName() + ".commissions")
                        .getBoundSql(values);
        assertThat(bound.getSql())
                .contains("o.tenant_id=?", "o.region_code=?")
                .doesNotContain("o.owner_employee_code=?", "CITY' OR 1=1");
        assertThat(bound.getParameterMappings())
                .extracting(x -> x.getProperty())
                .containsExactly("until", "tenant", "city");
    }

    @Test
    void salespersonQueryCannotAccidentallyUseTheCityBranch() {
        var bound =
                config.getMappedStatement(OrderCostSourceMapper.class.getName() + ".commissions")
                        .getBoundSql(
                                Map.of(
                                        "tenant",
                                        "TENANT",
                                        "employee",
                                        "E1",
                                        "city",
                                        "CITY",
                                        "until",
                                        LocalDateTime.now()));
        assertThat(bound.getSql())
                .contains("o.owner_employee_code=?")
                .doesNotContain("o.region_code=?");
        assertThat(bound.getSql())
                .contains("p.payment_time<cutoff.end_utc", "refundExists", "qualityStatus");
    }

    @Test
    void sourceKeywordAndTenantAreParameters() {
        var q = new QueryWrapper<>().eq("o.tenant_id", "T1").like("o.order_no", "' OR 1=1 --");
        var bound =
                config.getMappedStatement(OrderCostSourceMapper.class.getName() + ".deliveries")
                        .getBoundSql(Map.of("ew", q));
        assertThat(bound.getSql())
                .contains("o.tenant_id = ?", "o.order_no LIKE ?")
                .doesNotContain("OR 1=1");
    }

    @Test
    void collectionProjectionHasStableAliasAndBindsBothPaymentAndRefundTenant() {
        var bound =
                config.getMappedStatement(OrderCostSourceMapper.class.getName() + ".collections")
                        .getBoundSql(
                                Map.of(
                                        "tenant",
                                        "T1",
                                        "employee",
                                        "E1",
                                        "from",
                                        LocalDateTime.now(),
                                        "until",
                                        LocalDateTime.now()));
        assertThat(bound.getSql()).contains("ORDER BY `date`,sourceId", "-r.refund_amount");
        assertThat(bound.getParameterMappings())
                .extracting(x -> x.getProperty())
                .containsExactly(
                        "tenant",
                        "employee",
                        "from",
                        "until",
                        "tenant",
                        "employee",
                        "from",
                        "until");
    }
}
