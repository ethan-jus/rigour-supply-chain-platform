package com.rigour.order.api.v1.model;

import java.time.Instant;

/** 用户维护的发货物流字段。null 表示保留，空字符串清除物流单号或发货备注。 */
public record SalesShipmentLogisticsCommand(
        Integer revision, String carrierCode, String trackingNo, Instant shipTime, String remark) {}
