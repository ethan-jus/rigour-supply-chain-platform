package com.rigour.analytics.application.model;

import java.time.Instant;
import java.time.ZoneId;

/** BI 业务日与月份按北京时间划分；API Instant、数据库 UTC 时间和同步水位不做偏移存储。 */
public final class BiBusinessTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BiBusinessTime() {}

    public static Instant monthStart(Instant instant) {
        return instant.atZone(ZONE).toLocalDate().withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
    }
}
