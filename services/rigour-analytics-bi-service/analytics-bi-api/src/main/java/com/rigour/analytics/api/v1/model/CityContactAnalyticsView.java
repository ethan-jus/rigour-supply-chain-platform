package com.rigour.analytics.api.v1.model;

import java.time.Instant;
import java.util.List;

/** Sales 已提交拜访去重口径；城市归属固定为门店当前城市，审核异常优先，审核分组互斥。 */
public record CityContactAnalyticsView(String status, Instant syncedAt, Instant from, Instant to, List<City> cities) {
    public record City(String regionCode, String cityName, long contactedStores, long approvedStores, long pendingStores,
                       long flaggedStores, Long crmLinkedStores, Long crmUnlinkedStores) { }
}
