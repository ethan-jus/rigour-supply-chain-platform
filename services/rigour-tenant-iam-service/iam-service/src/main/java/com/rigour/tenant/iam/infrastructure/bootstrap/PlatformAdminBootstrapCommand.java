package com.rigour.tenant.iam.infrastructure.bootstrap;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.io.Console;
import java.nio.CharBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 显式、一次性的平台超级管理员初始化命令。
 * 密码只能从真实交互终端读取，不接受环境变量、Nacos、命令行或标准输入重定向。
 */
public final class PlatformAdminBootstrapCommand implements ApplicationRunner {

    private static final int MAXIMUM_PASSWORD_LENGTH = 128;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordHasher passwordHasher;
    private final IdentifierGenerator identifierGenerator;
    private final TransactionOperations transactionOperations;
    private final PlatformAdminBootstrapProperties properties;

    public PlatformAdminBootstrapCommand(
            JdbcTemplate jdbcTemplate,
            PasswordHasher passwordHasher,
            IdentifierGenerator identifierGenerator,
            TransactionOperations transactionOperations,
            PlatformAdminBootstrapProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordHasher = passwordHasher;
        this.identifierGenerator = identifierGenerator;
        this.transactionOperations = transactionOperations;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = normalizeRequired(properties.getUsername(), "bootstrap username", 64);
        String displayName = normalizeRequired(properties.getDisplayName(), "bootstrap display name", 128);
        if (properties.getMinimumPasswordLength() < 12
                || properties.getMinimumPasswordLength() > MAXIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException("bootstrap minimum password length must be between 12 and 128");
        }
        refuseWhenAdministratorExists();

        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Platform admin bootstrap requires an interactive system console");
        }
        char[] password = console.readPassword("New platform administrator password: ");
        char[] confirmation = console.readPassword("Confirm platform administrator password: ");
        try {
            validatePassword(password, confirmation);
            String passwordHash = passwordHasher.hash(CharBuffer.wrap(password));
            transactionOperations.executeWithoutResult(ignored -> insertFirstAdministrator(
                    username, displayName, passwordHash));
        } finally {
            erase(password);
            erase(confirmation);
        }
    }

    private void refuseWhenAdministratorExists() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam_platform_user", Long.class);
        if (count == null || count != 0) {
            throw new IllegalStateException("Platform administrator already exists; bootstrap is one-time only");
        }
    }

    private void insertFirstAdministrator(String username, String displayName, String passwordHash) {
        refuseWhenAdministratorExists();
        UUID userId = identifierGenerator.nextId();
        UUID credentialId = identifierGenerator.nextId();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO iam_platform_user (
                            id, username, display_name, platform_role, status, security_version, version,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, 'SUPER_ADMIN', 'ACTIVE', 0, 0, ?, ?)
                        """, UuidBinaryCodec.encode(userId), username, displayName, now, now);
        jdbcTemplate.update("""
                        INSERT INTO iam_platform_user_credential (
                            id, platform_user_id, credential_type, password_hash, algorithm, algorithm_version,
                            failed_attempts, password_changed_at, status, version, created_at, updated_at
                        ) VALUES (?, ?, 'PASSWORD', ?, 'ARGON2ID', 1, 0, ?, 'ACTIVE', 0, ?, ?)
                        """, UuidBinaryCodec.encode(credentialId), UuidBinaryCodec.encode(userId),
                passwordHash, now, now, now);
    }

    private void validatePassword(char[] password, char[] confirmation) {
        if (password == null || confirmation == null
                || password.length < properties.getMinimumPasswordLength()
                || password.length > MAXIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException("Bootstrap password does not satisfy the configured length policy");
        }
        if (!Arrays.equals(password, confirmation)) {
            throw new IllegalStateException("Bootstrap password confirmation does not match");
        }
    }

    private static String normalizeRequired(String value, String field, int maximumLength) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalStateException(field + " is required and must not exceed " + maximumLength);
        }
        return normalized;
    }

    private static void erase(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
