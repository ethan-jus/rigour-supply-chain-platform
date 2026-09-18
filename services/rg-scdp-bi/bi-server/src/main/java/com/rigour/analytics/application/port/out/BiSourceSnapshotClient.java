package com.rigour.analytics.application.port.out;

import java.util.*;

/** 从已登记的所属服务读取精确文本投影，不执行跨库 SQL。 */
public interface BiSourceSnapshotClient {
    String version(UUID tenant, String source, String dataset);

    Page page(UUID tenant, String source, String dataset, String after);

    record Page(String version, List<Map<String, String>> items) {}
}
