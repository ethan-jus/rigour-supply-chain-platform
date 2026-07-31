package com.rigour.tenant.iam.infrastructure.security.session;

import com.rigour.tenant.iam.domain.model.session.AuthSession.ClientType;
import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.CredentialsContainer;

/** 仅表示未认证登录请求；认证结束后主动覆盖密码字符数组。 */
public final class IamLoginAuthenticationToken extends AbstractAuthenticationToken
        implements CredentialsContainer {

    private final PrincipalScope principalScope;
    private final String tenantCode;
    private final Object principal;
    private char[] credentials;
    private final ClientType clientType;
    private final String deviceName;
    private final byte[] clientFingerprintHash;
    private final byte[] userAgentHash;
    private final byte[] ipAddress;

    public IamLoginAuthenticationToken(
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
        super(List.of());
        this.principalScope = principalScope;
        this.tenantCode = tenantCode;
        this.principal = username;
        this.credentials = copy(password);
        this.clientType = clientType;
        this.deviceName = deviceName;
        this.clientFingerprintHash = copy(clientFingerprintHash);
        this.userAgentHash = copy(userAgentHash);
        this.ipAddress = copy(ipAddress);
        super.setAuthenticated(false);
    }

    @Override
    public Object getCredentials() {
        return copy(credentials);
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    public PrincipalScope principalScope() {
        return principalScope;
    }

    public String tenantCode() {
        return tenantCode;
    }

    public ClientType clientType() {
        return clientType;
    }

    public String deviceName() {
        return deviceName;
    }

    public byte[] clientFingerprintHash() {
        return copy(clientFingerprintHash);
    }

    public byte[] userAgentHash() {
        return copy(userAgentHash);
    }

    public byte[] ipAddress() {
        return copy(ipAddress);
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("Login request token cannot become authenticated");
        }
        super.setAuthenticated(false);
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        if (credentials != null) {
            Arrays.fill(credentials, '\0');
            credentials = null;
        }
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static char[] copy(char[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
