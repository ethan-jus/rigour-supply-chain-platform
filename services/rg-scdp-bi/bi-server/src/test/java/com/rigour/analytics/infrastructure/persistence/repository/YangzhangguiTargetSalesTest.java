package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class YangzhangguiTargetSalesTest {
    @Test
    void sumsOnlyExactBrandValidPeriodTenantAndSubject() {
        var ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:brand_target_" + UUID.randomUUID() + ";MODE=MySQL",
                        "sa",
                        "",
                        true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute(
                    """
CREATE TABLE bi_sales_order_line_fact (
    tenant_id VARCHAR, brand_name VARCHAR, region_code VARCHAR, owner_staff_code VARCHAR,
    order_date TIMESTAMP, order_status_code VARCHAR, deleted INT, line_amount DECIMAL(18,2))
""");
            jdbc.update(
                    """
                    INSERT INTO bi_sales_order_line_fact VALUES
                    ('T','杨掌柜 X CBSA世界斯诺克学院','BJ','E1','2026-10-02','COMPLETED',0,12.50),
                    ('T',' 杨掌柜 X CBSA世界斯诺克学院 ','BJ','E2','2026-10-03','COMPLETED',0,20),
                    ('T','杨掌柜 X CBSA世界斯诺克学院','SH','E1','2026-10-03','COMPLETED',0,30),
                    ('OTHER','杨掌柜 X CBSA世界斯诺克学院','BJ','E1','2026-10-02','COMPLETED',0,999),
                    ('T','杨掌柜旗舰','BJ','E1','2026-10-02','COMPLETED',0,999),
                    ('T','杨掌柜 X CBSA世界斯诺克学院','BJ','E1','2026-09-30','COMPLETED',0,999),
                    ('T','杨掌柜 X CBSA世界斯诺克学院','BJ','E1','2026-11-01','COMPLETED',0,999),
                    ('T','杨掌柜 X CBSA世界斯诺克学院','BJ','E1','2026-10-02','CANCELLED',0,999),
                    ('T','杨掌柜 X CBSA世界斯诺克学院','BJ','E1','2026-10-02','COMPLETED',1,999)
                    """);
            var config = new Configuration();
            config.addMapper(SupplyDashboardQueryMapper.class);
            var args = new HashMap<String, Object>();
            args.put("tenantId", "T");
            args.put("from", LocalDateTime.parse("2026-10-01T00:00:00"));
            args.put("to", LocalDateTime.parse("2026-10-31T23:59:59"));
            assertThat(query(jdbc, config, args)).isEqualByComparingTo("62.50");
            args.put("regionCode", "BJ");
            assertThat(query(jdbc, config, args)).isEqualByComparingTo("32.50");
            args.put("ownerStaffCode", "E1");
            assertThat(query(jdbc, config, args)).isEqualByComparingTo("12.50");
            args.put("ownerStaffCode", "NONE");
            assertThat(query(jdbc, config, args)).isZero();
        } finally {
            ds.destroy();
        }
    }

    private BigDecimal query(JdbcTemplate jdbc, Configuration config, Map<String, Object> args) {
        var sql =
                config.getMappedStatement(
                                SupplyDashboardQueryMapper.class.getName()
                                        + ".yangzhangguiTargetSales")
                        .getBoundSql(args);
        var values =
                sql.getParameterMappings().stream().map(p -> args.get(p.getProperty())).toArray();
        return jdbc.queryForObject(sql.getSql(), BigDecimal.class, values);
    }
}
