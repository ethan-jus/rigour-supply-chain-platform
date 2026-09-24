package com.rigour.analytics.application.port.out;

import java.util.*;

/** 配置和派发状态都在 Integration，BI 仅消费执行请求。 */
public interface BiScheduleCoordinator {
    record Work(UUID tenantId, long version) {}

    List<Work> due();

    boolean managed(UUID tenant);

    boolean claim(UUID tenant, long version, UUID token);

    void heartbeat(UUID tenant, UUID token);

    void complete(UUID tenant, UUID token, String status, String message);
}
