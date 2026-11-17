package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public final class TransportFeeImportModels {
    private TransportFeeImportModels() {}

    public record Selection(int line, String costId, int revision) {}

    public record Apply(String fileHash, String carrierCode, List<Selection> selections) {}

    public record Item(
            int line,
            String trackingNo,
            BigDecimal amount,
            String remark,
            String costId,
            String documentNo,
            Integer revision,
            String state,
            String message) {}

    public record Preview(String fileHash, List<Item> items) {}
}
