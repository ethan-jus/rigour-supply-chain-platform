package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackPointView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackLegView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackGapView;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository.LocationPointRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Observed route estimates only; never joins sessions, missing samples or implausible jumps. */
final class SalesTrackMetrics {
    record Result(int distanceMeters, int trackedMinutes, boolean available, int rejected,
                  List<TrackLegView> legs, List<TrackGapView> gaps, List<ObservedEdge> confirmedEdges) {
        Result(int distanceMeters, int trackedMinutes, boolean available, int rejected,
               List<TrackLegView> legs, List<TrackGapView> gaps) {
            this(distanceMeters, trackedMinutes, available, rejected, legs, gaps, List.of());
        }
    }
    record ObservedEdge(Instant from, Instant to, double meters) {}

    static Result calculate(List<LocationPointRow> input, int sampleIntervalSeconds) {
        var sessions = new LinkedHashMap<UUID, List<LocationPointRow>>();
        var previousFormat = new ArrayList<LocationPointRow>();
        for (var row : input) {
            if (row.confirmedTrack()) sessions.computeIfAbsent(row.sessionId(), ignored -> new ArrayList<>()).add(row);
            else previousFormat.add(row);
        }
        var legacy = legacy(previousFormat, sampleIntervalSeconds);
        if (sessions.isEmpty()) return legacy;
        var legs = new ArrayList<>(legacy.legs());
        var gaps = new ArrayList<>(legacy.gaps());
        var edges = new ArrayList<ObservedEdge>();
        int distance = legacy.distanceMeters(), minutes = legacy.trackedMinutes(), rejected = legacy.rejected();
        boolean available = legacy.available();
        for (var rows : sessions.values()) {
            var confirmed = confirmed(rows, sampleIntervalSeconds);
            distance += confirmed.distanceMeters();
            minutes += confirmed.trackedMinutes();
            rejected += confirmed.rejected();
            available |= confirmed.available();
            legs.addAll(confirmed.legs());
            gaps.addAll(confirmed.gaps());
            edges.addAll(confirmed.confirmedEdges());
        }
        legs.sort(Comparator.comparing(leg -> leg.points().getFirst().clientOccurredAt()));
        gaps.sort(Comparator.comparing(TrackGapView::from));
        return new Result(distance, minutes, available, rejected, List.copyOf(legs), List.copyOf(gaps), List.copyOf(edges));
    }

    private static Result legacy(List<LocationPointRow> input, int sampleIntervalSeconds) {
        var rows = input.stream().sorted(Comparator.comparing(SalesTrackMetrics::time)).toList();
        long maximumGap = Math.max(180L, Math.min(300L, sampleIntervalSeconds * 2L));
        long movingGap = Math.max(20L, Math.min(maximumGap, sampleIntervalSeconds * 3L));
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
            if ("APP_NETWORK".equals(row.source())) { broken = true; continue; }
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
                long gapLimit = "APP_GPS".equals(previous.source()) && "APP_GPS".equals(row.source())
                        ? movingGap : maximumGap;
                String reason = !Objects.equals(previous.sessionId(), row.sessionId()) ? "SESSION_BOUNDARY"
                        : broken ? "REJECTED_SAMPLES" : elapsed <= 0 ? "INVALID_TIME"
                        : elapsed > gapLimit ? "MISSING_SAMPLES" : hop / elapsed > 55 ? "IMPLAUSIBLE_SPEED" : null;
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

    private static Result confirmed(List<LocationPointRow> input, int sampleIntervalSeconds) {
        var rows = input.stream().sorted(Comparator.comparing(SalesTrackMetrics::time)).toList();
        var eligible = new HashMap<String, LocationPointRow>();
        var legs = new ArrayList<TrackLegView>();
        var gaps = new ArrayList<TrackGapView>();
        var edges = new ArrayList<ObservedEdge>();
        var path = new ArrayList<TrackPointView>();
        long movingGapMs = Math.max(20L, Math.min(300L, sampleIntervalSeconds * 3L)) * 1000;
        long restingGapMs = Math.max(180L, sampleIntervalSeconds * 2L) * 1000;
        long observedMs = 0;
        int pairs = 0, rejected = 0;
        boolean interrupted = false;
        double total = 0, legDistance = 0;
        LocationPointRow previous = null, vertex = null;
        for (var witness : rows) {
            if (!valid(witness) || witness.accuracyMeters().doubleValue() > 25) {
                if (!"APP_NETWORK".equals(witness.source())) rejected++;
                interrupted = true;
                eligible.clear();
                continue;
            }
            if (previous != null) {
                long elapsed = Duration.between(time(previous), time(witness)).toMillis();
                long limit = resting(previous) || resting(witness) ? restingGapMs : movingGapMs;
                if (elapsed <= 0 || elapsed > limit || interrupted) {
                    gaps.add(new TrackGapView(time(previous), time(witness), interrupted ? "REJECTED_SAMPLES" :
                            elapsed <= 0 ? "INVALID_TIME" : "MISSING_SAMPLES"));
                    eligible.clear();
                } else {
                    pairs++;
                    observedMs += elapsed;
                }
            }
            previous = witness;
            interrupted = false;
            if ("APP_GPS".equals(witness.source())) eligible.put(witness.deviceEventId(), witness);
            if (witness.routePath().isEmpty()) continue;
            var proposed = witness.routePath().stream().map(eligible::get).toList();
            if (!validPath(proposed, vertex, movingGapMs)) { rejected++; continue; }
            boolean extendsLeg = proposed.getFirst() == vertex;
            if (!extendsLeg) {
                if (path.size() > 1) legs.add(new TrackLegView((int)Math.round(legDistance), path, true));
                path = new ArrayList<>();
                path.add(view(proposed.getFirst()));
                legDistance = 0;
            }
            for (int index = 1; index < proposed.size(); index++) {
                var from = proposed.get(index - 1);
                var to = proposed.get(index);
                double meters = confirmedDistance(from, to);
                total += meters;
                legDistance += meters;
                path.add(view(to));
                edges.add(new ObservedEdge(time(from), time(to), meters));
            }
            vertex = proposed.getLast();
        }
        if (path.size() > 1) legs.add(new TrackLegView((int)Math.round(legDistance), path, true));
        return new Result((int)Math.round(total), (int)(observedMs / 60000), pairs > 0, rejected, legs, gaps, edges);
    }

    private static boolean validPath(List<LocationPointRow> points, LocationPointRow previousVertex, long maxGapMs) {
        if (points.size() < 2 || points.stream().anyMatch(Objects::isNull)) return false;
        var first = points.getFirst();
        if (previousVertex != null && first != previousVertex && !time(first).isAfter(time(previousVertex))) return false;
        if (first != previousVertex && (points.size() < 3 || confirmedDistance(first, points.getLast()) < 11.99)) return false;
        for (int index = 1; index < points.size(); index++) {
            var a = points.get(index - 1);
            var b = points.get(index);
            long elapsed = Duration.between(time(a), time(b)).toMillis();
            if (elapsed <= 0 || elapsed > maxGapMs || confirmedDistance(a, b) * 1000 > 55 * elapsed) return false;
        }
        return true;
    }

    private static boolean resting(LocationPointRow point) { return "APP_GPS_STILL".equals(point.source()); }
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
        return !"APP_NETWORK".equals(row.source()) && "ACCEPTED".equals(row.qualityStatus()) && row.clientOccurredAt() != null
                && row.longitude() != null && row.latitude() != null
                && row.longitude().abs().doubleValue() <= 180 && row.latitude().abs().doubleValue() <= 90
                && row.accuracyMeters() != null && row.accuracyMeters().doubleValue() > 0;
    }
    private static Instant time(LocationPointRow row) { return row.clientOccurredAt() == null ? row.serverReceivedAt() : row.clientOccurredAt(); }
    private static TrackPointView view(LocationPointRow row) { return new TrackPointView(row.longitude(), row.latitude(), row.accuracyMeters(), row.clientOccurredAt(), row.serverReceivedAt(), row.source(), row.qualityStatus()); }
    private static double distance(LocationPointRow a, LocationPointRow b) {
        return distance(a.latitude().doubleValue(), a.longitude().doubleValue(), b.latitude().doubleValue(), b.longitude().doubleValue());
    }
    private static double confirmedDistance(LocationPointRow a, LocationPointRow b) {
        if (a.rawLongitude() == null || a.rawLatitude() == null || b.rawLongitude() == null || b.rawLatitude() == null)
            return distance(a, b);
        return distance(a.rawLatitude().doubleValue(), a.rawLongitude().doubleValue(),
                b.rawLatitude().doubleValue(), b.rawLongitude().doubleValue());
    }
    private static double distance(double latA, double lngA, double latB, double lngB) {
        double dy = Math.toRadians(latB - latA);
        double dx = Math.toRadians(lngB - lngA);
        double h = Math.pow(Math.sin(dy / 2), 2) + Math.cos(Math.toRadians(latA)) * Math.cos(Math.toRadians(latB)) * Math.pow(Math.sin(dx / 2), 2);
        return 6371000 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
