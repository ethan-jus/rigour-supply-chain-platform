package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.util.List;

/** 高德门店查询端口；坐标约定为 GCJ-02，由调用方在本地完成坐标转换。 */
public interface AmapPoiClient {

    NearbyPoiPage searchAround(String keyword, BigDecimal longitude, BigDecimal latitude,
                               int radiusMeters, int page, int pageSize);

    /**
     * 在指定城市内按关键词检索，不用打卡距离裁剪搜索结果。
     * 返回的文本搜索 POI 通常不带 distance，由调用方使用当前 GCJ-02 坐标本地计算。
     */
    NearbyPoiPage searchText(String keyword, String city, int page, int pageSize);

    record NearbyPoi(String poiId, String name, String address, String type, String typeCode,
                     BigDecimal longitude, BigDecimal latitude, BigDecimal distanceMeters,
                     String cityName, String adcode) {

        /** 兼容已有端口与测试构造器。新门店公开接口只接受带城市证据的候选。 */
        public NearbyPoi(
                String poiId,
                String name,
                String address,
                String type,
                String typeCode,
                BigDecimal longitude,
                BigDecimal latitude,
                BigDecimal distanceMeters) {
            this(poiId, name, address, type, typeCode, longitude, latitude, distanceMeters,
                    null, null);
        }
    }

    record NearbyPoiPage(List<NearbyPoi> items, int page, int pageSize, long total) {
        public NearbyPoiPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
