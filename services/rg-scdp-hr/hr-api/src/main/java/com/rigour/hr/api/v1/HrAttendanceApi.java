package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.HrAttendanceModels.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

public interface HrAttendanceApi {
    String ROOT = "/api/v1/hr/attendance";

    @GetMapping(ROOT + "/rule")
    ApiResponse<Rule> rule(
            @RequestParam String month, @RequestParam(required = false) Long departmentId);

    @GetMapping(ROOT + "/rules")
    ApiResponse<RuleListView> rules(
            @RequestParam String month,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step);

    @PutMapping(ROOT + "/rule")
    ApiResponse<Rule> saveRule(@RequestBody RuleCommand command);

    @GetMapping(ROOT + "/departments")
    ApiResponse<List<DepartmentOption>> departments();

    @GetMapping(ROOT)
    ApiResponse<View> list(
            @RequestParam String month,
            @RequestParam(defaultValue = "") String employeeName,
            @RequestParam(defaultValue = "") String employeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "") String attendanceStatus,
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step);

    @PostMapping(ROOT + "/{month}/{employeeCode}/confirm")
    ApiResponse<Void> confirmRecord(
            @PathVariable String month,
            @PathVariable String employeeCode,
            @RequestBody RecordCommand command);
}
