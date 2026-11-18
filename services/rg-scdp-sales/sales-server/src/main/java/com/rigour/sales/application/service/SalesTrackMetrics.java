package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackPointView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackLegView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackGapView;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository.LocationPointRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Observed route estimates only; never joins sessions, missing samples or implausible jumps. */
final class SalesTrackMetrics {
    record Result(int distanceMeters, int trackedMinutes, boolean available, int rejected,
                  List<TrackLegView> legs, List<TrackGapView> gaps) {}

    static Result calculate(List<LocationPointRow> input, int expectedIntervalMinutes) {
        var rows = input.stream().sorted(Comparator.comparing(SalesTrackMetrics::time)).toList();
        long maximumGap = Math.max(180L, Math.min(300L, expectedIntervalMinutes * 120L));
        List<TrackLegView> legs = new ArrayList<>();
        List<TrackGapView> gaps = new ArrayList<>();
        List<TrackPointView> path = new ArrayList<>();
        LocationPointRow previous = null, anchor = null;
        double total = 0, legDistance = 0;
        long seconds = 0;
        int rejected = 0, pairs = 0;
        boolean broken = false;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (!valid(row)) { rejected++; broken = true; continue; }
            if (previous != null && coarseSpike(previous, row, index + 1 < rows.size() ? rows.get(index + 1) : null, maximumGap)) {
                rejected++; continue;
            }
            if (!broken && anchor != null && index + 1 < rows.size()
                    && unconfirmedExcursion(anchor, previous, row, rows.get(index + 1), maximumGap)) {
                rejected++; continue;
            }
            if (previous != null) {
                long elapsed = Duration.between(time(previous), time(row)).getSeconds();
                double hop = distance(previous, row);
                String reason = !Objects.equals(previous.sessionId(), row.sessionId()) ? "SESSION_BOUNDARY"
                        : broken ? "REJECTED_SAMPLES" : elapsed <= 0 ? "INVALID_TIME"
                        : elapsed > maximumGap ? "MISSING_SAMPLES" : hop / elapsed > 55 ? "IMPLAUSIBLE_SPEED" : null;
                if (reason != null) {
                    if (path.size() > 1) legs.add(new TrackLegView((int)Math.round(legDistance), path));
                    if (!"SESSION_BOUNDARY".equals(reason)) gaps.add(new TrackGapView(time(previous), time(row), reason));
                    path = new ArrayList<>(); legDistance = 0; anchor = null;
                } else {
                    pairs++; seconds += elapsed;
                    // Overlapping accuracy circles do not establish movement. Retain the anchor
                    // so slow walking can accumulate; draw exactly the path used for distance.
                    double movement = distance(anchor, row);
                    double uncertainty = anchor.accuracyMeters().doubleValue() + row.accuracyMeters().doubleValue();
                    if (movement > uncertainty) { total += movement; legDistance += movement; anchor = row; path.add(view(row)); }
                }
            }
            if (anchor == null) { anchor = row; path.add(view(row)); }
            previous = row; broken = false;
        }
        if (path.size() > 1) legs.add(new TrackLegView((int)Math.round(legDistance), path));
        return new Result((int)Math.round(total), (int)(seconds / 60), pairs > 0, rejected, List.copyOf(legs), List.copyOf(gaps));
    }
    // A solitary less precise excursion that returns to the previous uncertainty circle is not
    // evidence of a trip. Wait for a following fix before displaying a coarse trailing jump.
    private static boolean coarseSpike(LocationPointRow before, LocationPointRow point, LocationPointRow after, long maximumGap) {
        long elapsed = Duration.between(time(before), time(point)).getSeconds();
        if (!Objects.equals(before.sessionId(), point.sessionId()) || elapsed <= 0 || elapsed > maximumGap
                || point.accuracyMeters().doubleValue() < before.accuracyMeters().doubleValue() * 2
                || distance(before, point) <= before.accuracyMeters().doubleValue() + point.accuracyMeters().doubleValue()) return false;
        if (after == null) return true;
        if (!valid(after) || !Objects.equals(point.sessionId(), after.sessionId())
                || point.accuracyMeters().doubleValue() < after.accuracyMeters().doubleValue() * 2) return false;
        long following = Duration.between(time(point), time(after)).getSeconds();
        return following > 0 && following <= maximumGap
                && distance(before, after) <= before.accuracyMeters().doubleValue() + after.accuracyMeters().doubleValue();
    }
    // A point can escape an old anchor's accuracy circle while still overlapping both
    // neighbours. If the next fix returns to the anchor, that isolated excursion is unproven.
    private static boolean unconfirmedExcursion(LocationPointRow anchor, LocationPointRow before,
                                                LocationPointRow point, LocationPointRow after, long maximumGap) {
        if (!valid(after) || !Objects.equals(before.sessionId(), point.sessionId())
                || !Objects.equals(point.sessionId(), after.sessionId())) return false;
        long elapsed = Duration.between(time(before), time(point)).getSeconds();
        long following = Duration.between(time(point), time(after)).getSeconds();
        return elapsed > 0 && elapsed <= maximumGap && following > 0 && following <= maximumGap
                && distance(anchor, point) > anchor.accuracyMeters().doubleValue() + point.accuracyMeters().doubleValue()
                && distance(before, point) <= before.accuracyMeters().doubleValue() + point.accuracyMeters().doubleValue()
                && distance(point, after) <= point.accuracyMeters().doubleValue() + after.accuracyMeters().doubleValue()
                && distance(anchor, after) <= anchor.accuracyMeters().doubleValue() + after.accuracyMeters().doubleValue();
    }
    private static boolean valid(LocationPointRow row) {
        return "ACCEPTED".equals(row.qualityStatus()) && row.clientOccurredAt() != null
                && row.longitude() != null && row.latitude() != null
                && row.longitude().abs().doubleValue() <= 180 && row.latitude().abs().doubleValue() <= 90
                && row.accuracyMeters() != null && row.accuracyMeters().doubleValue() > 0;
    }
    private static Instant time(LocationPointRow row) { return row.clientOccurredAt() == null ? row.serverReceivedAt() : row.clientOccurredAt(); }
    private static TrackPointView view(LocationPointRow row) { return new TrackPointView(row.longitude(), row.latitude(), row.accuracyMeters(), row.clientOccurredAt(), row.serverReceivedAt(), row.source(), row.qualityStatus()); }
    private static double distance(LocationPointRow a, LocationPointRow b) {
        double dy = Math.toRadians(b.latitude().doubleValue() - a.latitude().doubleValue());
        double dx = Math.toRadians(b.longitude().doubleValue() - a.longitude().doubleValue());
        double h = Math.pow(Math.sin(dy / 2), 2) + Math.cos(Math.toRadians(a.latitude().doubleValue())) * Math.cos(Math.toRadians(b.latitude().doubleValue())) * Math.pow(Math.sin(dx / 2), 2);
        return 6371000 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
