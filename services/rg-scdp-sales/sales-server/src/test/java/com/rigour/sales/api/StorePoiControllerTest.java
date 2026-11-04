package com.rigour.sales.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.shared.context.*;
import java.util.*;
import org.junit.jupiter.api.*;

class StorePoiControllerTest {
    final AmapPoiClient client=mock(AmapPoiClient.class);
    final StorePoiController controller=new StorePoiController(client);
    void actor(String scope,Set<String> permissions)throws Exception {
        UUID id=UUID.randomUUID();
        var setter=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);setter.setAccessible(true);
        setter.invoke(null,new CallerIdentity(scope,id,id,"SERVICE".equals(scope)?null:id,null,UUID.randomUUID(),1,1,"SERVICE".equals(scope)?0:1,Set.of(),permissions));
    }
    @AfterEach void clear()throws Exception {
        var clear=AuthorizationContext.class.getDeclaredMethod("clear");clear.setAccessible(true);clear.invoke(null);
    }
    @Test void tenantCannotAccessInternalPoiEndpointEvenWithServicePermission()throws Exception {
        actor("TENANT",Set.of("sales:store-poi:read"));
        assertThatThrownBy(()->controller.search("台球","330105")).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(client);
    }
    @Test void serviceNeedsExplicitPermission()throws Exception {
        actor("SERVICE",Set.of());
        assertThatThrownBy(()->controller.detail("B001")).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(client);
    }
    @Test void permittedServiceUsesDistrictRestrictedSearch()throws Exception {
        actor("SERVICE",Set.of("sales:store-poi:read"));
        when(client.searchText("台球","330105",1,20)).thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(),1,20,0));
        assertThat(controller.search("台球","330105").data()).isEmpty();
        verify(client).searchText("台球","330105",1,20);
    }
}
