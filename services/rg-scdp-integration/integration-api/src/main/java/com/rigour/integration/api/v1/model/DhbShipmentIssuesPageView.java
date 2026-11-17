package com.rigour.integration.api.v1.model;

import java.util.List;

/** 按业务发货单去重后的待处理清单。 */
public record DhbShipmentIssuesPageView(long total, List<DhbSyncOpenIssueItemView> items) {
    public DhbShipmentIssuesPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
