package com.rigour.sales.api.v1.model;

import java.time.LocalDate;
import java.util.List;

public final class SalesAttendanceModels {
    private SalesAttendanceModels() {}

    public record Query(List<String> employeeCodes, LocalDate from, LocalDate to) {}

    public record Evidence(String employeeCode, boolean linked, List<LocalDate> dates) {}
}
