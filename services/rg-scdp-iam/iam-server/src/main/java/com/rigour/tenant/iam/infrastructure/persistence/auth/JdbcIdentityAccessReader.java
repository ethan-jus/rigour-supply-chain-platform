package com.rigour.tenant.iam.infrastructure.persistence.auth;

import com.rigour.tenant.iam.application.port.out.IdentityAccessReader;
import com.rigour.tenant.iam.application.service.identity.IdentityAccessQuery;
import com.rigour.tenant.iam.application.service.identity.CurrentUser;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 读取租户身份及 SCDP 历史权限；供登录身份和权限升级核对使用。 */
public final class JdbcIdentityAccessReader implements IdentityAccessReader {

    private final JdbcTemplate jdbcTemplate;
    private final com.rigour.tenant.iam.application.port.out.AppSettingsStore supplySettings;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JdbcIdentityAccessReader.class);

    public JdbcIdentityAccessReader(
            JdbcTemplate jdbcTemplate,
            com.rigour.tenant.iam.application.port.out.AppSettingsStore supplySettings) {
        this.jdbcTemplate = jdbcTemplate;
        this.supplySettings = supplySettings;
    }

    @Override
    public CurrentUser readCurrentUser(IdentityAccessQuery query) {
        if (!"TENANT".equals(query.principalScope()) || query.tenantId() == null)
            throw new org.springframework.security.access.AccessDeniedException("SCDP requires a tenant account");
        return tenantUser(query);
    }

    private CurrentUser tenantUser(IdentityAccessQuery query) {
        byte[] tenantId = UuidBinaryCodec.encode(query.tenantId());
        byte[] userId = UuidBinaryCodec.encode(query.principalId());
        List<CurrentUser> users =
                jdbcTemplate.query(
                        """
                        SELECT u.id, u.tenant_id, u.username, u.display_name, t.company_name
                          FROM iam_user u
                          JOIN iam_tenant t ON t.id = u.tenant_id
                         WHERE u.tenant_id = ? AND u.id = ?
                           AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                           AND t.status = 'ACTIVE' AND t.deleted_at IS NULL
                        """,
                        (rs, row) ->
                                new CurrentUser(
                                        UuidBinaryCodec.decode(rs.getBytes("id")),
                                        UuidBinaryCodec.decode(rs.getBytes("tenant_id")),
                                        rs.getString("company_name"),
                                        "TENANT",
                                        rs.getString("username"),
                                        rs.getString("display_name"),
                                        tenantRoles(tenantId, userId),
                                        tenantPermissions(tenantId, userId)),
                        tenantId,
                        userId);
        return exactlyOne(users);
    }

    @Override
    public Set<String> readSupplyRoles(IdentityAccessQuery query) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
            SELECT r.role_code FROM iam_app_member_role mr
              JOIN iam_app_role r ON r.tenant_id=mr.tenant_id AND r.application_id=mr.application_id AND r.id=mr.role_id
              JOIN iam_application app ON app.id=r.application_id AND app.app_code='SUPPLY_CHAIN'
              JOIN iam_app_member m ON m.tenant_id=mr.tenant_id AND m.application_id=mr.application_id AND m.user_id=mr.user_id
             WHERE mr.tenant_id=? AND mr.user_id=? AND r.status='ACTIVE' AND r.deleted_at IS NULL
               AND m.status='ACTIVE' AND m.deleted_at IS NULL
            """, String.class, UuidBinaryCodec.encode(query.tenantId()), UuidBinaryCodec.encode(query.principalId())));
    }

    private Set<String> tenantRoles(byte[] tenantId, byte[] userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new LinkedHashSet<>(
                jdbcTemplate.queryForList(
                        """
SELECT role_record.role_code
  FROM iam_user_role ur
  JOIN iam_role role_record
    ON role_record.tenant_id = ur.tenant_id AND role_record.id = ur.role_id
 WHERE ur.tenant_id = ? AND ur.user_id = ? AND ur.status = 'ACTIVE'
   AND ur.effective_from <= ? AND (ur.effective_to IS NULL OR ur.effective_to > ?)
   AND role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
   AND (role_record.role_code <> 'TENANT_SUPER_ADMIN' OR role_record.role_type = 'SYSTEM')
 ORDER BY role_record.role_code
""",
                        String.class,
                        tenantId,
                        userId,
                        now,
                        now));
    }

    private Set<String> tenantPermissions(byte[] tenantId, byte[] userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new LinkedHashSet<>(
                jdbcTemplate.queryForList(
                        """
SELECT DISTINCT resource_record.permission_code
  FROM iam_user_role ur
  JOIN iam_role role_record
    ON role_record.tenant_id = ur.tenant_id AND role_record.id = ur.role_id
  JOIN iam_effective_tenant_role_resource rr
    ON rr.tenant_id = ur.tenant_id AND rr.role_id = ur.role_id
  JOIN iam_resource resource_record ON resource_record.id = rr.resource_id
  JOIN iam_application app ON app.id = resource_record.application_id
    AND app.app_code='SUPPLY_CHAIN' AND app.status='ACTIVE' AND app.deleted_at IS NULL
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = ur.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= ? AND subscription.effective_to > ?
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = rr.resource_id
 WHERE ur.tenant_id = ? AND ur.user_id = ? AND ur.status = 'ACTIVE'
   AND ur.effective_from <= ? AND (ur.effective_to IS NULL OR ur.effective_to > ?)
   AND role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
   AND rr.status = 'ACTIVE' AND resource_record.status = 'ACTIVE'
   AND resource_record.deleted_at IS NULL AND resource_record.permission_code IS NOT NULL
 ORDER BY resource_record.permission_code
""",
                        String.class,
                        now,
                        now,
                        tenantId,
                        userId,
                        now,
                        now));
    }

    private static CurrentUser exactlyOne(List<CurrentUser> users) {
        if (users.size() != 1) {
            throw new IllegalStateException("Authenticated IAM principal is no longer active");
        }
        return users.getFirst();
    }
}
