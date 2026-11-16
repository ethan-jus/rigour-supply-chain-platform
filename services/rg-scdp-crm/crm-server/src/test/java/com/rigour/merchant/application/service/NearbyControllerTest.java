package com.rigour.merchant.application.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.rigour.merchant.api.*;
import com.rigour.merchant.api.v1.model.NearbyModels;
import com.rigour.merchant.api.v1.model.StoreModels;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class NearbyControllerTest {
 @Test void poolBindsBusinessFiltersAndLocationAsIndependentArguments()throws Exception {
  var service=mock(StorePoolService.class);var mvc=MockMvcBuilders.standaloneSetup(new StorePoolController(service)).build();
  mvc.perform(get("/api/v1/crm/stores/nearby").param("view","PUBLIC").param("city","510100").param("size","20").param("nearLongitude","104.07").param("nearLatitude","30.67").param("nearCoordinateSystem","WGS84").param("nearCursor","opaque")).andExpect(status().isOk());
  var filters=ArgumentCaptor.forClass(StoreModels.Query.class);var near=ArgumentCaptor.forClass(NearbyModels.Query.class);verify(service).nearby(filters.capture(),near.capture());
  assertThat(filters.getValue().city()).isEqualTo("510100");assertThat(near.getValue().nearLongitude()).isEqualTo(104.07);assertThat(near.getValue().nearCursor()).isEqualTo("opaque");
 }
 @Test void originAndCooperatedRoutesBindLocationAndPreserveRelation()throws Exception {
  var pools=mock(StorePoolService.class);var mvc=MockMvcBuilders.standaloneSetup(new StorePoolController(pools)).build();
  mvc.perform(get("/api/v1/crm/stores/nearby/origin").param("view","MINE").param("nearLongitude","120").param("nearLatitude","30").param("nearCoordinateSystem","WGS84")).andExpect(status().isOk());
  verify(pools).nearbyOrigin(eq("MINE"),eq(new NearbyModels.Query(120d,30d,"WGS84",null)));
  var service=mock(CustomerWorkbenchService.class);mvc=MockMvcBuilders.standaloneSetup(new CustomerWorkbenchController(service)).build();
  mvc.perform(get("/api/v1/crm/customer-workbench/nearby").param("kind","COOPERATED").param("from","2026-11-01").param("to","2026-11-13").param("relation","TRANSFERRED").param("nearLongitude","120").param("nearLatitude","30").param("nearCoordinateSystem","WGS84")).andExpect(status().isOk());
  verify(service).nearby(eq("COOPERATED"),any(),any(),isNull(),eq("TRANSFERRED"),eq(20),eq(new NearbyModels.Query(120d,30d,"WGS84",null)));
 }
}
