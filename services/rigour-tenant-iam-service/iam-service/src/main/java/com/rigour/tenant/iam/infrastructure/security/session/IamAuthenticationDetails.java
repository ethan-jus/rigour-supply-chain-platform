package com.rigour.tenant.iam.infrastructure.security.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * IAM登录成功后写入Spring Security Authentication.details的稳定字段。
 * 使用受控字符串Map，避免把数据库实体或含凭据对象序列化进OAuth授权上下文。
 */
public final class IamAuthenticationDetails {

    public static final String SESSION_ID = "iam_session_id";
    public static final String PRINCIPAL_SCOPE = "iam_principal_scope";
    public static final String PRINCIPAL_ID = "iam_principal_id";
    public static final String TENANT_ID = "iam_tenant_id";
    public static final String SECURITY_VERSION = "iam_security_version";

    private IamAuthenticationDetails() {
    }

    public static Map<String, String> create(
            UUID sessionId,
            String principalScope,
            UUID principalId,
            UUID tenantId,
            long securityVersion
    ) {
        if (sessionId == null || principalId == null || principalScope == null || principalScope.isBlank()) {
            throw new IllegalArgumentException("sessionId, principalScope and principalId cannot be null");
        }
        if (securityVersion < 0) {
            throw new IllegalArgumentException("securityVersion cannot be negative");
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put(SESSION_ID, sessionId.toString());
        details.put(PRINCIPAL_SCOPE, principalScope);
        details.put(PRINCIPAL_ID, principalId.toString());
        if (tenantId != null) {
            details.put(TENANT_ID, tenantId.toString());
        }
        details.put(SECURITY_VERSION, Long.toString(securityVersion));
        return details;
    }
}
