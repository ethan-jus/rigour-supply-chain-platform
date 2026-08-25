package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemporaryCheckinAdminAccessPolicyTest {

    private final TemporaryCheckinAdminAccessPolicy policy = new TemporaryCheckinAdminAccessPolicy();

    @Test
    void mapsGlobalAndAllConfiguredCityAccounts() {
        var global = policy.requireScope("sales-checkin-admin");
        assertThat(global.allCities()).isTrue();
        assertThat(global.city()).isNull();

        Map<String, String> cities = Map.ofEntries(
                Map.entry("city-beijing", "北京"), Map.entry("city-shenzhen", "深圳"),
                Map.entry("city-hangzhou", "杭州"), Map.entry("city-chengdu", "成都"),
                Map.entry("city-wuhan", "武汉"), Map.entry("city-xian", "西安"),
                Map.entry("city-changsha", "长沙"), Map.entry("city-nanjing", "南京"),
                Map.entry("city-shijiazhuang", "石家庄"), Map.entry("city-chongqing", "重庆"),
                Map.entry("city-suzhou", "苏州"), Map.entry("city-jinhua", "金华"),
                Map.entry("city-dongguan", "东莞"), Map.entry("city-shanghai", "上海"),
                Map.entry("city-luoyang", "洛阳"), Map.entry("city-guangzhou", "广州"),
                Map.entry("city-zongbu", "总部"));
        assertThat(cities).allSatisfy((username, city) -> {
            var scope = policy.requireScope(username);
            assertThat(scope.allCities()).isFalse();
            assertThat(scope.city()).isEqualTo(city);
        });
    }

    @Test
    void rejectsMissingUnknownOrAmbiguousHeaders() {
        assertForbidden(null);
        assertForbidden(" ");
        assertForbidden("unknown");
        assertForbidden("city-beijing,city-shenzhen");
        assertForbidden(" city-beijing");
    }

    private void assertForbidden(String username) {
        assertThatThrownBy(() -> policy.requireScope(username))
                .isInstanceOfSatisfying(TemporaryCheckinException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("TEMP_CHECKIN_ADMIN_FORBIDDEN");
                });
    }
}
