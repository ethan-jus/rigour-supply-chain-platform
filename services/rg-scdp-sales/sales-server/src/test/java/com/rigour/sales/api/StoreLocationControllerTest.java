package com.rigour.sales.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.RequestValidationException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class StoreLocationControllerTest {
    final AmapPoiClient maps=mock(AmapPoiClient.class);
    final StoreLocationController controller=new StoreLocationController(maps);
    final BigDecimal lng=new BigDecimal("120.1"), lat=new BigDecimal("30.2");
    void actor(Set<String> permissions) throws Exception {
        UUID user=UUID.randomUUID();
        var setter=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);setter.setAccessible(true);
        setter.invoke(null,new CallerIdentity("TENANT",user,UUID.randomUUID(),user,null,UUID.randomUUID(),1,1,1,Set.of(),permissions));
    }
    @AfterEach void clear() throws Exception {var clear=AuthorizationContext.class.getDeclaredMethod("clear");clear.setAccessible(true);clear.invoke(null);}
    @Test void unauthorizedRequestDoesNotSpendProviderQuota() throws Exception {
        actor(Set.of());
        assertThatThrownBy(()->controller.normalize(new StoreLocationController.Location(lng,lat,"WGS84"))).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(maps);
    }
    @Test void gpsUsesIntegrationAndGcjDoesNotConvertTwice() throws Exception {
        actor(Set.of("sales:visit:own:write"));
        var converted=new AmapPoiClient.Coordinates(new BigDecimal("120.104"),new BigDecimal("30.202"),"GCJ02");
        when(maps.convertGps(lng,lat)).thenReturn(converted);
        assertThat(controller.normalize(new StoreLocationController.Location(lng,lat,"WGS84")).data()).isEqualTo(converted);
        assertThat(controller.normalize(new StoreLocationController.Location(lng,lat,"GCJ02")).data().longitude()).isEqualTo(lng);
        verify(maps,times(1)).convertGps(lng,lat);
    }
    @Test void unknownSystemsAndOutOfBoundsCannotBecomeEvidence() throws Exception {
        actor(Set.of("sales:visit:own:write"));
        for(var input:List.of(new StoreLocationController.Location(lng,lat,"BD09"),new StoreLocationController.Location(new BigDecimal("181"),lat,"WGS84"),new StoreLocationController.Location(lng,null,"GCJ02")))
            assertThatThrownBy(()->controller.normalize(input)).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(maps);
    }
}
