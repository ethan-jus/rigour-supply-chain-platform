package com.rigour.hr.api.v1.model;

import java.time.Instant;

/** Private payroll payment evidence. Object keys and signed storage URLs are never exposed. */
public record PayrollPaymentAttachmentView(
        String id, String fileName, String contentType, long size,
        String createdBy, String createdByName, Instant createdTime) {}
