package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.CustomerAttributeAnalyticsView.Item;
import java.time.Instant;
import java.util.List;

/** 客户档案属性分析只读端口；刷新由现有 CRM_CUSTOMER 任务在同一事务完成。 */
public interface CustomerAttributeAnalyticsStore {
    Snapshot read(String tenantId, Instant from, Instant to, String regionCode, String employeeCode);
    record Snapshot(Instant syncedAt, List<Item> sources, List<Item> businessCategories) {}
}
