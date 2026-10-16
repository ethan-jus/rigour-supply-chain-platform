package com.rigour.erp.api.v1.model;

import java.time.Instant;

public record ErpCostAttachmentView(
        String id,
        String fileName,
        String contentType,
        long size,
        String createdBy,
        Instant createdTime) {}
