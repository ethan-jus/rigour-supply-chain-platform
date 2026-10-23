package com.rigour.hr.api.v1.model;

public record AttendanceDepartmentOption(
        Long id, Long parentId, String departmentName, int sortOrder) {}
