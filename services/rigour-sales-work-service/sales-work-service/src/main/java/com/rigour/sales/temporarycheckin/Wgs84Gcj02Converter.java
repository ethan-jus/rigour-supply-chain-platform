package com.rigour.sales.temporarycheckin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 纯本地 WGS84 -> GCJ-02 近似转换。它只用于将浏览器 GPS 作为高德周边搜索中心，
 * 不作为地址、行政区或考勤凭证。
 */
@Component
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
class Wgs84Gcj02Converter {

    private static final double A = 6_378_245.0d;
    private static final double EE = 0.00669342162296594323d;

    Coordinates convert(BigDecimal longitude, BigDecimal latitude) {
        double lng = longitude.doubleValue();
        double lat = latitude.doubleValue();
        if (outsideMainlandChina(lng, lat)) {
            return new Coordinates(scale(longitude), scale(latitude));
        }

        double deltaLat = transformLatitude(lng - 105.0d, lat - 35.0d);
        double deltaLng = transformLongitude(lng - 105.0d, lat - 35.0d);
        double radLat = lat / 180.0d * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1.0d - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        deltaLat = (deltaLat * 180.0d)
                / ((A * (1.0d - EE)) / (magic * sqrtMagic) * Math.PI);
        deltaLng = (deltaLng * 180.0d)
                / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new Coordinates(scale(BigDecimal.valueOf(lng + deltaLng)),
                scale(BigDecimal.valueOf(lat + deltaLat)));
    }

    private static boolean outsideMainlandChina(double longitude, double latitude) {
        return longitude < 72.004d || longitude > 137.8347d
                || latitude < 0.8293d || latitude > 55.8271d;
    }

    private static double transformLatitude(double x, double y) {
        double result = -100.0d + 2.0d * x + 3.0d * y + 0.2d * y * y
                + 0.1d * x * y + 0.2d * Math.sqrt(Math.abs(x));
        result += (20.0d * Math.sin(6.0d * x * Math.PI)
                + 20.0d * Math.sin(2.0d * x * Math.PI)) * 2.0d / 3.0d;
        result += (20.0d * Math.sin(y * Math.PI)
                + 40.0d * Math.sin(y / 3.0d * Math.PI)) * 2.0d / 3.0d;
        result += (160.0d * Math.sin(y / 12.0d * Math.PI)
                + 320.0d * Math.sin(y * Math.PI / 30.0d)) * 2.0d / 3.0d;
        return result;
    }

    private static double transformLongitude(double x, double y) {
        double result = 300.0d + x + 2.0d * y + 0.1d * x * x
                + 0.1d * x * y + 0.1d * Math.sqrt(Math.abs(x));
        result += (20.0d * Math.sin(6.0d * x * Math.PI)
                + 20.0d * Math.sin(2.0d * x * Math.PI)) * 2.0d / 3.0d;
        result += (20.0d * Math.sin(x * Math.PI)
                + 40.0d * Math.sin(x / 3.0d * Math.PI)) * 2.0d / 3.0d;
        result += (150.0d * Math.sin(x / 12.0d * Math.PI)
                + 300.0d * Math.sin(x / 30.0d * Math.PI)) * 2.0d / 3.0d;
        return result;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    record Coordinates(BigDecimal longitude, BigDecimal latitude) { }
}
