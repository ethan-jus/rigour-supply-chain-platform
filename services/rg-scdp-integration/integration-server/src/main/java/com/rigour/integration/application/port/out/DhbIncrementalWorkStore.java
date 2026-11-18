package com.rigour.integration.application.port.out;

import java.time.Instant;
import java.util.*;

/** 拉取进度与业务应用分离；原始工作项提交成功后，失败项才允许脱离扫描窗口重试。 */
public interface DhbIncrementalWorkStore {
    record Item(String id, String fingerprint, String payload, Instant sourceUpdatedAt) {}

    Set<String> stage(UUID tenant, UUID connector, String type, List<Item> items, Instant now);

    void complete(
            UUID tenant, UUID connector, String type, Item item, boolean applied, Instant now);

    List<Item> pending(UUID tenant, UUID connector, String type, int limit, Instant now);

    default Item find(UUID tenant, UUID connector, String type, String sourceId) { return null; }

    default void waitForManual(UUID tenant, UUID connector, String type, Item item, Instant now) {
        complete(tenant, connector, type, item, false, now);
    }

    default Set<String> awaitingManual(UUID tenant, UUID connector, String type, Set<String> sourceIds) {
        return Set.of();
    }

    default List<Item> manualShipments(UUID tenant, UUID connector, int limit, Instant now) {
        return List.of();
    }

    /** 仅改变处理队列，不创建业务单据；指纹防止复核期间的新来源被旧结果恢复。 */
    default boolean resumeShipment(UUID tenant, UUID connector, Item item, Instant now) {
        return false;
    }

    default void deferShipmentReview(UUID tenant, UUID connector, Item item, Instant nextReview) {}

    /** 字典对应改变后复核受影响的原发货；不创建运输或改写业务金额。 */
    default int recheckShipmentDictionary(UUID tenant, String sourceField, String sourceValue, Instant now) {
        return 0;
    }

    long pendingCount(UUID tenant, UUID connector, String type);
}
