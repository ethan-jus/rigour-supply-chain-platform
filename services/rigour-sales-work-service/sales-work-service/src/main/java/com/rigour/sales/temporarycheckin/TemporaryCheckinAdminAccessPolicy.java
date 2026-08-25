package com.rigour.sales.temporarycheckin;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将 Nginx Basic Auth 已验证的用户名收敛为后台城市范围。
 * 请求头必须由 Nginx 覆盖为 {@code $remote_user}，不能透传浏览器的同名请求头。
 */
@Component
final class TemporaryCheckinAdminAccessPolicy {

    static final String HEADER = "X-Sales-Checkin-Admin-User";
    static final String GLOBAL_ADMIN = "sales-checkin-admin";

    private static final Map<String, String> CITY_BY_USERNAME = Map.ofEntries(
            Map.entry("city-beijing", "北京"),
            Map.entry("city-shenzhen", "深圳"),
            Map.entry("city-hangzhou", "杭州"),
            Map.entry("city-chengdu", "成都"),
            Map.entry("city-wuhan", "武汉"),
            Map.entry("city-xian", "西安"),
            Map.entry("city-changsha", "长沙"),
            Map.entry("city-nanjing", "南京"),
            Map.entry("city-shijiazhuang", "石家庄"),
            Map.entry("city-chongqing", "重庆"),
            Map.entry("city-suzhou", "苏州"),
            Map.entry("city-jinhua", "金华"),
            Map.entry("city-dongguan", "东莞"),
            Map.entry("city-shanghai", "上海"),
            Map.entry("city-luoyang", "洛阳"),
            Map.entry("city-guangzhou", "广州"),
            Map.entry("city-zongbu", "总部"));

    AdminScope requireScope(String rawUsername) {
        if (rawUsername == null || rawUsername.isBlank()) {
            throw TemporaryCheckinException.adminForbidden("缺少后台身份头");
        }
        String username = rawUsername.trim();
        if (!username.equals(rawUsername) || username.indexOf(',') >= 0
                || username.chars().anyMatch(Character::isISOControl)) {
            throw TemporaryCheckinException.adminForbidden("后台身份头无效");
        }
        if (GLOBAL_ADMIN.equals(username)) {
            return new AdminScope(username, null);
        }
        String city = CITY_BY_USERNAME.get(username);
        if (city == null) {
            throw TemporaryCheckinException.adminForbidden("后台账号未配置城市范围");
        }
        return new AdminScope(username, city);
    }

    record AdminScope(String username, String city) {
        boolean allCities() {
            return city == null;
        }
    }
}
