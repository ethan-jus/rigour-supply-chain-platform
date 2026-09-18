package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrOrganizationApi;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.HrOrganizationService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HR 部门/员工维护 HTTP 边界。 */
@RestController
public final class HrOrganizationController implements HrOrganizationApi {
    private final HrOrganizationService service;

    public HrOrganizationController(HrOrganizationService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<List<HrDepartmentView>> departments() {
        return ApiResponse.success(service.departments());
    }

    @Override
    public ApiResponse<HrDepartmentView> createDepartment(HrDepartmentCommand c) {
        return ApiResponse.success(service.saveDepartment(null, c));
    }

    @Override
    public ApiResponse<HrDepartmentView> updateDepartment(Long id, HrDepartmentCommand c) {
        return ApiResponse.success(service.saveDepartment(id, c));
    }

    @Override
    public ApiResponse<Void> deleteDepartment(Long id, int revision) {
        service.deleteDepartment(id, revision);
        return ApiResponse.success(null);
    }

    @Override
    public ApiResponse<HrEmployeeView> createEmployee(HrEmployeeCommand c) {
        return ApiResponse.success(service.saveEmployee(null, c));
    }

    @Override
    public ApiResponse<HrEmployeeView> updateEmployee(Long id, HrEmployeeCommand c) {
        return ApiResponse.success(service.saveEmployee(id, c));
    }

    @Override
    public ApiResponse<List<HrAssignmentView>> assignments(Long id) {
        return ApiResponse.success(service.assignments(id));
    }

    @Override
    public ApiResponse<HrPageView<HrEmployeeIdentityView>> identities(
            String key, int begin, int step) {
        return ApiResponse.success(service.identities(key, begin, step));
    }

    @Override
    public ApiResponse<HrEmployeeIdentityView> identity(String code) {
        return ApiResponse.success(service.identity(code));
    }

    @Override
    public ApiResponse<List<HrEmployeeIdentityView>> resolveIdentities(List<String> codes) {
        return ApiResponse.success(service.resolveIdentities(codes));
    }
}
