package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.FieldworkSources.*;

import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository
public class FieldworkSourceQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public FieldworkSourceQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Result find(String tenant, Query query) {
        var zone = ZoneId.of("Asia/Shanghai");
        Instant start = query.date().atStartOfDay(zone).toInstant();
        Instant end = query.date().plusDays(1).atStartOfDay(zone).toInstant();
        var args =
                new MapSqlParameterSource("tenant", tenant)
                        .addValue("ids", query.employees())
                        .addValue("start", Timestamp.from(start))
                        .addValue("end", Timestamp.from(end))
                        .addValue("asOf", Timestamp.from(query.asOf()));
        var contacts = new HashMap<String, Contacts>();
        if (!query.employees().isEmpty()) {
            jdbc.query(
                    """
SELECT employee_id,MAX(coverage_from) started,MIN(observed_at) observed
FROM crm_wecom_sync WHERE tenant_id=:tenant AND employee_id IN (:ids) AND sequence_no>0
GROUP BY employee_id
""",
                    args,
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            rs -> {
                                Instant observed = rs.getTimestamp("observed").toInstant();
                                var began = rs.getTimestamp("started");
                                boolean covered =
                                        began != null && !began.toInstant().isAfter(start);
                                boolean fresh =
                                        query.asOf().isBefore(end)
                                                ? !observed.isBefore(start)
                                                        && !observed.isBefore(
                                                                query.asOf()
                                                                        .minus(
                                                                                Duration.ofHours(
                                                                                        24)))
                                                : !observed.isBefore(end);
                                contacts.put(
                                        rs.getString(1),
                                        new Contacts(
                                                !covered ? "PARTIAL" : fresh ? "READY" : "STALE",
                                                observed,
                                                new ArrayList<>()));
                            });
            jdbc.query(
                    """
SELECT r.employee_id,COALESCE(CONCAT('C',i.contact_id),CONCAT('I',i.id)) person, MIN(r.added_at) added
FROM crm_wecom_relation r JOIN crm_wecom_identity i ON i.tenant_id=r.tenant_id AND i.id=r.identity_id
WHERE r.tenant_id=:tenant AND r.employee_id IN (:ids) AND r.acquisition=1
  AND r.added_at>=:start AND r.added_at<:end AND r.added_at<=:asOf
GROUP BY r.employee_id,person ORDER BY added
""",
                    args,
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            rs -> {
                                var coverage = contacts.get(rs.getString(1));
                                if (coverage != null
                                        && "READY".equals(coverage.status())
                                        && !rs.getTimestamp(3)
                                                .toInstant()
                                                .isAfter(coverage.observedAt()))
                                    coverage.additions()
                                            .add(
                                                    new Contact(
                                                            rs.getString(2),
                                                            rs.getTimestamp(3).toInstant()));
                            });
        }
        var visits = new HashMap<Long, Visit>();
        if (!query.visits().isEmpty()) {
            args.addValue("visits", query.visits());
            jdbc.query(
                    "SELECT"
                        + " id,status_code,validation_policy_version,review_reason,CAST(JSON_UNQUOTE(JSON_EXTRACT(policy_snapshot,'$.minimumDwellMinutes'))"
                        + " AS UNSIGNED) FROM crm_visit WHERE tenant_id=:tenant AND id IN"
                        + " (:visits)",
                    args,
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            rs -> {
                                String state =
                                        switch (rs.getString(2)) {
                                            case "EFFECTIVE" -> "VALID";
                                            case "INVALID", "VOID" -> "INVALID";
                                            default -> "PENDING";
                                        };
                                visits.put(
                                        rs.getLong(1),
                                        new Visit(
                                                state,
                                                rs.getString(4),
                                                rs.getString(3),
                                                rs.getObject(5, Integer.class)));
                            });
        }
        return new Result(contacts, visits);
    }
}
