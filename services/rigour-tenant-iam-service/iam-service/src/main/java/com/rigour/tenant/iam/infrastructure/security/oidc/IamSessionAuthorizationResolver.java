package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.rigour.tenant.iam.infrastructure.security.session.IamAuthenticationDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/** 从已认证主体的受控details中解析IAM会话，拒绝缺失、伪造格式或主体不一致的数据。 */
public final class IamSessionAuthorizationResolver implements AuthorizationSessionResolver {

    @Override
    public UUID resolveSessionId(OAuth2Authorization authorization) {
        Object principalAttribute = authorization.getAttribute(Principal.class.getName());
        if (!(principalAttribute instanceof Authentication authentication) || !authentication.isAuthenticated()) {
            throw new IllegalArgumentException("OAuth authorization requires an authenticated IAM principal");
        }
        if (!(authentication.getDetails() instanceof Map<?, ?> details)) {
            throw new IllegalArgumentException("Authenticated IAM principal is missing session details");
        }
        UUID sessionId = parseUuid(details.get(IamAuthenticationDetails.SESSION_ID), "IAM session id");
        UUID principalId = parseUuid(details.get(IamAuthenticationDetails.PRINCIPAL_ID), "IAM principal id");
        if (!principalId.toString().equals(authorization.getPrincipalName())) {
            throw new IllegalArgumentException("OAuth principal does not match IAM authentication details");
        }
        return sessionId;
    }

    private UUID parseUuid(Object value, String field) {
        try {
            return UUID.fromString((String) value);
        } catch (ClassCastException | IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(field + " must be a UUID string", exception);
        }
    }
}
