package com.rigour.sales.temporarycheckin;

import java.util.UUID;

/** 已由数据库会话认证的后台主体；只能由认证过滤器写入请求属性。 */
record TemporaryCheckinAdminPrincipal(
        UUID accountId,
        UUID sessionId,
        String username,
        String displayName,
        String role,
        UUID cityId,
        String city,
        boolean mustChangePassword,
        String csrfToken) {

    static final String REQUEST_ATTRIBUTE = TemporaryCheckinAdminPrincipal.class.getName();

    boolean allCities() {
        return "GLOBAL_ADMIN".equals(role);
    }
}
