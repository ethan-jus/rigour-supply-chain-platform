package com.rigour.merchant.api.v1.model;

import java.util.List;

public final class NearbyModels {
    private NearbyModels() {}
    public record Query(Double nearLongitude, Double nearLatitude, String nearCoordinateSystem, String nearCursor) {}
    public record Coordinates(double longitude, double latitude, String coordinateSystem) {}
    public record Origin(double gpsLongitude, double gpsLatitude, double gcjLongitude, double gcjLatitude) {}
    public record Item<T>(T store, Double distanceMeters, Double longitude, Double latitude, String coordinateSystem) {}
    public record Page<T>(long total, List<Item<T>> items, String nextCursor, Origin origin) {}
}
