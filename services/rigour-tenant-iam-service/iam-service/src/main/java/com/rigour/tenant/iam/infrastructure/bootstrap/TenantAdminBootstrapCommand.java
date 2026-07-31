package com.rigour.tenant.iam.infrastructure.bootstrap;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.io.Console;
import java.nio.CharBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** 已开通套餐租户的首个系统管理员一次性初始化命令。 */
public final class TenantAdminBootstrapCommand implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;
    private final IdentifierGenerator ids;
    private final TransactionOperations transaction;
    private final TenantAdminBootstrapProperties properties;

    public TenantAdminBootstrapCommand(JdbcTemplate jdbc, PasswordHasher passwordHasher, IdentifierGenerator ids,
                                       TransactionOperations transaction, TenantAdminBootstrapProperties properties) {
        this.jdbc = jdbc; this.passwordHasher = passwordHasher; this.ids = ids;
        this.transaction = transaction; this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String tenantCode = required(properties.getTenantCode(), "tenant code", 32);
        String username = required(properties.getUsername(), "username", 64).toLowerCase();
        String displayName = required(properties.getDisplayName(), "display name", 128);
        List<byte[]> tenants = jdbc.queryForList("""
                SELECT id FROM iam_tenant WHERE tenant_code=? AND status='ACTIVE' AND deleted_at IS NULL
                """, byte[].class, tenantCode);
        if (tenants.size() != 1) throw new IllegalStateException("Active bootstrap tenant does not exist");
        byte[] tenantId = tenants.getFirst();
        refuseExisting(tenantId, username);
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Tenant admin bootstrap requires an interactive console");
        char[] password = console.readPassword("New tenant administrator password: ");
        char[] confirmation = console.readPassword("Confirm tenant administrator password: ");
        try {
            if (password == null || confirmation == null || password.length < properties.getMinimumPasswordLength()
                    || password.length > 128 || !Arrays.equals(password, confirmation)) {
                throw new IllegalStateException("Tenant administrator password is invalid or does not match");
            }
            String hash = passwordHasher.hash(CharBuffer.wrap(password));
            transaction.executeWithoutResult(status -> create(tenantId, username, displayName, hash));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private void create(byte[] tenantId, String username, String displayName, String hash) {
        refuseExisting(tenantId, username);
        if (count("""
                SELECT COUNT(*) FROM iam_tenant_subscription WHERE tenant_id=? AND status IN ('ACTIVE','SCHEDULED')
                 AND effective_from<=UTC_TIMESTAMP(6) AND effective_to>UTC_TIMESTAMP(6)
                """, tenantId) != 1) throw new IllegalStateException("Tenant requires exactly one active subscription");
        UUID userId = ids.nextId(); UUID credentialId = ids.nextId(); UUID roleId = ids.nextId();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO iam_user (id, tenant_id, username, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, UuidBinaryCodec.encode(userId), tenantId, username, displayName, now, now);
        jdbc.update("""
                INSERT INTO iam_user_credential
                (id, tenant_id, user_id, credential_type, password_hash, algorithm, algorithm_version,
                 password_changed_at, status, created_at, updated_at)
                VALUES (?, ?, ?, 'PASSWORD', ?, 'ARGON2ID', 1, ?, 'ACTIVE', ?, ?)
                """, UuidBinaryCodec.encode(credentialId), tenantId, UuidBinaryCodec.encode(userId), hash, now, now, now);
        jdbc.update("""
                INSERT INTO iam_role
                (id, tenant_id, role_code, role_name, role_type, description, status, created_at, updated_at)
                VALUES (?, ?, 'TENANT_SUPER_ADMIN', '租户超级管理员', 'SYSTEM', '租户内IAM管理全部权限', 'ACTIVE', ?, ?)
                """, UuidBinaryCodec.encode(roleId), tenantId, now, now);
        jdbc.update("""
                INSERT INTO iam_role_resource
                (tenant_id, role_id, resource_id, status, created_at, updated_at)
                SELECT ?, ?, r.id, 'ACTIVE', ?, ?
                  FROM iam_resource r
                  JOIN iam_package_resource pr ON pr.resource_id=r.id
                  JOIN iam_tenant_subscription subscription ON subscription.package_version_id=pr.package_version_id
                   AND subscription.tenant_id=? AND subscription.status IN ('ACTIVE','SCHEDULED')
                   AND subscription.effective_from<=UTC_TIMESTAMP(6) AND subscription.effective_to>UTC_TIMESTAMP(6)
                 WHERE r.status='ACTIVE' AND r.deleted_at IS NULL
                """, tenantId, UuidBinaryCodec.encode(roleId), now, now, tenantId);
        jdbc.update("""
                INSERT INTO iam_user_role
                (tenant_id, user_id, role_id, status, effective_from, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, tenantId, UuidBinaryCodec.encode(userId), UuidBinaryCodec.encode(roleId), now, now, now);
        jdbc.update("UPDATE iam_tenant SET policy_version=policy_version+1, version=version+1, updated_at=? WHERE id=?",
                now, tenantId);
    }

    private void refuseExisting(byte[] tenantId, String username) {
        if (count("""
                SELECT COUNT(*) FROM iam_user WHERE tenant_id=? AND (username=? OR deleted_at IS NULL AND id IN (
                  SELECT ur.user_id FROM iam_user_role ur JOIN iam_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
                  WHERE ur.tenant_id=? AND r.role_code='TENANT_SUPER_ADMIN'))
                """, tenantId, username, tenantId) != 0) {
            throw new IllegalStateException("Tenant administrator already exists; bootstrap is one-time only");
        }
    }
    private int count(String sql, Object... args) { Integer value=jdbc.queryForObject(sql,Integer.class,args);return value==null?0:value; }
    private static String required(String value, String name, int max) {
        String normalized=value==null?null:value.strip();
        if(normalized==null||normalized.isEmpty()||normalized.length()>max)throw new IllegalStateException(name+" is invalid");
        return normalized;
    }
}
