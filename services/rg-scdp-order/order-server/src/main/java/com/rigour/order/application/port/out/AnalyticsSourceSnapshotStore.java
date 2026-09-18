package com.rigour.order.application.port.out;

import java.util.*;

/** 只导出登记给 BI 的列；数据仍由当前领域维护。 */
public interface AnalyticsSourceSnapshotStore {
    String version(String tenant, String dataset);

    Page page(String tenant, String dataset, String after);

    record Page(String version, List<Map<String, String>> items) {}
}
