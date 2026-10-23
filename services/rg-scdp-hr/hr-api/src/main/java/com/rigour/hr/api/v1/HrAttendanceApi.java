package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.AttendanceDepartmentOption;
import com.rigour.hr.api.v1.model.AttendanceRecordCommand;
import com.rigour.hr.api.v1.model.AttendanceReopenCommand;
import com.rigour.hr.api.v1.model.AttendanceRuleCommand;
import com.rigour.hr.api.v1.model.AttendanceRuleListView;
import com.rigour.hr.api.v1.model.AttendanceRuleView;
import com.rigour.hr.api.v1.model.AttendanceView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

public interface HrAttendanceApi {
    String ROOT = "/api/v1/hr/attendance";

    @GetMapping(ROOT + "/rule")
    ApiResponse<AttendanceRuleView> rule(
            @RequestParam String month, @RequestParam(required = false) Long departmentId);

    @GetMapping(ROOT + "/rules")
    ApiResponse<AttendanceRuleListView> rules(
            @RequestParam String month,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step);

    @PutMapping(ROOT + "/rule")
    ApiResponse<AttendanceRuleView> saveRule(@RequestBody AttendanceRuleCommand command);

    @GetMapping(ROOT + "/departments")
    ApiResponse<List<AttendanceDepartmentOption>> departments(
            @RequestParam(defaultValue = "false") boolean forRules);

    @GetMapping(ROOT)
    ApiResponse<AttendanceView> list(
            @RequestParam String month,
            @RequestParam(defaultValue = "") String employeeName,
            @RequestParam(defaultValue = "") String employeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "") String attendanceStatus,
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step);

    @PostMapping(ROOT + "/{month}/{employeeCode}/confirm")
    ApiResponse<Void> confirmRecord(
            @PathVariable("month") String month,
            @PathVariable("employeeCode") String employeeCode,
            @RequestBody AttendanceRecordCommand command);

    @PostMapping(ROOT + "/{month}/{employeeCode}/reopen")
    ApiResponse<Void> reopenRecord(
            @PathVariable("month") String month,
            @PathVariable("employeeCode") String employeeCode,
            @RequestBody AttendanceReopenCommand command);
}
