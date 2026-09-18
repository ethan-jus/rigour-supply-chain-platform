package com.rigour.analytics.api.v1.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 拜访独立看板；去重对象为 Sales 门店，审核数为提交次数，分组去重数不能相加代替总体。 */
public record VisitAnalyticsView(String status, Instant syncedAt, Instant from, Instant to,
        Summary summary, List<Day> days, List<City> cities, List<Person> people) {
    public record Summary(long visits, long contactedStores, long visitingPeople, long repeatStores,
            long approvedVisits, long pendingVisits, long flaggedVisits, long unlinkedEmployeeVisits,
            long crmLinkedStores) { }
    public record Day(LocalDate date, long visits, long contactedStores) { }
    public record City(String regionCode, String cityName, long visits, long contactedStores,
            long visitingPeople, long crmLinkedStores) { }
    public record Person(String salespersonId, String employeeCode, String employeeName,
            long visits, long contactedStores, long activeDays, long approvedVisits, long pendingVisits,
            long flaggedVisits) { }
}
