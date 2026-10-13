package com.rigour.order.api.v1.model;

import java.util.List;

public record LogisticsCostTransportDetails(
        String legCode,
        List<Long> sourceIds,
        List<Long> voucherIds,
        String carrierName,
        String trackingNo,
        String transportMethodCode,
        String payerCode) {}
