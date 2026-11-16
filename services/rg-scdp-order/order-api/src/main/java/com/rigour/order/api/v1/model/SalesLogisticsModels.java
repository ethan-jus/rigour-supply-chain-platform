package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** 销售侧只展示运输摘要并维护关系；费用仍以运输记录为唯一事实。 */
public final class SalesLogisticsModels {
    private SalesLogisticsModels() {}

    public record Context(
            LogisticsCostSourceView salesOrder,
            List<TransportRecordModels.Detail> transports,
            BigDecimal allocatedAmount,
            BigDecimal confirmedAmount,
            boolean canAssociate) {}

    public record Link(String transportId, Integer revision, String reason) {}
}
