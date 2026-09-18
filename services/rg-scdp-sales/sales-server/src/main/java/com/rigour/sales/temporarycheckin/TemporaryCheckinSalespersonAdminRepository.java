package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 后台销售目录仓储；历史门店和打卡快照不会随目录修改而回写。 */
@Repository
public class TemporaryCheckinSalespersonAdminRepository {

    private final JdbcTemplate jdbc;

    TemporaryCheckinSalespersonAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long count(UUID tenantId, String city, String status, String escapedQuery) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM temp_sales_checkin_salesperson WHERE tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId)));
        appendFilters(sql, arguments, city, status, escapedQuery);
        Long result = jdbc.queryForObject(sql.toString(), Long.class, arguments.toArray());
        return result == null ? 0 : result;
    }

    List<SalespersonAdminRow> list(
            UUID tenantId, String city, String status, String escapedQuery, int offset, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, city, position, employment_status, status, sort_order,
                       checkin_secret_hash, created_at, updated_at
                  FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(bin(tenantId)));
        appendFilters(sql, arguments, city, status, escapedQuery);
        sql.append(" ORDER BY city, sort_order, name, id LIMIT ? OFFSET ?");
        arguments.add(limit);
        arguments.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> salesperson(rs), arguments.toArray());
    }

    Optional<SalespersonAdminRow> find(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT id, name, city, position, employment_status, status, sort_order,
                       checkin_secret_hash, created_at, updated_at
                  FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=? AND id=? LIMIT 1
                """, (rs, row) -> salesperson(rs), bin(tenantId), bin(id)).stream().findFirst();
    }

    List<SalespersonAdminRow> listWithoutCredential(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT id, name, city, position, employment_status, status, sort_order,
                       checkin_secret_hash, created_at, updated_at
                  FROM temp_sales_checkin_salesperson
                 WHERE tenant_id=? AND status='ACTIVE' AND employment_status<>'离职'
                   AND checkin_secret_hash IS NULL
                 ORDER BY city, sort_order, name, id
                 LIMIT ?
                """, (rs, row) -> salesperson(rs), bin(tenantId), limit);
    }

    int insert(
            UUID id, UUID tenantId, String name, String city, String position,
            String employmentStatus, String status, int sortOrder, Instant now) {
        return jdbc.update("""
                INSERT INTO temp_sales_checkin_salesperson (
                    id, tenant_id, source_record_id, name, city, position, employment_status,
                    status, sort_order, checkin_secret_hash, credential_version,
                    created_at, updated_at
                ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, NULL, 1, ?, ?)
                """, bin(id), bin(tenantId), name, city, position, employmentStatus,
                status, sortOrder, timestamp(now), timestamp(now));
    }

    int update(
            UUID tenantId, UUID id, String requiredCurrentCity, String name, String city,
            String position, String employmentStatus, String status, int sortOrder, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_salesperson
                   SET name=?, city=?, position=?, employment_status=?, status=?, sort_order=?, updated_at=?
                 WHERE tenant_id=? AND id=?
                """ + (requiredCurrentCity == null ? "" : " AND city=?"),
                arguments(name, city, position, employmentStatus, status, sortOrder, now,
                        tenantId, id, requiredCurrentCity));
    }

    private static Object[] arguments(
            String name, String city, String position, String employmentStatus, String status,
            int sortOrder, Instant now, UUID tenantId, UUID id, String requiredCurrentCity) {
        List<Object> values = new ArrayList<>(List.of(
                name, city, position == null ? "" : position, employmentStatus, status,
                sortOrder, timestamp(now), bin(tenantId), bin(id)));
        // 保留数据库 NULL，而不是把可选职位变为空串。
        values.set(2, position);
        if (requiredCurrentCity != null) values.add(requiredCurrentCity);
        return values.toArray();
    }

    private static void appendFilters(
            StringBuilder sql, List<Object> arguments,
            String city, String status, String escapedQuery) {
        if (city != null) {
            sql.append(" AND city=?");
            arguments.add(city);
        }
        if (status != null) {
            sql.append(" AND status=?");
            arguments.add(status);
        }
        if (escapedQuery != null) {
            sql.append(" AND (name LIKE ? ESCAPE '=' OR position LIKE ? ESCAPE '=')");
            String pattern = "%" + escapedQuery + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
    }

    private static SalespersonAdminRow salesperson(ResultSet rs) throws SQLException {
        return new SalespersonAdminRow(
                uuid(rs, "id"), rs.getString("name"), rs.getString("city"), rs.getString("position"),
                rs.getString("employment_status"), rs.getString("status"), rs.getInt("sort_order"),
                rs.getString("checkin_secret_hash") != null,
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static byte[] bin(UUID value) { return SalesUuidCodec.encode(value); }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return SalesUuidCodec.decode(rs.getBytes(column));
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record SalespersonAdminRow(
            UUID id, String name, String city, String position, String employmentStatus,
            String status, int sortOrder, boolean credentialConfigured,
            Instant createdAt, Instant updatedAt) { }
}
