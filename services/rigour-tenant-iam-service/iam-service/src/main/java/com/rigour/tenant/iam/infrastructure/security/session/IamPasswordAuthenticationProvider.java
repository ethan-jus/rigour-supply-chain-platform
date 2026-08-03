package com.rigour.tenant.iam.infrastructure.security.session;

import com.rigour.tenant.iam.application.service.auth.AuthService;
import com.rigour.tenant.iam.application.service.auth.PasswordAuthenticationAttempt;
import com.rigour.tenant.iam.application.service.auth.PasswordLoginCommand;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.transaction.support.TransactionOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将统一密码登录用例接入Spring Security，并确保失败计数先提交再抛统一异常。 */
public final class IamPasswordAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(IamPasswordAuthenticationProvider.class);
    private static final String FAILURE_MESSAGE = "Authentication failed";

    private final AuthService authService;
    private final TransactionOperations transactionOperations;

    public IamPasswordAuthenticationProvider(
            AuthService authService, TransactionOperations transactionOperations) {
        this.authService = authService;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        IamLoginAuthenticationToken request = (IamLoginAuthenticationToken) authentication;
        char[] password = (char[]) request.getCredentials();
        try {
            PasswordLoginCommand command = new PasswordLoginCommand(
                    request.principalScope(), request.tenantCode(), request.getName(), password,
                    request.clientType(), request.deviceName(), request.clientFingerprintHash(),
                    request.userAgentHash(), request.ipAddress());
            PasswordAuthenticationAttempt attempt = transactionOperations.execute(
                    ignored -> authService.authenticate(command));
            if (!(attempt instanceof PasswordAuthenticationAttempt.Success success)) {
                String reason = attempt instanceof PasswordAuthenticationAttempt.Failure failure
                        ? failure.reason().name() : "UNKNOWN";
                log.warn("IAM密码登录失败 scope={} tenantCode={} username={} reason={}",
                        request.principalScope(), safeTenantCode(request.tenantCode()), request.getName(), reason);
                throw new BadCredentialsException(FAILURE_MESSAGE);
            }

            log.info("IAM密码登录成功 scope={} tenantCode={} username={} principalId={} sessionId={}",
                    success.principalScope(), safeTenantCode(request.tenantCode()), request.getName(),
                    success.principalId(), success.sessionId());

            UsernamePasswordAuthenticationToken result = UsernamePasswordAuthenticationToken.authenticated(
                    success.principalId().toString(), null, authorities(success));
            result.setDetails(IamAuthenticationDetails.create(
                    success.sessionId(), success.principalScope().name(), success.principalId(),
                    success.tenantId(), success.securityVersion()));
            return result;
        } finally {
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
            }
            request.eraseCredentials();
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return IamLoginAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static List<GrantedAuthority> authorities(PasswordAuthenticationAttempt.Success success) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));
        authorities.add(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"));
        if (success.platformRole() != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + success.platformRole()));
        }
        return List.copyOf(authorities);
    }

    private static String safeTenantCode(String tenantCode) {
        return tenantCode == null || tenantCode.isBlank() ? "-" : tenantCode;
    }
}
