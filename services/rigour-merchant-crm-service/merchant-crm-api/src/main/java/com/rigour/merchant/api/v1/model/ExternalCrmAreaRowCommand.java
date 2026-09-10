package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 外部区域/城市单行同步命令。 */
public record ExternalCrmAreaRowCommand(
        UUID connectorId,
        String sourceTenantKey,
        String sourceAreaId,
        String regionName,
        String cityName,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt) {
}
