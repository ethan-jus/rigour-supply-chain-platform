package com.rigour.sales.application.service;
import static org.assertj.core.api.Assertions.assertThat;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository.LocationPointRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class SalesTrackMetricsTest {
    static final Instant BASE=Instant.parse("2026-11-12T01:00:00Z");
    static final UUID SESSION=UUID.randomUUID();
    LocationPointRow point(int seconds, double latitude, UUID session, String quality) {
        return new LocationPointRow(BigDecimal.valueOf(116),BigDecimal.valueOf(latitude),BigDecimal.valueOf(10),BASE.plusSeconds(seconds),BASE.plusSeconds(3600),"APP_NATIVE",quality,session);
    }
    @Test void missingIsNotZeroButStationaryIsMeasuredZero() {
        assertThat(SalesTrackMetrics.calculate(List.of(),1).available()).isFalse();
        var result=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(60,40.00003,SESSION,"ACCEPTED")),1);
        assertThat(result.available()).isTrue();assertThat(result.distanceMeters()).isZero();assertThat(result.trackedMinutes()).isEqualTo(1);
    }
    @Test void offlineArrivalOrderDoesNotChangeDistanceOrUseServerDuration() {
        var result=SalesTrackMetrics.calculate(List.of(point(60,40.001,SESSION,"ACCEPTED"),point(0,40,SESSION,"ACCEPTED")),1);
        assertThat(result.distanceMeters()).isBetween(110,112);assertThat(result.trackedMinutes()).isEqualTo(1);
    }
    @Test void gapsSessionsAndRejectedPointsCannotBeBridged() {
        for(var rows:List.of(
            List.of(point(0,40,SESSION,"ACCEPTED"),point(600,40.01,SESSION,"ACCEPTED")),
            List.of(point(0,40,SESSION,"ACCEPTED"),point(60,40.01,UUID.randomUUID(),"ACCEPTED")),
            List.of(point(0,40,SESSION,"ACCEPTED"),point(30,40.001,SESSION,"REJECTED"),point(60,40.002,SESSION,"ACCEPTED")))) {
            var result=SalesTrackMetrics.calculate(rows,1);assertThat(result.available()).isFalse();assertThat(result.distanceMeters()).isZero();assertThat(result.legs()).isEmpty();
        }
    }
    @Test void impossibleJumpCreatesGapAndWalkingAccumulatesBeyondAccuracy() {
        var jump=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(1,41,SESSION,"ACCEPTED")),1);
        assertThat(jump.gaps().getFirst().reason()).isEqualTo("IMPLAUSIBLE_SPEED");assertThat(jump.available()).isFalse();
        var walk=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(10,40.00005,SESSION,"ACCEPTED"),point(20,40.0001,SESSION,"ACCEPTED")),1);
        assertThat(walk.distanceMeters()).isBetween(10,12);
    }
}
