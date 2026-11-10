package com.rigour.analytics.api.v1.model;

import java.util.List;

public record CostDepartmentSummary(
        String departmentId,
        String departmentName,
        String purchaseGoods,
        String purchaseFreight,
        String logistics,
        List<Person> people,
        List<Transport> transportTypes) {
    public record Transport(String key, String label, String amount) {}

    public record Person(String employeeCode, String employeeName, String role, String amount) {}
}
