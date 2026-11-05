package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.port.out.*;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.RequestValidationException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class StoreMaintenanceServiceTest {
    final StoreReferenceClient refs=mock(StoreReferenceClient.class);
    final StorePoolStore store=mock(StorePoolStore.class);
    final StorePoiClient pois=mock(StorePoiClient.class);
    final StorePoolService service=new StorePoolService(refs,mock(CrmEmployeeClient.class),store,pois);
    final Poi poi=new Poi("B001","测试球馆","湖墅南路1号",null,"080113","330105",new BigDecimal("120.15"),new BigDecimal("30.28"));
    @BeforeEach void scope() {
        when(refs.access("crm:store:manage")).thenReturn(new StoreAccessView("T","U","crm:store:manage",1,"1","E1","管理","10",
                Set.of("crm:store:manage"),List.of(new StoreAccessView.Grant("ALL",List.of(),false,false,List.of(new StoreAccessView.Area("V","330100")))),false,List.of()));
        when(refs.divisions()).thenReturn(new AdminDivisionCatalog("V","test","test",List.of(
                new AdminDivisionCatalog.Node("330000","浙江省","PROVINCE",null,"EAST"),
                new AdminDivisionCatalog.Node("330100","杭州市","PREFECTURE","330000",null),
                new AdminDivisionCatalog.Node("330105","拱墅区","COUNTY","330100",null),
                new AdminDivisionCatalog.Node("620000","甘肃省","PROVINCE",null,"NORTHWEST"),
                new AdminDivisionCatalog.Node("620100","兰州市","PREFECTURE","620000",null),
                new AdminDivisionCatalog.Node("620102","城关区","COUNTY","620100",null))));
    }
    Update update(Location location,String id){return new Update(1,UUID.randomUUID().toString(),"球馆","核实地址",null,null,"实地核实",location,id);}
    Location location(String area){return new Location(area,poi.longitude(),poi.latitude(),"GCJ02");}
    @Test void libraryReadDoesNotGrantMaintenanceOrProviderAccess() {
        assertThat(StorePoolService.action("ALL")).isEqualTo("crm:store:library-read");
        var manager=refs.access("crm:store:manage");
        when(refs.access("crm:store:library-read")).thenReturn(manager);
        service.context("ALL");
        when(refs.access("crm:store:manage")).thenThrow(new AuthorizationDeniedException("crm:store:manage"));
        assertThatThrownBy(()->service.maintenanceContext("123")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->service.poiCandidates("123","球馆","330105")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->service.update("123",update(location("330105"),null))).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(pois,store);
    }
    @Test void maintenanceCatalogUsesWriteScopeAndChecksOriginalStore() {
        assertThat(service.maintenanceContext("123").areas()).extracting(Area::code).contains("330105").doesNotContain("620102");
        verify(store).detail(any(),eq("123"));
        verify(refs).access("crm:store:manage");
    }
    @Test void onlyReturnsCandidatesInSelectedAuthorizedCounty() {
        when(pois.search("T","球馆","330105")).thenReturn(List.of(poi,
                new Poi("B002","越权结果",null,null,null,"620102",BigDecimal.ONE,BigDecimal.ONE),
                new Poi("B003","坏坐标",null,null,null,"330105",null,null)));
        assertThat(service.poiCandidates("123"," 球馆 ","330105")).containsExactly(poi);
        verify(store).detail(any(),eq("123"));
    }
    @Test void rejectsTargetOutsideScopeBeforeCallingProviderOrWriting() {
        assertThatThrownBy(()->service.poiCandidates("123","球馆","620102")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->service.update("123",update(location("620102"),null))).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(pois);verify(store,never()).update(any(),any(),any());
    }
    @Test void verifiesSelectedPoiOnSaveAndRejectsChangedLocation() {
        when(pois.detail("T","B001")).thenReturn(poi);
        var command=update(location("330105"),"B001");
        service.update("123",command);
        verify(store).update(any(),eq("123"),eq(command));
        clearInvocations(store);
        assertThatThrownBy(()->service.update("123",update(new Location("330105",BigDecimal.ONE,BigDecimal.ONE,"GCJ02"),"B001")))
                .isInstanceOf(RequestValidationException.class).hasMessageContaining("重新查询");
        verify(store,never()).update(any(),any(),any());
    }
    @Test void manualMaintenanceDoesNotDependOnAmapAndRejectsHalfCoordinates() {
        service.update("123",update(location("330105"),null));
        verifyNoInteractions(pois);
        assertThatThrownBy(()->service.update("123",update(new Location("330105",BigDecimal.ONE,null,"GCJ02"),null)))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.update("123",update(location("330100"),null))).isInstanceOf(RequestValidationException.class);
    }
}
