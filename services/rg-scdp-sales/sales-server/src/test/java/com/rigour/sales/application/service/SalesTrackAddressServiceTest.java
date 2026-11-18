package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.*;
import com.rigour.sales.application.port.out.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalesTrackAddressServiceTest {
    final SalesWorkAttendanceService attendance = mock(SalesWorkAttendanceService.class);
    final AmapPoiClient maps = mock(AmapPoiClient.class);
    final SalesTrackAddressService service = new SalesTrackAddressService(attendance, maps);
    final LocalDate date = LocalDate.of(2026, 11, 17);
    @Test void deniedTrackNeverCallsProvider() {
        when(attendance.track(date)).thenThrow(new IllegalStateException("denied"));
        assertThatThrownBy(() -> service.ownPunchAddresses(date)).hasMessage("denied");
        verifyNoInteractions(maps);
    }
    @Test void sameLocationIsResolvedOnceAndMissingLocationRemainsExplicit() {
        var lng = new BigDecimal("120.1"); var lat = new BigDecimal("30.2");
        var punches = List.of(new TrackPunchView("CHECK_IN", null, null, lng, lat, null, "VALID",null),
                new TrackPunchView("CHECK_OUT", null, null, lng, lat, null, "VALID",null),
                new TrackPunchView("CHECK_IN", null, null, null, null, null, "MISSING",null));
        when(attendance.track(date)).thenReturn(new WorkDayTrackView(null, date, "FINISHED", 0, 0, List.of(), punches, List.of(), List.of()));
        when(maps.reverseGcj02(lng, lat)).thenReturn(new AmapPoiClient.Address("RESOLVED", "附近", "真实地址"));
        var result = service.ownPunchAddresses(date);
        assertThat(result).extracting(SalesTrackAddressService.PunchAddress::key).containsExactly("punch-0", "punch-1", "punch-2");
        assertThat(result).extracting(SalesTrackAddressService.PunchAddress::address).containsExactly("真实地址", "真实地址", "");
        assertThat(result.get(2).status()).isEqualTo("NO_LOCATION");
        verify(maps, times(1)).reverseGcj02(lng, lat);
    }
    @Test void providerFailureDoesNotInventAnAddress() {
        var lng = BigDecimal.ONE; var lat = BigDecimal.TEN;
        when(attendance.track(date)).thenReturn(new WorkDayTrackView(null, date, "ACTIVE", 0, 0, List.of(), List.of(new TrackPunchView("CHECK_IN", null, null, lng, lat, null, "VALID",null)), List.of(), List.of()));
        when(maps.reverseGcj02(lng, lat)).thenThrow(new AmapPoiException("offline"));
        assertThat(service.ownPunchAddresses(date).get(0).status()).isEqualTo("UNAVAILABLE");
        assertThat(service.ownPunchAddresses(date).get(0).address()).isEmpty();
    }
}
