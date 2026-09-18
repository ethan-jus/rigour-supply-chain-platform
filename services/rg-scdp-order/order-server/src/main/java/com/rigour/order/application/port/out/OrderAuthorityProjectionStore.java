package com.rigour.order.application.port.out;

import java.util.*;

/** 所属领域提供最小权威事实；不将数据表或 SQL 选择器交给调用方。 */
public interface OrderAuthorityProjectionStore {
    String version(String tenant);

    Page page(String tenant, long afterId, int step);

    record Page(
            String version, List<Map<String, Object>> items, List<Map<String, Object>> regions) {}
}
