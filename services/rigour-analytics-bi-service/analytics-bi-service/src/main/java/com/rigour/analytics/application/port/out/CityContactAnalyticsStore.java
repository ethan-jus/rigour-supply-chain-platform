package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.CityContactAnalyticsView.City;
import java.time.Instant;
import java.util.List;

/** Sales 建联记录本地投影端口；不存微信截图内容、联系方式或身份凭据。 */
public interface CityContactAnalyticsStore {
    List<String> tenantIds();
    SupplyDashboardStore.SourceRefreshResult refresh(String tenantId, Instant syncedAt);
    Snapshot read(String tenantId, Instant from, Instant to, String regionCode, String ownerStaffCode);
    record Snapshot(Instant syncedAt, boolean businessLinksReady, List<City> cities) { }
}
