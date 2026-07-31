package com.rigour.shared.context;

import java.util.List;

/**
 * 平台信任链使用的标准请求头名称。
 * Gateway 负责校验或补全这些值，领域服务仍需在本地建立请求级上下文。
 */
public final class RequestHeaders {

    public static final String TENANT_ID = "X-Rigour-Tenant-Id";
    public static final String PRINCIPAL_SCOPE = "X-Rigour-Principal-Scope";
    public static final String PRINCIPAL_ID = "X-Rigour-Principal-Id";
    public static final String USER_ID = "X-Rigour-User-Id";
    public static final String PLATFORM_USER_ID = "X-Rigour-Platform-User-Id";
    public static final String SESSION_ID = "X-Rigour-Session-Id";
    public static final String SESSION_VERSION = "X-Rigour-Session-Version";
    public static final String USER_SECURITY_VERSION = "X-Rigour-User-Security-Version";
    public static final String TENANT_POLICY_VERSION = "X-Rigour-Tenant-Policy-Version";
    public static final String ROLES = "X-Rigour-Roles";
    public static final String PERMISSIONS = "X-Rigour-Permissions";
    public static final String CONTEXT_KEY_ID = "X-Rigour-Context-Key-Id";
    public static final String CONTEXT_TIMESTAMP = "X-Rigour-Context-Timestamp";
    public static final String CONTEXT_SIGNATURE = "X-Rigour-Context-Signature";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String ACCEPT_LANGUAGE = "Accept-Language";

    /** 签名覆盖的身份上下文头，顺序是稳定协议的一部分。 */
    public static final List<String> SIGNED_CONTEXT_HEADERS = List.of(
            PRINCIPAL_SCOPE, PRINCIPAL_ID, TENANT_ID, USER_ID, PLATFORM_USER_ID, SESSION_ID,
            SESSION_VERSION, USER_SECURITY_VERSION, TENANT_POLICY_VERSION, ROLES, PERMISSIONS);

    public static final List<String> ALL_CONTEXT_HEADERS = java.util.stream.Stream.concat(
            SIGNED_CONTEXT_HEADERS.stream(),
            java.util.stream.Stream.of(CONTEXT_KEY_ID, CONTEXT_TIMESTAMP, CONTEXT_SIGNATURE)).toList();

    private RequestHeaders() {
    }
}
