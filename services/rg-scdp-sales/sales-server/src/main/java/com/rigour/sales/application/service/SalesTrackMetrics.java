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
        for (var row : rows) {
            if (!valid(row)) { rejected++; broken = true; continue; }
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
