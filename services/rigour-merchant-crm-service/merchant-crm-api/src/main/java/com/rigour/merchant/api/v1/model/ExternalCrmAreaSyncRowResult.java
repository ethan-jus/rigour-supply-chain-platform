package com.rigour.merchant.api.v1.model;

/** 外部区域/城市单行同步结果。 */
public record ExternalCrmAreaSyncRowResult(
        String sourceAreaId,
        String regionCode,
        String cityCode,
        String status,
        String message) {
}
