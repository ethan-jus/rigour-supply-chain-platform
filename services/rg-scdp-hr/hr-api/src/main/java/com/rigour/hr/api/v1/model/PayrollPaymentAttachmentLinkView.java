package com.rigour.hr.api.v1.model;

import java.time.Instant;

/** 导出凭证的私有 COS 签名链接；持有链接者在有效期内可读取原文件。 */
public record PayrollPaymentAttachmentLinkView(String url, Instant expiresAt) {}
