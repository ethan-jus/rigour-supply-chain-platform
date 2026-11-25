package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource.Employee;
import com.rigour.sales.application.port.out.SalesEmployeeProfileRepository;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSalesEmployeeProfileRepository implements SalesEmployeeProfileRepository {
    private final JdbcTemplate jdbc;

    public JdbcSalesEmployeeProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Profile> findByUser(UUID tenantId, UUID userId) {
        return find(tenantId, userId, false);
    }

    private Optional<Profile> find(UUID tenantId, UUID userId, boolean lock) {
        return jdbc.query("""
                SELECT id, hr_employee_id, hr_employee_code, sales_no, city_org_id,
                       status, effective_from, effective_to
                  FROM sales_profile WHERE tenant_id=? AND platform_user_id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Profile(
                SalesUuidCodec.decode(rs.getBytes("id")), rs.getLong("hr_employee_id"),
                rs.getString("hr_employee_code"), rs.getString("sales_no"),
                SalesUuidCodec.decode(rs.getBytes("city_org_id")), rs.getString("status"),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("effective_to") == null ? null : rs.getTimestamp("effective_to").toInstant()),
                bin(tenantId), bin(userId)).stream().findFirst();
    }

    @Override
    public Registration register(UUID tenantId, UUID userId, Employee employee, Instant now) {
        // 即使碰到员工唯一键也先取得行锁，换号和重复开通共用同一份档案。
        jdbc.update("""
                INSERT INTO sales_profile
                    (id, tenant_id, employee_id, platform_user_id, hr_employee_id, hr_employee_code,
                     sales_no, status, effective_from, source_version, version, created_at, updated_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0, ?, ?)
                ON DUPLICATE KEY UPDATE id=id
                """, bin(UUID.randomUUID()), bin(tenantId), bin(userId), employee.id(), employee.code(),
                employee.code(), Timestamp.from(now), employee.revision(), Timestamp.from(now), Timestamp.from(now));
        var own = find(tenantId, userId, true);
        if (own.isPresent()) return new Registration(own.get(), null);

        UUID previous = jdbc.query("""
                SELECT platform_user_id FROM sales_profile
                 WHERE tenant_id=? AND hr_employee_id=? AND hr_employee_code=?
                   AND employee_id IS NULL AND platform_user_id IS NOT NULL FOR UPDATE
                """, (rs, row) -> SalesUuidCodec.decode(rs.getBytes(1)),
                bin(tenantId), employee.id(), employee.code()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_EMPLOYEE_BINDING_CONFLICT));
        if (hasLegacyBinding(tenantId, previous)) throw new BusinessException(ErrorCode.SALES_EMPLOYEE_BINDING_CONFLICT);
        jdbc.update("""
                UPDATE sales_profile SET platform_user_id=?, version=version+1, updated_at=?
                 WHERE tenant_id=? AND platform_user_id=? AND hr_employee_id=? AND hr_employee_code=?
                """, bin(userId), Timestamp.from(now), bin(tenantId), bin(previous), employee.id(), employee.code());
        return new Registration(find(tenantId, userId, true).orElseThrow(), previous);
    }

    @Override
    public boolean hasLegacyBinding(UUID tenantId, UUID userId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_identity_projection WHERE tenant_id=? AND platform_user_id=?
                """, Integer.class, bin(tenantId), bin(userId)) > 0;
    }

    private static byte[] bin(UUID value) { return SalesUuidCodec.encode(value); }
}
