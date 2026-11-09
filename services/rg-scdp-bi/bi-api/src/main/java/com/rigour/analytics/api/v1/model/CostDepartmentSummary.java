package com.rigour.analytics.api.v1.model;

import java.util.List;

public record CostDepartmentSummary(
        String departmentId,
        String departmentName,
        String purchaseGoods,
        String purchaseFreight,
        String logistics,
        List<Person> people) {
    public record Person(String employeeCode, String employeeName, String role, String amount) {}
}
