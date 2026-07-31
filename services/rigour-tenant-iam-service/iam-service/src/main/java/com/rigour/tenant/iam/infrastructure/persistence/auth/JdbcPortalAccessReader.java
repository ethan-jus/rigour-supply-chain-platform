package com.rigour.tenant.iam.infrastructure.persistence.auth;

import com.rigour.tenant.iam.application.port.out.PortalAccessReader;
import com.rigour.tenant.iam.application.service.portal.PortalAccessQuery;
import com.rigour.tenant.iam.application.service.portal.PortalApplication;
import com.rigour.tenant.iam.application.service.portal.PortalCurrentUser;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 以IAM当前事实计算Portal用户、权限和应用卡片；不信任Token内的角色或权限集合。 */
public final class JdbcPortalAccessReader implements PortalAccessReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPortalAccessReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PortalCurrentUser readCurrentUser(PortalAccessQuery query) {
        return "PLATFORM".equals(query.principalScope()) ? platformUser(query) : tenantUser(query);
    }

    @Override
    public List<PortalApplication> readGrantedApplications(PortalAccessQuery query) {
        if ("PLATFORM".equals(query.principalScope())) {
            PortalCurrentUser user = platformUser(query);
            if (!user.roles().contains("SUPER_ADMIN")) {
                return List.of();
            }
            return jdbcTemplate.query("""
                            SELECT id, app_code, app_name, icon_key, launch_mode, target_uri, sort_order
                              FROM iam_application
                             WHERE app_scope = 'PLATFORM' AND status = 'ACTIVE' AND deleted_at IS NULL
                             ORDER BY sort_order, app_code
                            """, (rs, row) -> application(rs));
        }
        byte[] tenantId = UuidBinaryCodec.encode(query.tenantId());
        byte[] userId = UuidBinaryCodec.encode(query.principalId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return jdbcTemplate.query("""
                        SELECT DISTINCT a.id, a.app_code, a.app_name, a.icon_key,
                               a.launch_mode, a.target_uri, a.sort_order
                          FROM iam_user_role ur
                          JOIN iam_role role_record
                            ON role_record.tenant_id = ur.tenant_id AND role_record.id = ur.role_id
                          JOIN iam_role_resource rr
                            ON rr.tenant_id = ur.tenant_id AND rr.role_id = ur.role_id
                          JOIN iam_resource resource_record
                            ON resource_record.id = rr.resource_id
                          JOIN iam_application a
                            ON a.id = resource_record.application_id
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
                           AND rr.status = 'ACTIVE'
                           AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
                           AND a.app_scope = 'TENANT' AND a.status = 'ACTIVE' AND a.deleted_at IS NULL
                         ORDER BY a.sort_order, a.app_code
                        """, (rs, row) -> application(rs), now, now, tenantId, userId, now, now);
    }

    private PortalCurrentUser platformUser(PortalAccessQuery query) {
        List<PortalCurrentUser> users = jdbcTemplate.query("""
                        SELECT id, username, display_name, platform_role
                          FROM iam_platform_user
                         WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                        """, (rs, row) -> {
                    String role = rs.getString("platform_role");
                    Set<String> permissions = "SUPER_ADMIN".equals(role) ? Set.of("*:*:*") : Set.of();
                    return new PortalCurrentUser(
                            UuidBinaryCodec.decode(rs.getBytes("id")), null, null, "PLATFORM",
                            rs.getString("username"), rs.getString("display_name"), Set.of(role), permissions);
                }, UuidBinaryCodec.encode(query.principalId()));
        return exactlyOne(users);
    }

    private PortalCurrentUser tenantUser(PortalAccessQuery query) {
        byte[] tenantId = UuidBinaryCodec.encode(query.tenantId());
        byte[] userId = UuidBinaryCodec.encode(query.principalId());
        List<PortalCurrentUser> users = jdbcTemplate.query("""
                        SELECT u.id, u.tenant_id, u.username, u.display_name, t.company_name
                          FROM iam_user u
                          JOIN iam_tenant t ON t.id = u.tenant_id
                         WHERE u.tenant_id = ? AND u.id = ?
                           AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                           AND t.status = 'ACTIVE' AND t.deleted_at IS NULL
                        """, (rs, row) -> new PortalCurrentUser(
                        UuidBinaryCodec.decode(rs.getBytes("id")),
                        UuidBinaryCodec.decode(rs.getBytes("tenant_id")), rs.getString("company_name"), "TENANT",
                        rs.getString("username"), rs.getString("display_name"),
                        tenantRoles(tenantId, userId), tenantPermissions(tenantId, userId)),
                tenantId, userId);
        return exactlyOne(users);
    }

    private Set<String> tenantRoles(byte[] tenantId, byte[] userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                        SELECT role_record.role_code
                          FROM iam_user_role ur
                          JOIN iam_role role_record
                            ON role_record.tenant_id = ur.tenant_id AND role_record.id = ur.role_id
                         WHERE ur.tenant_id = ? AND ur.user_id = ? AND ur.status = 'ACTIVE'
                           AND ur.effective_from <= ? AND (ur.effective_to IS NULL OR ur.effective_to > ?)
                           AND role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
                         ORDER BY role_record.role_code
                        """, String.class, tenantId, userId, now, now));
    }

    private Set<String> tenantPermissions(byte[] tenantId, byte[] userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                        SELECT DISTINCT resource_record.permission_code
                          FROM iam_user_role ur
                          JOIN iam_role role_record
                            ON role_record.tenant_id = ur.tenant_id AND role_record.id = ur.role_id
                          JOIN iam_role_resource rr
                            ON rr.tenant_id = ur.tenant_id AND rr.role_id = ur.role_id
                          JOIN iam_resource resource_record ON resource_record.id = rr.resource_id
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
                        """, String.class, now, now, tenantId, userId, now, now));
    }

    private static PortalApplication application(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PortalApplication(
                UuidBinaryCodec.decode(rs.getBytes("id")), rs.getString("app_code"),
                rs.getString("app_name"), rs.getString("icon_key"), rs.getString("launch_mode"),
                rs.getString("target_uri"), rs.getInt("sort_order"));
    }

    private static PortalCurrentUser exactlyOne(List<PortalCurrentUser> users) {
        if (users.size() != 1) {
            throw new IllegalStateException("Authenticated IAM principal is no longer active");
        }
        return users.getFirst();
    }
}
