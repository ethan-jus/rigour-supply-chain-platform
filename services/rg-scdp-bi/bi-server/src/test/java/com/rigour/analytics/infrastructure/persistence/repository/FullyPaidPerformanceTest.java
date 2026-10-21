package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

class FullyPaidPerformanceTest {
    @Test
    void countsEachOrderOnceInFirstFullyPaidMonthWithTenantStatusOwnerAndCutoffBoundaries() {
        var ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:full_paid_" + UUID.randomUUID() + ";MODE=MySQL",
                        "sa",
                        "",
                        true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute(
                    "CREATE TABLE bi_sales_order_fact(tenant_id VARCHAR,order_id BIGINT,region_code"
                            + " VARCHAR,owner_staff_code VARCHAR,payable_amount"
                            + " DECIMAL(18,2),paid_amount DECIMAL(18,2),order_status_code"
                            + " VARCHAR,deleted INT)");
            jdbc.execute(
                    "CREATE TABLE bi_sales_payment_fact(tenant_id VARCHAR,payment_id"
                            + " BIGINT,order_id BIGINT,payment_time TIMESTAMP,paid_amount"
                            + " DECIMAL(18,2),deleted INT)");
            jdbc.execute(
                    "CREATE TABLE bi_payment_status(tenant_id VARCHAR,id BIGINT,payment_status_code"
                            + " VARCHAR)");
            jdbc.update(
                    """
                    INSERT INTO bi_sales_order_fact VALUES
                    ('T',1,'BJ','E1',100,100,'COMPLETED',0),
                    ('T',2,'BJ','E1',100,120,'COMPLETED',0),
                    ('T',3,'BJ','E1',100,50,'COMPLETED',0),
                    ('T',4,'BJ','E1',100,100,'CANCELLED',0),
                    ('T',5,'BJ','E1',100,100,'COMPLETED',1),
                    ('T',6,'BJ','E1',100,110,'COMPLETED',0),
                    ('T',7,'SH','E2',250,250,'COMPLETED',0),
                    ('T',8,'BJ','E1',100,100,'COMPLETED',0),
                    ('T',9,'BJ','E1',100,100,'COMPLETED',0),
                    ('T',10,'BJ','E1',0,100,'COMPLETED',0),
                    ('T',11,'BJ','E1',100,100,'DRAFT',0),
                    ('T',12,'BJ','E1',100,50,'COMPLETED',0),
                    ('T',13,'BJ','E1',100,100,'COMPLETED',0),
                    ('T',14,'BJ','E1',100,100,'COMPLETED',0),
                    ('T',15,'BJ','E1',100,100,'COMPLETED',0),
                    ('OTHER',1,'BJ','E1',999,999,'COMPLETED',0)
                    """);
            jdbc.update(
                    """
                    INSERT INTO bi_sales_payment_fact VALUES
                    ('T',1,1,'2026-09-20',40,0),('T',2,1,'2026-10-01',60,0),
                    ('T',3,2,'2026-10-01',100,0),('T',4,2,'2026-10-10',20,0),
                    ('T',5,3,'2026-10-01',50,0),('T',6,4,'2026-10-01',100,0),
                    ('T',7,5,'2026-10-01',100,0),('T',8,6,'2026-09-01',100,0),
                    ('T',9,6,'2026-10-01',10,0),('T',10,7,'2026-10-01',250,0),
                    ('T',11,8,'2026-10-01',100,0),('T',12,9,'2026-10-01',100,1),
                    ('T',13,10,'2026-10-01',100,0),('OTHER',1,1,'2026-10-01',999,0),
                    ('T',14,11,'2026-10-01',100,0),('T',15,12,'2026-10-01',100,0),
                    ('T',16,13,'2026-10-01',100,0),('T',17,13,'2026-10-02',-50,0),
                    ('T',18,14,'2026-10-01',100,0),('T',19,15,'2026-10-15',100,0)
                    """);
            jdbc.update(
                    "INSERT INTO bi_payment_status SELECT tenant_id,payment_id,CASE WHEN"
                        + " payment_id=11 THEN 'CANCELLED' WHEN payment_id=18 THEN 'DRAFT' WHEN"
                        + " payment_id=2 THEN 'CHECKED' ELSE 'RECEIVED' END FROM"
                        + " bi_sales_payment_fact");
            var config = new Configuration();
            config.addMapper(SupplyDashboardQueryMapper.class);
            var args = new HashMap<String, Object>();
            args.put("tenantId", "T");
            args.put("from", LocalDateTime.parse("2026-10-01T00:00:00"));
            args.put("to", LocalDateTime.parse("2026-10-14T23:59:59"));
            args.put("city", false);
            assertThat(query(jdbc, config, args))
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("E1", new BigDecimal("200.00"), "E2", new BigDecimal("250.00")));
            args.put("regionCode", "BJ");
            assertThat(query(jdbc, config, args))
                    .containsExactlyEntriesOf(Map.of("E1", new BigDecimal("200.00")));
            args.put("ownerStaffCode", "E2");
            assertThat(query(jdbc, config, args)).isEmpty();
            args.remove("ownerStaffCode");
            args.remove("regionCode");
            args.put("city", true);
            assertThat(query(jdbc, config, args))
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("BJ", new BigDecimal("200.00"), "SH", new BigDecimal("250.00")));
            args.put("from", LocalDateTime.parse("2026-09-01T00:00:00"));
            args.put("to", LocalDateTime.parse("2026-09-30T23:59:59"));
            assertThat(query(jdbc, config, args))
                    .containsExactlyEntriesOf(Map.of("BJ", new BigDecimal("100.00")));
        } finally {
            ds.destroy();
        }
    }

    private Map<String, BigDecimal> query(
            JdbcTemplate jdbc, Configuration config, Map<String, Object> args) {
        var sql =
                config.getMappedStatement(
                                SupplyDashboardQueryMapper.class.getName()
                                        + ".fullyPaidPerformance")
                        .getBoundSql(args);
        var values =
                sql.getParameterMappings().stream().map(p -> args.get(p.getProperty())).toArray();
        var result = new HashMap<String, BigDecimal>();
        jdbc.query(
                sql.getSql(),
                r -> {
                    result.put(r.getString(1), r.getBigDecimal(2));
                },
                values);
        return result;
    }
}
