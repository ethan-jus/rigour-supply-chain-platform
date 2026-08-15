package com.rigour.order.domain.model.order;

import java.time.LocalDateTime;

/** 订货宝订单来源报文版本；只读用于详情追溯和原始订单对比。 */
public record OrderSourceRecord(
        String payloadType,
        String payloadJson,
        String payloadHash,
        LocalDateTime receivedAt) {
}
