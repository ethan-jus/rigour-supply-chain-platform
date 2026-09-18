package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;

class TemporaryCheckinAdminAccessPolicyTest {

    private final TemporaryCheckinAdminAccessPolicy policy = new TemporaryCheckinAdminAccessPolicy();

    @Test
    void mapsDatabasePrincipalsToGlobalAndCityScopes() {
        var global = policy.requireScope(principal("sales-checkin-admin", "GLOBAL_ADMIN", null));
        assertThat(global.allCities()).isTrue();
        assertThat(global.city()).isNull();

        var city = policy.requireScope(principal("city-beijing", "CITY_ADMIN", "北京"));
        assertThat(city.allCities()).isFalse();
        assertThat(city.city()).isEqualTo("北京");
    }

    @Test
    void readsOnlyServerRequestAttributeAndRejectsMissingOrForcedChange() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThatThrownBy(() -> policy.currentPrincipal(request))
                .isInstanceOf(TemporaryCheckinException.class);

        TemporaryCheckinAdminPrincipal principal = principal("city-beijing", "CITY_ADMIN", "北京");
        request.setAttribute(TemporaryCheckinAdminPrincipal.REQUEST_ATTRIBUTE, principal);
        assertThat(policy.currentPrincipal(request)).isSameAs(principal);

        TemporaryCheckinAdminPrincipal forced = new TemporaryCheckinAdminPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "city-beijing", "北京管理员", "CITY_ADMIN",
                UUID.randomUUID(), "北京", true, "csrf");
        assertThatThrownBy(() -> policy.requireScope(forced))
                .isInstanceOfSatisfying(TemporaryCheckinException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TEMP_CHECKIN_PASSWORD_CHANGE_REQUIRED"));
    }

    private static TemporaryCheckinAdminPrincipal principal(String username, String role, String city) {
        return new TemporaryCheckinAdminPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), username, username, role,
                city == null ? null : UUID.randomUUID(), city, false, "csrf");
    }
}
