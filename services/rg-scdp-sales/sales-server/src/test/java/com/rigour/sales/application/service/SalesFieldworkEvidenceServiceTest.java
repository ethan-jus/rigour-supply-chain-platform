package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.*;
import com.rigour.sales.application.port.out.*;
import com.rigour.shared.context.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SalesFieldworkEvidenceServiceTest {
    final UUID tenant=UUID.randomUUID(),profile=UUID.randomUUID(),day=UUID.randomUUID();
    final SalesFieldworkEvidenceRepository repository=mock(SalesFieldworkEvidenceRepository.class);
    final SalesWorkAttendanceRepository days=mock(SalesWorkAttendanceRepository.class);
    final SalesFieldworkService access=mock(SalesFieldworkService.class);
    final SalesFieldworkEvidenceService service=new SalesFieldworkEvidenceService(repository,days,access);
    @BeforeEach void caller() throws Exception {
        UUID user=UUID.randomUUID();var set=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);set.setAccessible(true);
        set.invoke(null,new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of("sales:risk:read")));
        when(days.findWorkDay(tenant,profile,day)).thenReturn(Optional.of(mock(SalesWorkAttendanceRepository.WorkDaySnapshot.class)));
    }
    @AfterEach void clear() throws Exception {var clear=AuthorizationContext.class.getDeclaredMethod("clear");clear.setAccessible(true);clear.invoke(null);}
    DeviceEvent event(String reference){return new DeviceEvent(UUID.randomUUID(),"CHECK_IN",Instant.now(),Instant.now(),reference,Instant.now(),"VALID");}
    @Test void installationEqualityNeverBecomesTrustedDeviceProof(){
        when(repository.devices(tenant,profile,day)).thenReturn(List.of(event("a"),event("a")));
        var result=service.devices(profile,day);assertThat(result.installationComparison()).isEqualTo("SAME");
        assertThat(result.proofStatus()).isEqualTo("NOT_OBTAINED");assertThat(result.registrationStatus()).isEqualTo("NOT_CONNECTED");
        when(repository.devices(tenant,profile,day)).thenReturn(List.of(event(null),event("a")));
        assertThat(service.devices(profile,day).installationComparison()).isEqualTo("UNKNOWN");
        when(repository.devices(tenant,profile,day)).thenReturn(List.of(event("b"),event("a")));
        assertThat(service.devices(profile,day).installationComparison()).isEqualTo("DIFFERENT");
    }
    @Test void denialAndWrongOwnerStopEvidenceLookup(){
        doThrow(new AuthorizationDeniedException("sales:risk:read")).when(access).requireProfile(profile,"sales:risk:read");
        assertThatThrownBy(()->service.devices(profile,day)).isInstanceOf(AuthorizationDeniedException.class);verifyNoInteractions(repository);
    }
    @Test void otherDayCannotBeSubstituted(){
        when(days.findWorkDay(tenant,profile,day)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.devices(profile,day)).hasMessageContaining("当日作业不存在");verifyNoInteractions(repository);
    }
    @Test void historyUsesIndependentRiskScope(){
        UUID visit=UUID.randomUUID();var capture=new Capture(visit,profile,day,LocalDate.now(),12L,14L,"门店",Instant.now(),null,"CHECKED_IN",null,null,null);
        var scope=new SalesManagementDirectory.Access(false,Set.of("visible"));when(access.access("sales:risk:read")).thenReturn(scope);
        when(repository.capture(tenant,visit)).thenReturn(Optional.of(capture));service.history(visit,2,10,true);
        verify(access).requireVisit(visit,"sales:risk:read");verify(repository).history(tenant,capture,scope,2,10,true);
    }
}
