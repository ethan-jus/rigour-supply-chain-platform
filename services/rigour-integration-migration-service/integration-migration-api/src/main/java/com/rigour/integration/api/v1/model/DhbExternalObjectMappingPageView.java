package com.rigour.integration.api.v1.model;

import java.util.List;

/** 订货宝同步中心的外部对象映射分页结果。 */
public record DhbExternalObjectMappingPageView(
        /** 符合筛选条件的映射总数。 */ long total,
        /** 当前页映射记录。 */ List<DhbExternalObjectMappingView> items) {
    public DhbExternalObjectMappingPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
