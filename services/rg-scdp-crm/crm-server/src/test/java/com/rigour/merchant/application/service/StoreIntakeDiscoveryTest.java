package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.api.v1.model.NearbyModels;
import com.rigour.merchant.application.port.out.*;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import com.rigour.shared.core.exception.RequestValidationException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class StoreIntakeDiscoveryTest {
    final StoreReferenceClient references=mock(StoreReferenceClient.class);
    final StorePoolStore store=mock(StorePoolStore.class);
    final StorePoiClient pois=mock(StorePoiClient.class);
    final StorePoolService service=new StorePoolService(references,mock(CrmEmployeeClient.class),store,pois);
    @BeforeEach void scope() {
        when(references.access("crm:lead:create")).thenReturn(new StoreAccessView("t","u","crm:lead:create",1,"e","E","销售","d",Set.of("crm:lead:create"),List.of(new StoreAccessView.Grant("SELF",List.of(),false,false,List.of(new StoreAccessView.Area("v","110101")))),false,List.of()));
        when(references.divisions()).thenReturn(new AdminDivisionCatalog("v","test","test",List.of(new AdminDivisionCatalog.Node("110101","东城区","COUNTY",null,"NORTH"),new AdminDivisionCatalog.Node("110102","西城区","COUNTY",null,"NORTH"))));
        when(store.intakeCandidates(any(),any())).thenAnswer(i->i.<List<Poi>>getArgument(1).stream().map(p->new IntakeCandidate(p,"NEW",null,null)).toList());
    }
    Poi poi(String area) {return new Poi("B01","台球馆","地址",null,"080113",area,new BigDecimal("116.4"),new BigDecimal("39.9"));}
    @Test void convertsDeviceGpsAndFiltersUnauthorizedAreaBeforeMatching() {
        when(pois.convertGps("t",116.0,39.0)).thenReturn(new NearbyModels.Coordinates(116.4,39.9,"GCJ02"));
        when(pois.around("t","",116.4,39.9,3000)).thenReturn(List.of(poi("110101"),poi("110102")));
        var result=service.discover(new IntakeQuery("",null,116.0,39.0,"WGS84",3000));
        assertThat(result.items()).singleElement().satisfies(r->assertThat(r.distanceMeters()).isZero());
        verify(store,times(1)).intakeCandidates(any(),any());
    }
    @Test void mapCoordinatesAreAlreadyGcjAndMustNotBeConvertedAgain() {
        when(pois.around("t","台球",116.4,39.9,3000)).thenReturn(List.of());
        service.discover(new IntakeQuery("台球",null,116.4,39.9,"GCJ02",3000));
        verify(pois,never()).convertGps(anyString(),anyDouble(),anyDouble());
    }
    @Test void supportsTextSearchWithoutLocationButRequiresAuthorizedArea() {
        when(pois.search("t","台球","110101")).thenReturn(List.of(poi("110101"),poi("110102")));
        assertThat(service.discover(new IntakeQuery("台球","110101",null,null,null,null)).items()).hasSize(1);
        assertThatThrownBy(()->service.discover(new IntakeQuery("台球","110102",null,null,null,null))).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
    }
    @Test void rejectsPartialOrInvalidLocationRatherThanFallingBackToText() {
        for(var q:List.of(new IntakeQuery("台球","110101",116.4,null,"WGS84",3000),new IntakeQuery("",null,Double.NaN,39.0,"GCJ02",3000),new IntakeQuery("",null,116.4,39.9,"GCJ02",99999)))
            assertThatThrownBy(()->service.discover(q)).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(pois,store);
    }
}
