package com.rigour.analytics.application.port.out;

import java.util.*;

/** 归属投影来源端口。版本来自所属领域；调用者无法选择表名或 SQL。 */
public interface BiAuthoritySource {
    String version(UUID tenant, String source);

    Page page(UUID tenant, String source, long afterId);

    record Page(
            String version, List<Map<String, Object>> items, List<Map<String, Object>> regions) {}
}
