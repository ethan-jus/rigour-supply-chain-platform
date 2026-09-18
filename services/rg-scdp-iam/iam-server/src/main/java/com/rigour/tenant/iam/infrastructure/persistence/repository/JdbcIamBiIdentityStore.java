package com.rigour.tenant.iam.infrastructure.persistence.repository;

import com.rigour.tenant.iam.application.model.IamBiIdentity;
import com.rigour.tenant.iam.application.port.out.IamBiIdentityStore;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;

/** IAM 本库精确账号绑定查询，多个有效绑定视为歧义并拒绝，不猜测员工姓名。 */
@Repository
public class JdbcIamBiIdentityStore implements IamBiIdentityStore {
    private final JdbcTemplate jdbc;
    public JdbcIamBiIdentityStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public IamBiIdentity read(UUID tenantId, UUID userId) {
        var identities = jdbc.query("""
                SELECT BIN_TO_UUID(s.id) AS staff_id, s.staff_code, u.security_version, t.policy_version
                  FROM iam_staff_user_binding b
                  JOIN iam_staff_profile s ON s.tenant_id=b.tenant_id AND s.id=b.staff_id
                  JOIN iam_user u ON u.tenant_id=b.tenant_id AND u.id=b.user_id
                  JOIN iam_tenant t ON t.id=b.tenant_id
                 WHERE b.tenant_id=? AND b.user_id=? AND b.status='ACTIVE' AND b.deleted_at IS NULL
                   AND s.employment_status='ACTIVE' AND s.deleted_at IS NULL
                   AND u.status='ACTIVE' AND u.deleted_at IS NULL AND t.status='ACTIVE' AND t.deleted_at IS NULL
                """, (rs, row) -> new IamBiIdentity(userId, rs.getString("staff_id"), rs.getString("staff_code"),
                rs.getLong("security_version"), rs.getLong("policy_version"), java.util.List.of(), java.util.List.of()), bin(tenantId), bin(userId));
        if (identities.size() != 1) throw new AccessDeniedException("Exact active employee binding required");
        var policies = jdbc.query("""
                SELECT BIN_TO_UUID(p.id) AS policy_id, r.role_code, p.scope_type
                  FROM iam_data_scope_policy p
                  JOIN iam_role r ON r.tenant_id=p.tenant_id AND r.id=p.role_id
                  JOIN iam_user_role ur ON ur.tenant_id=r.tenant_id AND ur.role_id=r.id AND ur.user_id=?
                 WHERE p.tenant_id=? AND p.status='ACTIVE' AND p.deleted_at IS NULL
                   AND r.status='ACTIVE' AND r.deleted_at IS NULL
                   AND ur.status='ACTIVE' AND ur.effective_from <= UTC_TIMESTAMP(6)
                   AND (ur.effective_to IS NULL OR ur.effective_to > UTC_TIMESTAMP(6))
                   AND EXISTS (SELECT 1 FROM iam_resource res WHERE res.application_id=p.application_id
                       AND res.permission_code='analytics:dashboard:read' AND res.status='ACTIVE' AND res.deleted_at IS NULL)
                """, (rs, row) -> new IamBiIdentity.Policy(rs.getString("policy_id"), rs.getString("role_code"),
                rs.getString("scope_type")), bin(userId), bin(tenantId));
        var found = identities.getFirst();
        var aliases = jdbc.query("""
                SELECT source_system, source_tenant_key, source_staff_id
                  FROM iam_external_staff_binding
                 WHERE tenant_id=? AND staff_id=? AND deleted_at IS NULL AND source_presence='PRESENT'
                """, (rs, row) -> new IamBiIdentity.ExternalBinding(rs.getString("source_system"),
                rs.getString("source_tenant_key"), rs.getString("source_staff_id")), bin(tenantId), bin(UUID.fromString(found.staffId())));
        return new IamBiIdentity(userId, found.staffId(), found.staffCode(), found.userSecurityVersion(), found.tenantPolicyVersion(), policies, aliases);
    }
    private static byte[] bin(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
