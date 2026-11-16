package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.NearbyModels.Origin;
import com.rigour.shared.core.exception.RequestValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Indexed bounding boxes first; only page-sized results enter the JVM. */
final class NearbyStoreQuery {
    static final double EARTH = 6371008.8;
    static final double WORLD = Math.PI * EARTH + 1;
    static final String LOCATED = "s.longitude BETWEEN -180 AND 180 AND s.latitude BETWEEN -90 AND 90 AND s.coordinate_system IN ('GCJ02','WGS84')";
    record Hit(String id, Long millimeters, Double longitude, Double latitude, String coordinateSystem) {
        Double meters() { return millimeters == null ? null : millimeters / 1000d; }
    }
    record Result(List<Hit> hits, String nextCursor) {}
    record Cursor(Long distance, String id, double radius) {}
    record Box(double west, double south, double east, double north) {}

    static Box box(double lng, double lat, double radius) {
        double angle = Math.min(Math.PI, radius / EARTH);
        double south = Math.max(-90, lat - Math.toDegrees(angle));
        double north = Math.min(90, lat + Math.toDegrees(angle));
        double delta = south == -90 || north == 90 ? 180 : Math.toDegrees(Math.asin(Math.min(1, Math.sin(angle) / Math.cos(Math.toRadians(lat)))));
        // A box crossing the date line uses all longitudes; distance still limits the circle.
        return new Box(lng - delta < -180 || lng + delta > 180 ? -180 : lng - delta, south,
                lng - delta < -180 || lng + delta > 180 ? 180 : lng + delta, north);
    }
    static String fingerprint(String from, String where, List<Object> args, Origin origin) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((from + where + args + origin).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static Cursor cursor(String value, String fingerprint) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > 512) throw new IllegalArgumentException();
            String[] p = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (p.length != 5 || !p[0].equals("N1") || !p[1].equals(fingerprint) || !p[3].matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            Long distance = p[2].equals("unknown") ? null : Long.valueOf(p[2]);
            double radius = Double.parseDouble(p[4]);
            if (!Double.isFinite(radius) || radius < 2000 || radius > WORLD || distance != null && (distance < 0 || distance > WORLD * 1000)) throw new IllegalArgumentException();
            return new Cursor(distance, p[3], radius);
        } catch (IllegalArgumentException e) { throw new RequestValidationException("附近门店分页已失效，请刷新列表"); }
    }
    static String encode(Hit last, double radius, String fingerprint) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("N1|" + fingerprint + "|" + (last.millimeters == null ? "unknown" : last.millimeters) + "|" + last.id + "|" + radius).getBytes(StandardCharsets.UTF_8));
    }
    static Result search(JdbcTemplate jdbc, String from, String where, List<Object> args, Origin origin, String after, int size, long totalHint) {
        String key = fingerprint(from, where, args, origin);
        Cursor cursor = cursor(after, key);
        // A page-sized scope is cheaper through its business/owner indexes than repeated geographic expansion.
        // The count is only a planner hint: LIMIT size+1 still proves whether another page exists.
        boolean smallScope = totalHint > 0 && totalHint <= size;
        double radius = smallScope ? WORLD : cursor == null ? 2000 : cursor.radius;
        var hits = new ArrayList<Hit>();
        if (cursor == null || cursor.distance != null) {
            do {
                hits.clear();
                hits.addAll(located(jdbc, from, where, args, "GCJ02", origin.gcjLongitude(), origin.gcjLatitude(), radius, cursor, size + 1, !smallScope));
                hits.addAll(located(jdbc, from, where, args, "WGS84", origin.gpsLongitude(), origin.gpsLatitude(), radius, cursor, size + 1, !smallScope));
                hits.sort(Comparator.comparing(Hit::millimeters).thenComparing(h -> Long.parseLong(h.id)));
                if (hits.size() >= size + 1 || radius == WORLD) break;
                radius = Math.min(WORLD, radius * 4);
            } while (true);
        }
        if (hits.size() < size + 1) {
            var unknownArgs = new ArrayList<>(args);
            String paging = "";
            if (cursor != null && cursor.distance == null) { paging = " AND s.id>?"; unknownArgs.add(cursor.id); }
            unknownArgs.add(size + 1 - hits.size());
            hits.addAll(jdbc.query("SELECT s.id,s.longitude,s.latitude,s.coordinate_system" + from + " WHERE " + where
                    + " AND NOT COALESCE((" + LOCATED + "),FALSE)" + paging + " ORDER BY s.id LIMIT ?",
                    (r,n) -> new Hit(r.getString("id"), null, null, null, r.getString("coordinate_system")), unknownArgs.toArray()));
        }
        boolean more = hits.size() > size;
        var page = List.copyOf(hits.subList(0, Math.min(size, hits.size())));
        return new Result(page, more ? encode(page.getLast(), radius, key) : null);
    }
    private static List<Hit> located(JdbcTemplate jdbc, String from, String where, List<Object> args, String system,
                                    double lng, double lat, double radius, Cursor cursor, int limit, boolean geographicIndex) {
        Box b = box(lng, lat, radius);
        var p = new ArrayList<Object>();
        p.add(lat); p.add(lat); p.add(lng); p.addAll(args);
        p.add(system); p.add(b.west); p.add(b.east); p.add(b.south); p.add(b.north); p.add(Math.round(radius * 1000));
        String paging = "";
        if (cursor != null) { paging = " AND (distance_mm>? OR (distance_mm=? AND s.id>?))"; p.add(cursor.distance); p.add(cursor.distance); p.add(cursor.id); }
        p.add(limit);
        // Round to millimeters for deterministic keyset ties, including equal-location stores.
        String distance = "ROUND(12742017.6*ASIN(SQRT(LEAST(1,POW(SIN(RADIANS(s.latitude-?)/2),2)+COS(RADIANS(?))*COS(RADIANS(s.latitude))*POW(SIN(RADIANS(s.longitude-?)/2),2))))*1000)";
        // Existing (tenant_id,longitude,latitude,id) index narrows the range before sorting.
        String indexed = !geographicIndex ? from : from.replace("crm_store_summary q JOIN crm_store_master s", "crm_store_master s FORCE INDEX (ix_store_map_coordinates) STRAIGHT_JOIN crm_store_summary q");
        return jdbc.query("SELECT s.id,s.longitude,s.latitude,s.coordinate_system," + distance + " AS distance_mm" + indexed
                + " WHERE " + where + " AND s.coordinate_system=? AND s.longitude BETWEEN ? AND ? AND s.latitude BETWEEN ? AND ?"
                + " HAVING distance_mm<=?" + paging + " ORDER BY distance_mm,s.id LIMIT ?",
                (r,n) -> new Hit(r.getString("id"), r.getLong("distance_mm"), r.getDouble("longitude"), r.getDouble("latitude"), r.getString("coordinate_system")), p.toArray());
    }
}
