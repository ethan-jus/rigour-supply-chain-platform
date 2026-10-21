package com.rigour.order.api.v1.model;

import java.time.Instant;

public record LogisticsCostAttachmentView(
        String id,
        String fileName,
        String contentType,
        long size,
        String createdBy,
        Instant createdTime) {}
