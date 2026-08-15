package com.rigour.order.api.v1.model;

import java.time.Instant;

/** 订货宝订单原始列表/详情报文的本地只读版本。 */
public record DhbOrderSourceRecordView(
        String payloadType,
        String payloadJson,
        String payloadHash,
        Instant receivedAt) {
}
