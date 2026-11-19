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
        assertThat(SalesTrackMetrics.calculate(List.of(),60).available()).isFalse();
        var result=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(60,40.00003,SESSION,"ACCEPTED")),60);
        assertThat(result.available()).isTrue();assertThat(result.distanceMeters()).isZero();assertThat(result.trackedMinutes()).isEqualTo(1);
    }
    @Test void offlineArrivalOrderDoesNotChangeDistanceOrUseServerDuration() {
        var result=SalesTrackMetrics.calculate(List.of(point(60,40.001,SESSION,"ACCEPTED"),point(0,40,SESSION,"ACCEPTED")),60);
        assertThat(result.distanceMeters()).isBetween(110,112);assertThat(result.trackedMinutes()).isEqualTo(1);
    }
    @Test void gapsSessionsAndRejectedPointsCannotBeBridged() {
        for(var rows:List.of(
            List.of(point(0,40,SESSION,"ACCEPTED"),point(600,40.01,SESSION,"ACCEPTED")),
            List.of(point(0,40,SESSION,"ACCEPTED"),point(60,40.01,UUID.randomUUID(),"ACCEPTED")),
            List.of(point(0,40,SESSION,"ACCEPTED"),point(30,40.001,SESSION,"REJECTED"),point(60,40.002,SESSION,"ACCEPTED")))) {
            var result=SalesTrackMetrics.calculate(rows,60);assertThat(result.available()).isFalse();assertThat(result.distanceMeters()).isZero();assertThat(result.legs()).isEmpty();
        }
    }
    @Test void impossibleJumpCreatesGapAndWalkingAccumulatesBeyondAccuracy() {
        var jump=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(1,41,SESSION,"ACCEPTED")),60);
        assertThat(jump.gaps().getFirst().reason()).isEqualTo("IMPLAUSIBLE_SPEED");assertThat(jump.available()).isFalse();
        var walk=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(10,40.00005,SESSION,"ACCEPTED"),point(20,40.0002,SESSION,"ACCEPTED")),60);
        assertThat(walk.distanceMeters()).isBetween(21,23);
    }
    @Test void overlappingUncertaintyDoesNotDrawOrAccumulateIndoorDrift() {
        var result=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(60,40.00012,SESSION,"ACCEPTED"),point(120,39.99988,SESSION,"ACCEPTED"),point(180,40,SESSION,"ACCEPTED")),60);
        assertThat(result.available()).isTrue(); assertThat(result.distanceMeters()).isZero();
        assertThat(result.legs()).isEmpty(); assertThat(result.trackedMinutes()).isEqualTo(3);
    }
    @Test void drawnRouteUsesTheSameMovementAnchorsAsMileage() {
        var result=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(60,40.00012,SESSION,"ACCEPTED"),point(120,40.0003,SESSION,"ACCEPTED")),60);
        assertThat(result.legs()).hasSize(1); assertThat(result.legs().getFirst().points()).hasSize(2);
        assertThat(result.distanceMeters()).isBetween(33,34);
    }
    @Test void qualityClassificationFollowsThePublishedPolicyNotAnotherHardcodedLimit() {
        var a=new LocationPointRow(BigDecimal.valueOf(116),BigDecimal.valueOf(40),BigDecimal.valueOf(120),BASE,BASE,"APP_NATIVE","ACCEPTED",SESSION);
        var b=new LocationPointRow(BigDecimal.valueOf(116),BigDecimal.valueOf(40.003),BigDecimal.valueOf(120),BASE.plusSeconds(60),BASE,"APP_NATIVE","ACCEPTED",SESSION);
        assertThat(SalesTrackMetrics.calculate(List.of(a,b),60).distanceMeters()).isBetween(333,334);
    }
    @Test void coarseIsolatedExcursionIsNotDrawnOrCounted() {
        var coarse=new LocationPointRow(BigDecimal.valueOf(116),BigDecimal.valueOf(40.001),BigDecimal.valueOf(40),BASE.plusSeconds(60),BASE,"APP_NATIVE","ACCEPTED",SESSION);
        var before=point(0,40,SESSION,"ACCEPTED");
        assertThat(SalesTrackMetrics.calculate(List.of(before,coarse),60).distanceMeters()).isZero();
        var result=SalesTrackMetrics.calculate(List.of(before,coarse,point(120,40,SESSION,"ACCEPTED")),60);
        assertThat(result.distanceMeters()).isZero(); assertThat(result.legs()).isEmpty(); assertThat(result.rejected()).isEqualTo(1);
    }
    @Test void preciseReturnTripRemainsRealMileage() {
        var result=SalesTrackMetrics.calculate(List.of(point(0,40,SESSION,"ACCEPTED"),point(60,40.001,SESSION,"ACCEPTED"),point(120,40,SESSION,"ACCEPTED")),60);
        assertThat(result.distanceMeters()).isBetween(221,223); assertThat(result.legs().getFirst().points()).hasSize(3);
    }
    @Test void isolatedPointOverlappingNeighboursCannotEscapeAnOlderAnchor() {
        var anchor=point(0,40,SESSION,"ACCEPTED");
        var before=point(60,40.0001,SESSION,"ACCEPTED");
        var excursion=point(120,40.00025,SESSION,"ACCEPTED");
        var after=point(180,40.0001,SESSION,"ACCEPTED");
        var result=SalesTrackMetrics.calculate(List.of(anchor,before,excursion,after),60);
        assertThat(result.distanceMeters()).isZero(); assertThat(result.legs()).isEmpty();
        assertThat(result.rejected()).isEqualTo(1); assertThat(result.trackedMinutes()).isEqualTo(3);
        var walk=SalesTrackMetrics.calculate(List.of(anchor,before,excursion,point(180,40.0004,SESSION,"ACCEPTED")),60);
        assertThat(walk.distanceMeters()).isBetween(27,29); assertThat(walk.legs()).hasSize(1);
    }
}
