package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.rigour.merchant.api.StorePoolController;
import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.port.out.*;
import com.rigour.settings.api.v1.model.*;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StoreMapServiceTest {
    StoreReferenceClient references=mock(StoreReferenceClient.class);
    StorePoolStore store=mock(StorePoolStore.class);
    StorePoolService service=new StorePoolService(references,mock(CrmEmployeeClient.class),store);
    Query query(String type){return new Query("PUBLIC",null,null,null,null,null,null,null,null,null,null,20,type);}
    @BeforeEach void scope(){
        when(references.access("crm:pool:public-read")).thenReturn(new StoreAccessView("T","U","crm:pool:public-read",1,"1","E1","销售","10",Set.of("crm:pool:public-read"),List.of(new StoreAccessView.Grant("ALL",List.of(),false,true,List.of())),false,List.of()));
        when(references.divisions()).thenReturn(new AdminDivisionCatalog("TEST","fixture","fixture",List.of()));
    }
    DictItemView type(String code,String parent,boolean enabled){return new DictItemView(1L,"CRM_STORE_TYPE",parent==null?1:2,parent,code,code,null,1,1,enabled);}
    @Test void parentFilterIncludesInactiveDescendantsAndAliasesInBothListAndMap(){
        when(references.storeTypes("T")).thenReturn(List.of(type("SPORTS",null,true),type("VENUE","SPORTS",false),type("BILLIARDS","VENUE",true),type("UNRELATED",null,true),
            new DictItemView(2L,"CRM_STORE_TYPE",1,null,"OLD_BILLIARDS","旧分类",null,1,1,false,"CRM_STORE_TYPE","BILLIARDS")));
        var q=query("SPORTS");service.list(q);service.map(q,new MapWindow(null,null,null,null,4d));
        var codes=Set.of("SPORTS","VENUE","BILLIARDS","OLD_BILLIARDS");
        verify(store).list(any(),eq(q),eq(codes));verify(store).map(any(),eq(q),eq(codes),any());
    }
    @Test void unclassifiedDoesNotRequireDictionaryAvailability(){
        service.map(query("__UNCLASSIFIED__"),new MapWindow(null,null,null,null,null));
        verify(references,never()).storeTypes(any());verify(store).map(any(),any(),eq(Set.of()),any());
    }
    @Test void invalidBoundsAndZoomAreRejectedBeforeQuerying(){
        for(var window:List.of(new MapWindow(120d,null,121d,31d,10d),new MapWindow(121d,30d,120d,31d,10d),
                new MapWindow(-181d,30d,120d,31d,10d),new MapWindow(120d,30d,121d,91d,10d),
                new MapWindow(120d,Double.NaN,121d,31d,10d),new MapWindow(null,null,null,null,21d)))
            assertThatThrownBy(()->service.map(query(null),window)).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(store,references);
    }
    @Test void clusterMemberLookupRequiresBoundsAndValidCoordinateSystem(){
        assertThatThrownBy(()->service.mapStores(query(null),new MapWindow(null,null,null,null,4d),"GCJ02")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.mapStores(query(null),new MapWindow(120d,30d,121d,31d,4d),"BD09")).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(store);
    }
    @Test void mapEndpointBindsIndependentRecordArgumentsAndDoesNotUseListRoute()throws Exception {
        var api=mock(StorePoolService.class);
        when(api.map(any(),any())).thenReturn(new MapResult(170099,170099,170099,null,List.of(),"PROVINCE"));
        var mvc=MockMvcBuilders.standaloneSetup(new StorePoolController(api)).build();
        mvc.perform(get("/api/v1/crm/stores/map").param("view","PUBLIC").param("size","20").param("storeType","SPORTS")
                .param("west","120").param("east","121").param("south","30").param("north","31").param("zoom","12")).andExpect(status().isOk());
        var query=ArgumentCaptor.forClass(Query.class);var window=ArgumentCaptor.forClass(MapWindow.class);
        verify(api).map(query.capture(),window.capture());
        assertThat(query.getValue().storeType()).isEqualTo("SPORTS");assertThat(window.getValue()).isEqualTo(new MapWindow(120d,30d,121d,31d,12d));
        verify(api,never()).list(any());
    }
}
