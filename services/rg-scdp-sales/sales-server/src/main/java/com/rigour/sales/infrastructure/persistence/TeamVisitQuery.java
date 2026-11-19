package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.api.v1.model.TeamVisitPage;
import com.rigour.sales.application.port.out.SalesManagementDirectory.Access;

import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public class TeamVisitQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public TeamVisitQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TeamVisitPage find(
            UUID tenant,
            Access scope,
            LocalDate from,
            LocalDate to,
            String keyword,
            Set<String> matchingEmployees,
            int page,
            int size) {
        if (!scope.all() && scope.employeeCodes().isEmpty()) return new TeamVisitPage(List.of(), 0);
        String sql =
                """
FROM sales_visit v JOIN sales_work_day d ON d.tenant_id=v.tenant_id AND d.id=v.work_day_id
JOIN sales_profile p ON p.tenant_id=v.tenant_id AND p.id=v.sales_profile_id
LEFT JOIN sales_visit_target_snapshot t ON t.tenant_id=v.tenant_id AND t.visit_id=v.id
WHERE v.tenant_id=:tenant AND d.business_date BETWEEN :from AND :to AND v.checked_in_at IS NOT NULL
""";
        var args =
                new MapSqlParameterSource("tenant", SalesUuidCodec.encode(tenant))
                        .addValue("from", from)
                        .addValue("to", to);
        if (!scope.all()) {
            sql += " AND p.hr_employee_code IN (:codes)";
            args.addValue("codes", scope.employeeCodes());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql +=
                    " AND (LOCATE(:term,p.hr_employee_code)>0 OR LOCATE(:term,p.sales_no)>0 OR"
                        + " LOCATE(:term,t.store_name)>0 OR LOCATE(:term,t.customer_name)>0";
            if (!matchingEmployees.isEmpty()) {
                sql += " OR p.hr_employee_code IN (:matches)";
                args.addValue("matches", matchingEmployees);
            }
            sql += ")";
            args.addValue("term", keyword.trim());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) " + sql, args, Long.class);
        var rows =
                jdbc.query(
                        """
SELECT v.id,d.business_date,p.hr_employee_code,p.sales_no,t.customer_name,t.store_name,
       v.status,v.checked_in_at,v.checked_out_at,v.result_note,v.contact_outcome,v.intention_level
"""
                                + sql
                                + " ORDER BY d.business_date DESC,v.checked_in_at DESC,v.id LIMIT"
                                + " :size OFFSET :offset",
                        args.addValue("size", size).addValue("offset", (page - 1) * size),
                        (rs, n) ->
                                new TeamVisitPage.Row(
                                        SalesUuidCodec.decode(rs.getBytes(1)).toString(),
                                        rs.getDate(2).toLocalDate(),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getTimestamp(8).toInstant(),
                                        rs.getTimestamp(9) == null
                                                ? null
                                                : rs.getTimestamp(9).toInstant(),
                                        rs.getString(10),
                                        rs.getString(11),
                                        rs.getString(12)));
        return new TeamVisitPage(rows, total);
    }
}
