package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.VisitAnalyticsView;
import java.time.Instant;

/** 仅查询 BI 已提交拜访投影，不在页面请求中访问 Sales 或 HR 业务库。 */
public interface VisitAnalyticsStore {
    VisitAnalyticsView read(String tenantId, Instant from, Instant to, String regionCode, String employeeCode);
}
