package com.rigour.integration.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.application.port.out.AmapPoiClient;
import com.rigour.integration.application.port.out.ReverseGeocoder;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.RequestValidationException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class AmapControllerTest {
    final AmapPoiClient pois = mock(AmapPoiClient.class);
    final ReverseGeocoder geocoder = mock(ReverseGeocoder.class);
    final AmapController controller = new AmapController(pois, geocoder);

    void actor(String scope, Set<String> permissions) throws Exception {
        UUID id = UUID.randomUUID();
        var setter = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        setter.setAccessible(true);
        setter.invoke(null, new CallerIdentity(scope, id, id, "SERVICE".equals(scope) ? null : id,
                null, UUID.randomUUID(), 1, 1, "SERVICE".equals(scope) ? 0 : 1, Set.of(), permissions));
    }

    @AfterEach void clear() throws Exception {
        var clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    @Test void ordinaryUserCannotBypassCustomerScopeThroughProviderEndpoint() throws Exception {
        actor("TENANT", Set.of("integration:amap:read"));
        assertThatThrownBy(() -> controller.around("台球", BigDecimal.ONE, BigDecimal.ONE, 1000, 1, 20))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(pois, geocoder);
    }

    @Test void serviceWithoutProviderPermissionCannotReverseGeocode() throws Exception {
        actor("SERVICE", Set.of());
        assertThatThrownBy(() -> controller.reverse(BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(pois, geocoder);
    }

    @Test void signedServiceStillNeedsValidSearchBounds() throws Exception {
        actor("SERVICE", Set.of("integration:amap:read"));
        assertThatThrownBy(() -> controller.around("台球", new BigDecimal("181"), BigDecimal.ONE, 1000, 1, 20))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> controller.search("台球", "330105", 1, 1000))
                .isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(pois, geocoder);
    }
}
