package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.analytics.application.port.out.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** 当前组织/业务关联变化后原子重建人员和拜访投影；失败拒绝请求，不能返回旧的扩大范围。 */
@Component
public class BiPeopleProjector {
    private final BiSourceSnapshotProjector sources;
    private final EmployeeAnalyticsStore employees;
    private final CityContactAnalyticsStore contacts;
    private final Clock clock;

    public BiPeopleProjector(
            BiSourceSnapshotProjector sources,
            EmployeeAnalyticsStore employees,
            CityContactAnalyticsStore contacts,
            Clock analyticsClock) {
        this.sources = sources;
        this.employees = employees;
        this.contacts = contacts;
        this.clock = analyticsClock;
    }

    @Transactional
    public void refresh(UUID tenant) {
        if (sources.refreshPeople(tenant)) {
            employees.refresh(tenant.toString(), clock.instant());
            contacts.refresh(tenant.toString(), clock.instant());
        }
    }
}
