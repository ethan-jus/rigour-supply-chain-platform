package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrAttendanceApi;
import com.rigour.hr.api.v1.model.AttendanceDepartmentOption;
import com.rigour.hr.api.v1.model.AttendanceRecordCommand;
import com.rigour.hr.api.v1.model.AttendanceReopenCommand;
import com.rigour.hr.api.v1.model.AttendanceRuleCommand;
import com.rigour.hr.api.v1.model.AttendanceRuleListView;
import com.rigour.hr.api.v1.model.AttendanceRuleView;
import com.rigour.hr.api.v1.model.AttendanceView;
import com.rigour.hr.application.service.HrAttendanceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HrAttendanceController implements HrAttendanceApi {
    private final HrAttendanceService service;

    public HrAttendanceController(HrAttendanceService service) {
        this.service = service;
    }

    public ApiResponse<AttendanceRuleView> rule(String month, Long departmentId) {
        return ApiResponse.success(service.rule(month, departmentId));
    }

    public ApiResponse<AttendanceRuleListView> rules(
            String month, Long departmentId, String status, int begin, int step) {
        return ApiResponse.success(service.rules(month, departmentId, status, begin, step));
    }

    public ApiResponse<AttendanceRuleView> saveRule(AttendanceRuleCommand command) {
        return ApiResponse.success(service.saveRule(command));
    }

    public ApiResponse<List<AttendanceDepartmentOption>> departments(boolean forRules) {
        return ApiResponse.success(service.departments(forRules));
    }

    public ApiResponse<AttendanceView> list(
            String month,
            String employeeName,
            String employeeCode,
            Long departmentId,
            String attendanceStatus,
            int begin,
            int step) {
        return ApiResponse.success(
                service.list(
                        month,
                        employeeName,
                        employeeCode,
                        departmentId,
                        attendanceStatus,
                        begin,
                        step));
    }

    public ApiResponse<Void> confirmRecord(
            String month, String employeeCode, AttendanceRecordCommand command) {
        service.confirmRecord(month, employeeCode, command);
        return ApiResponse.success(null);
    }

    public ApiResponse<Void> reopenRecord(
            String month, String employeeCode, AttendanceReopenCommand command) {
        service.reopenRecord(month, employeeCode, command);
        return ApiResponse.success(null);
    }
}
