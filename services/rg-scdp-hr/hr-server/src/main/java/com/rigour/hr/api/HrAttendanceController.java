package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrAttendanceApi;
import com.rigour.hr.api.v1.model.HrAttendanceModels.*;
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

    public ApiResponse<Rule> rule(String month, Long departmentId) {
        return ApiResponse.success(service.rule(month, departmentId));
    }

    public ApiResponse<RuleListView> rules(
            String month, Long departmentId, String status, int begin, int step) {
        return ApiResponse.success(service.rules(month, departmentId, status, begin, step));
    }

    public ApiResponse<Rule> saveRule(RuleCommand command) {
        return ApiResponse.success(service.saveRule(command));
    }

    public ApiResponse<List<DepartmentOption>> departments() {
        return ApiResponse.success(service.departments());
    }

    public ApiResponse<View> list(
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

    public ApiResponse<Void> confirmRecord(String month, String employee, RecordCommand command) {
        service.confirmRecord(month, employee, command);
        return ApiResponse.success(null);
    }
}
