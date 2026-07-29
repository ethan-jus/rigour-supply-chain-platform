package com.rigour.shared.context;

/**
 * 平台信任链使用的标准请求头名称。
 * Gateway 负责校验或补全这些值，领域服务仍需在本地建立请求级上下文。
 */
public final class RequestHeaders {

    public static final String TENANT_ID = "X-Tenant-Id";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String ACCEPT_LANGUAGE = "Accept-Language";

    private RequestHeaders() {
    }
}
