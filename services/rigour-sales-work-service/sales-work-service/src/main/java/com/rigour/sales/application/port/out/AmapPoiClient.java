package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.util.List;

/** 高德附近门店查询端口；坐标约定为 GCJ-02，由飞书定位与高德 Web 服务保持一致。 */
public interface AmapPoiClient {

    NearbyPoiPage searchAround(String keyword, BigDecimal longitude, BigDecimal latitude,
                               int radiusMeters, int page, int pageSize);

    record NearbyPoi(String poiId, String name, String address, String type, String typeCode,
                     BigDecimal longitude, BigDecimal latitude, BigDecimal distanceMeters) {
    }

    record NearbyPoiPage(List<NearbyPoi> items, int page, int pageSize, long total) {
        public NearbyPoiPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
