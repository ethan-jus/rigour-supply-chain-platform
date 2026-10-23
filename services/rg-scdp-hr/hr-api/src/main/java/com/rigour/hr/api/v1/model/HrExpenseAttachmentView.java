package com.rigour.hr.api.v1.model;

import java.time.Instant;

public record HrExpenseAttachmentView(
        String id,
        String fileName,
        String contentType,
        long size,
        String createdBy,
        Instant createdTime) {}
