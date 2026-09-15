package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.VisitAnalyticsView;
import com.rigour.analytics.api.v1.model.VisitAnalyticsView.*;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.port.out.VisitAnalyticsStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 拜访看板聚合；每条 SQL 复用同一授权范围，总体去重独立计算，不累加城市/人员去重值。 */
@Repository
public class JdbcVisitAnalyticsStore implements VisitAnalyticsStore {
    private static final String SCOPE = """
            WITH scoped AS (SELECT * FROM bi_sales_contact_fact
                WHERE tenant_id = :tenant AND submitted_at >= :from AND submitted_at <= :to
                  AND (:region IS NULL OR region_code = :region)
                  AND (:employee IS NULL OR owner_staff_code = :employee))
            """;
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcVisitAnalyticsStore(DataSource dataSource) { jdbc = new NamedParameterJdbcTemplate(dataSource); }

    @Override @Transactional(readOnly = true)
    public VisitAnalyticsView read(String tenantId, Instant from, Instant to, String regionCode, String employeeCode) {
        var args = new MapSqlParameterSource("tenant", tenantId).addValue("from", local(from)).addValue("to", local(to))
                .addValue("region", regionCode).addValue("employee", employeeCode);
        var times = jdbc.query("SELECT synced_time FROM bi_sales_contact_snapshot WHERE tenant_id=:tenant AND business_links_ready=1",
                args, (rs, index) -> rs.getObject("synced_time", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        if (times.isEmpty()) return new VisitAnalyticsView("NOT_READY", null, from, to, null, List.of(), List.of(), List.of());
        var summary = jdbc.queryForObject(SCOPE + """
                SELECT COUNT(*) visits, COUNT(DISTINCT store_id) stores, COUNT(DISTINCT salesperson_id) people,
                    (SELECT COUNT(*) FROM (SELECT store_id FROM scoped GROUP BY store_id HAVING COUNT(*) > 1) repeated) repeats,
                    COALESCE(SUM(CASE WHEN review_status='APPROVED' THEN 1 ELSE 0 END),0) approved,
                    COALESCE(SUM(CASE WHEN review_status='FLAGGED' THEN 1 ELSE 0 END),0) flagged,
                    COALESCE(SUM(CASE WHEN owner_staff_code IS NULL THEN 1 ELSE 0 END),0) unlinked,
                    COUNT(DISTINCT CASE WHEN customer_code IS NOT NULL THEN store_id END) linked_stores
                  FROM scoped
                """, args, (rs, index) -> new Summary(rs.getLong("visits"), rs.getLong("stores"), rs.getLong("people"),
                rs.getLong("repeats"), rs.getLong("approved"), rs.getLong("visits")-rs.getLong("approved")-rs.getLong("flagged"),
                rs.getLong("flagged"), rs.getLong("unlinked"), rs.getLong("linked_stores")));
        var daily = jdbc.query(SCOPE + """
                SELECT CAST(TIMESTAMPADD(HOUR,8,submitted_at) AS DATE) business_day, COUNT(*) visits, COUNT(DISTINCT store_id) stores
                  FROM scoped GROUP BY CAST(TIMESTAMPADD(HOUR,8,submitted_at) AS DATE) ORDER BY business_day
                """, args, (rs, index) -> new Day(rs.getObject("business_day", LocalDate.class), rs.getLong("visits"), rs.getLong("stores")))
                .stream().collect(Collectors.toMap(Day::date, Function.identity()));
        var days = new ArrayList<Day>();
        for (var day = from.atZone(BiBusinessTime.ZONE).toLocalDate(); !day.isAfter(to.atZone(BiBusinessTime.ZONE).toLocalDate()); day = day.plusDays(1)) {
            days.add(daily.getOrDefault(day, new Day(day, 0, 0)));
        }
        var cities = jdbc.query(SCOPE + """
                SELECT region_code, city_name, COUNT(*) visits, COUNT(DISTINCT store_id) stores,
                    COUNT(DISTINCT salesperson_id) people, COUNT(DISTINCT CASE WHEN customer_code IS NOT NULL THEN store_id END) linked_stores
                  FROM scoped GROUP BY region_code,city_name ORDER BY visits DESC,city_name
                """, args, (rs, index) -> new City(rs.getString("region_code"), rs.getString("city_name"), rs.getLong("visits"),
                rs.getLong("stores"), rs.getLong("people"), rs.getLong("linked_stores")));
        var people = jdbc.query(SCOPE + """
                SELECT s.salesperson_id, s.owner_staff_code, MAX(e.employee_name) employee_name,
                    COUNT(*) visits, COUNT(DISTINCT s.store_id) stores,
                    COUNT(DISTINCT CAST(TIMESTAMPADD(HOUR,8,s.submitted_at) AS DATE)) active_days,
                    SUM(CASE WHEN s.review_status='APPROVED' THEN 1 ELSE 0 END) approved,
                    SUM(CASE WHEN s.review_status='FLAGGED' THEN 1 ELSE 0 END) flagged
                  FROM scoped s LEFT JOIN bi_employee_dim e ON e.tenant_id=s.tenant_id AND e.employee_code=s.owner_staff_code
                 GROUP BY s.salesperson_id,s.owner_staff_code ORDER BY visits DESC,s.salesperson_id
                """, args, (rs, index) -> new Person(rs.getString("salesperson_id"), rs.getString("owner_staff_code"),
                rs.getString("employee_name"), rs.getLong("visits"), rs.getLong("stores"), rs.getLong("active_days"),
                rs.getLong("approved"), rs.getLong("visits")-rs.getLong("approved")-rs.getLong("flagged"), rs.getLong("flagged")));
        return new VisitAnalyticsView(summary.visits() == 0 ? "EMPTY" : "READY", times.getFirst(), from, to, summary, days, cities, people);
    }
    private static LocalDateTime local(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
