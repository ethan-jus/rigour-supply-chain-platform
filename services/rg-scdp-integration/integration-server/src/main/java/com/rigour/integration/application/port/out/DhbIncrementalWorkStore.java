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

    long pendingCount(UUID tenant, UUID connector, String type);
}
