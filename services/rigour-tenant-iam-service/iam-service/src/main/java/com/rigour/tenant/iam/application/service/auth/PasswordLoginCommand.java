package com.rigour.tenant.iam.application.service.auth;

import com.rigour.tenant.iam.domain.model.session.AuthSession.ClientType;
import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import java.util.Arrays;
import java.util.Objects;

/** 密码登录输入；password由认证提供器在完成后负责擦除。 */
public record PasswordLoginCommand(
        PrincipalScope principalScope,
        String tenantCode,
        String username,
        char[] password,
        ClientType clientType,
        String deviceName,
        byte[] clientFingerprintHash,
        byte[] userAgentHash,
        byte[] ipAddress
) {
    public PasswordLoginCommand {
        Objects.requireNonNull(principalScope, "principalScope cannot be null");
        Objects.requireNonNull(username, "username cannot be null");
        Objects.requireNonNull(password, "password cannot be null");
        Objects.requireNonNull(clientType, "clientType cannot be null");
        tenantCode = normalize(tenantCode);
        username = normalize(username);
        deviceName = normalize(deviceName);
        password = Arrays.copyOf(password, password.length);
        clientFingerprintHash = copy(clientFingerprintHash);
        userAgentHash = copy(userAgentHash);
        ipAddress = copy(ipAddress);
        if (username == null || username.length() > 64 || password.length == 0) {
            throw new IllegalArgumentException("username and password are required");
        }
        if (principalScope == PrincipalScope.TENANT && (tenantCode == null || tenantCode.length() > 32)) {
            throw new IllegalArgumentException("tenantCode is required for tenant login");
        }
        if (principalScope == PrincipalScope.PLATFORM && tenantCode != null) {
            throw new IllegalArgumentException("platform login must not have tenantCode");
        }
        if (deviceName != null && deviceName.length() > 128) {
            throw new IllegalArgumentException("deviceName is too long");
        }
    }

    @Override
    public char[] password() {
        return Arrays.copyOf(password, password.length);
    }

    public void erasePassword() {
        Arrays.fill(password, '\0');
    }

    @Override
    public byte[] clientFingerprintHash() {
        return copy(clientFingerprintHash);
    }

    @Override
    public byte[] userAgentHash() {
        return copy(userAgentHash);
    }

    @Override
    public byte[] ipAddress() {
        return copy(ipAddress);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
