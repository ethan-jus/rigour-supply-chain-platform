package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 货拉拉原始报表独立暂存；只有明确关联销售单后才生成待确认费用。 */
public final class LogisticsImportModels {
    private LogisticsImportModels() {}

    public record CarrierOrder(
            String enterpriseId,
            String orderNo,
            String status,
            String useTime,
            BigDecimal paidAmount,
            Map<String, String> raw,
            List<String> salesOrderNos,
            int line) {}

    public record Upload(String fileName, String fileHash, List<CarrierOrder> rows) {}

    public record Row(
            String id,
            int revision,
            String state,
            String costId,
            String fileName,
            CarrierOrder order) {}

    public record Generated(int revision, LogisticsCostCommand command) {}
}
