package com.rigour.merchant.api.v1.model;

import java.util.UUID;

/** 业务核对所需的客户资料；不返回原始接口报文或内部错误。 */
public final class CustomerSourceReviewModels {
    private CustomerSourceReviewModels() {}

    public record Review(
            UUID connectorId,
            String sourceId,
            String sourceCode,
            String customerName,
            String salespersonName,
            String areaName,
            String issueCode,
            String issueMessage,
            long revision) {}

    public record Decision(
            UUID connectorId,
            String sourceId,
            long revision,
            Long customerId,
            boolean independent,
            String reason) {}
}
