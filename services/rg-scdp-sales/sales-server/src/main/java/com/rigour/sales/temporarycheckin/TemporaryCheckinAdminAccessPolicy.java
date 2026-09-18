package com.rigour.sales.temporarycheckin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 将认证过滤器写入的数据库会话主体收敛为后台业务城市范围。 */
@Component
final class TemporaryCheckinAdminAccessPolicy {

    static final String GLOBAL_ADMIN = "sales-checkin-admin";

    TemporaryCheckinAdminPrincipal currentPrincipal(HttpServletRequest request) {
        Object value = request == null ? null
                : request.getAttribute(TemporaryCheckinAdminPrincipal.REQUEST_ATTRIBUTE);
        if (!(value instanceof TemporaryCheckinAdminPrincipal principal)) {
            throw TemporaryCheckinException.adminUnauthorized("请先登录后台");
        }
        return principal;
    }

    AdminScope requireScope(HttpServletRequest request) {
        return requireScope(currentPrincipal(request));
    }

    AdminScope requireScope(TemporaryCheckinAdminPrincipal principal) {
        if (principal == null || principal.username() == null || principal.username().isBlank()) {
            throw TemporaryCheckinException.adminUnauthorized("请先登录后台");
        }
        if (principal.mustChangePassword()) {
            throw TemporaryCheckinException.passwordChangeRequired("请先修改临时密码");
        }
        if (principal.allCities()) {
            return new AdminScope(principal.accountId(), principal.username(), null);
        }
        if (!"CITY_ADMIN".equals(principal.role()) || principal.cityId() == null
                || principal.city() == null || principal.city().isBlank()) {
            throw TemporaryCheckinException.adminForbidden("后台账号城市范围无效");
        }
        return new AdminScope(principal.accountId(), principal.username(), principal.city());
    }

    record AdminScope(UUID accountId, String username, String city) {
        boolean allCities() {
            return city == null;
        }
    }
}
