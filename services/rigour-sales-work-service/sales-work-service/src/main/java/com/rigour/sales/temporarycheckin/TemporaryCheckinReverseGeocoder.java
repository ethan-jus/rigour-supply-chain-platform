package com.rigour.sales.temporarycheckin;

import java.math.BigDecimal;

/** 将浏览器采集的 GPS/WGS84 坐标转换为高德坐标，并生成可读地址快照。 */
public interface TemporaryCheckinReverseGeocoder {

    GeocodeResult resolve(BigDecimal longitude, BigDecimal latitude);

    record GeocodeResult(
            String status,
            String address,
            String formattedAddress,
            String adcode,
            String province,
            String city,
            String district,
            String township,
            BigDecimal amapLongitude,
            BigDecimal amapLatitude,
            String errorCode) {

        public static GeocodeResult keyMissing() {
            return new GeocodeResult("KEY_MISSING", null, null, null, null, null, null, null,
                    null, null, "AMAP_WEB_KEY_MISSING");
        }

        public static GeocodeResult failed(String errorCode) {
            return new GeocodeResult("FAILED", null, null, null, null, null, null, null,
                    null, null, errorCode);
        }
    }
}
