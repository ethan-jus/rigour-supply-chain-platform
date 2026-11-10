package com.rigour.order.api.v1.model;

import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class TransportRecordModels {
    private TransportRecordModels() {}

    public record Draft(
            Integer revision,
            String transportType,
            LocalDate date,
            String carrierCode,
            String trackingNo,
            String platformOrderNo,
            String warehouseCode,
            String senderName,
            String senderContact,
            String senderPhone,
            String senderAddress,
            String cityCode,
            BigDecimal amount,
            String ownerEmployeeCode,
            String remark,
            String reason,
            String zeroReason,
            List<String> orderCodes,
            String requestId) {
        public Draft {
            trackingNo = trackingNo == null || trackingNo.isBlank() ? null : trackingNo.strip();
            platformOrderNo =
                    platformOrderNo == null || platformOrderNo.isBlank()
                            ? null
                            : platformOrderNo.strip();
        }
    }

    public record PlatformRecord(Row source, Draft draft, List<CarrierOrder> billHistory) {}

    public record Page(LogisticsCostPageView page, Map<String, PlatformRecord> platformRecords) {}

    public record Detail(LogisticsCostDetailView record, PlatformRecord platform) {}
}
