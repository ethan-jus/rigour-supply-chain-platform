package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.sales.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.StateConflictException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class StoreVisitCaptureServiceTest {
    final UUID tenant=UUID.randomUUID(), user=UUID.randomUUID(), profile=UUID.randomUUID(), day=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-28T05:00:00Z");
    final SalesWorkContextService context=mock(SalesWorkContextService.class);
    final SalesWorkAttendanceRepository days=mock(SalesWorkAttendanceRepository.class);
    final SalesWorkQueryRepository queries=mock(SalesWorkQueryRepository.class);
    final SalesWorkVisitRepository visits=mock(SalesWorkVisitRepository.class);
    final StoreVisitCaptureStore store=mock(StoreVisitCaptureStore.class);
    final CrmStoreVisitClient crm=mock(CrmStoreVisitClient.class);
    final StoreVisitCaptureService service=new StoreVisitCaptureService(context,days,queries,store,crm,Clock.fixed(now,ZoneOffset.UTC),visits);
    final LocationEvidence location=new LocationEvidence(new BigDecimal("120.1"),new BigDecimal("30.1"),BigDecimal.ONE,"GPS");
    final StoreVisitCaptureService.Command command=new StoreVisitCaptureService.Command("capture_request_12345","100","200",day,location);

    @BeforeEach void setup() throws Exception {
        var setter=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);setter.setAccessible(true);
        setter.invoke(null,new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of("sales:visit:own:write")));
        when(context.resolveIdentity(any(),any())).thenReturn(new SalesWorkContextService.SalesIdentity(
                new SalesWorkQueryRepository.IdentityProjection(user,null,"ACTIVE"),
                new SalesWorkQueryRepository.SalesProfile(profile,null,"E232",null,"ACTIVE",232L,"E232")));
        var workDay=mock(SalesWorkAttendanceRepository.WorkDaySnapshot.class);when(workDay.status()).thenReturn("ACTIVE");
        when(days.findWorkDay(tenant,profile,day)).thenReturn(Optional.of(workDay));
        var visit=mock(Visit.class);when(visit.id()).thenReturn("300");
        when(crm.checkIn(eq("100"),any())).thenReturn(visit);
        when(crm.context("100","300")).thenAnswer(invocation -> {
            var request=ArgumentCaptor.forClass(CheckIn.class);verify(crm,atLeastOnce()).checkIn(eq("100"),request.capture());
            return new Context("300","100","200","E232",request.getValue().captureRef(),UUID.randomUUID().toString(),now,"测试店","地址",location.longitude(),location.latitude(),"GCJ02",BigDecimal.ZERO,1,"IN_PROGRESS");
        });
    }
    @AfterEach void clear() throws Exception {var clear=AuthorizationContext.class.getDeclaredMethod("clear");clear.setAccessible(true);clear.invoke(null);}
    @Test void repeatedCreationUsesSameCaptureAndOriginalCrmTime() {
        var first=service.create(command);var retry=service.create(command);
        assertThat(retry.captureId()).isEqualTo(first.captureId());assertThat(first.visitId()).isEqualTo("300");
        verify(store,times(2)).create(eq(tenant),eq(profile),eq(day),eq(first.captureId()),argThat(c->c.checkedInAt().equals(now)&&c.storeId().equals("100")),eq(location));
    }
    @Test void existingActiveVisitRejectsBeforeCreatingCrmFact() {
        var active=mock(SalesWorkVisitRepository.VisitSnapshot.class);when(active.id()).thenReturn(UUID.randomUUID());
        when(visits.findActiveVisit(tenant,profile)).thenReturn(Optional.of(active));
        assertThatThrownBy(()->service.create(command)).isInstanceOf(StateConflictException.class);
        verifyNoInteractions(crm,store);
    }
    @Test void concurrentCaptureConflictCancelsOnlyUnsubmittedCrmFact() {
        doThrow(new StateConflictException("并发签到")).when(store).create(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->service.create(command)).hasMessageContaining("并发签到");
        verify(crm).cancel(eq("100"),eq("300"),argThat(c->c.revision()==1&&c.reason().contains("冲突")));
    }
    @Test void cancelledCrmCheckinCannotRecreateAnOrphanCapture(){
        var snapshot=mock(Context.class);
        when(snapshot.employeeCode()).thenReturn("E232");when(snapshot.coordinateSystem()).thenReturn("GCJ02");when(snapshot.status()).thenReturn("INVALID");
        when(snapshot.captureRef()).thenReturn(UUID.nameUUIDFromBytes((tenant+":crm-capture:"+command.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        doReturn(snapshot).when(crm).context("100","300");
        assertThatThrownBy(()->service.create(command)).hasMessageContaining("重新发起");
        verify(store,never()).create(any(),any(),any(),any(),any(),any());
    }

}
