package com.rigour.order.api.v1.model;

import java.time.Instant;

public record LogisticsCostAttachmentView(
        String id,
        String fileName,
        String contentType,
        long size,
        String createdBy,
        Instant createdTime,
        String categoryCode) {
    public LogisticsCostAttachmentView(
            String id,
            String fileName,
            String contentType,
            long size,
            String createdBy,
            Instant createdTime) {
        this(id, fileName, contentType, size, createdBy, createdTime, "EXPENSE_VOUCHER");
    }
}
