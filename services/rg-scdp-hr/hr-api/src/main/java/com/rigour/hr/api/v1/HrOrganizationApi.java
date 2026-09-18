package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** HR 部门与人工员工维护；员工身份最小投影用于跨服务核验。 */
public interface HrOrganizationApi {
    @GetMapping("/api/v1/hr/departments")
    ApiResponse<List<HrDepartmentView>> departments();

    @PostMapping("/api/v1/hr/departments")
    ApiResponse<HrDepartmentView> createDepartment(@RequestBody HrDepartmentCommand command);

    @PutMapping("/api/v1/hr/departments/{id}")
    ApiResponse<HrDepartmentView> updateDepartment(
            @PathVariable("id") Long id, @RequestBody HrDepartmentCommand command);

    @DeleteMapping("/api/v1/hr/departments/{id}")
    ApiResponse<Void> deleteDepartment(
            @PathVariable("id") Long id, @RequestParam("revision") int revision);

    @PostMapping("/api/v1/hr/employees")
    ApiResponse<HrEmployeeView> createEmployee(@RequestBody HrEmployeeCommand command);

    @PutMapping("/api/v1/hr/employees/{id}")
    ApiResponse<HrEmployeeView> updateEmployee(
            @PathVariable("id") Long id, @RequestBody HrEmployeeCommand command);

    @GetMapping("/api/v1/hr/employees/{id}/assignments")
    ApiResponse<List<HrAssignmentView>> assignments(@PathVariable("id") Long id);

    @GetMapping("/api/v1/hr/employee-identities")
    ApiResponse<HrPageView<HrEmployeeIdentityView>> identities(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "begin", defaultValue = "0") int begin,
            @RequestParam(name = "step", defaultValue = "20") int step);

    @GetMapping("/api/v1/hr/employee-identities/{employeeCode}")
    ApiResponse<HrEmployeeIdentityView> identity(@PathVariable("employeeCode") String employeeCode);

    @PostMapping("/api/v1/hr/employee-identities/resolve")
    ApiResponse<List<HrEmployeeIdentityView>> resolveIdentities(@RequestBody List<String> codes);
}
