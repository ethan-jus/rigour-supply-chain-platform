package com.rigour.erp.api.v1.model;

import java.time.LocalDate;

/** 外部来源单据快照，没有本系统单据 ID，不生成采购、销售或库存业务。 */
public record ErpCostExternalDocument(
        String sourceSystem,
        String documentType,
        String documentNo,
        LocalDate documentDate,
        String senderName,
        String receiverName,
        String remark,
        Long linkedId,
        String sourceSystemName,
        String originAddress,
        String destinationAddress,
        String differenceReason) {
    public ErpCostExternalDocument(
            String sourceSystem,
            String documentType,
            String documentNo,
            LocalDate documentDate,
            String senderName,
            String receiverName,
            String remark) {
        this(
                sourceSystem,
                documentType,
                documentNo,
                documentDate,
                senderName,
                receiverName,
                remark,
                null,
                null,
                null,
                null,
                null);
    }
}
