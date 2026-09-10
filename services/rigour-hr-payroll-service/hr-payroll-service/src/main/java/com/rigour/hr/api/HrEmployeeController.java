package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrEmployeeApi;
import com.rigour.hr.api.v1.model.HrEmployeeView;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.application.service.HrEmployeeService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** HR 员工主档 HTTP 边界。 */
@RestController
public final class HrEmployeeController implements HrEmployeeApi {
    private final HrEmployeeService service;

    public HrEmployeeController(HrEmployeeService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<HrPageView<HrEmployeeView>> employees(
            int begin, int step, String keyword, String employeeCode, String employeeName,
            String mobile, String employmentStatus, String jobCategory, String positionName,
            String regionName, String cityName, String sourceSystem) {
        return ApiResponse.success(service.employees(begin, step, keyword, employeeCode, employeeName,
                mobile, employmentStatus, jobCategory, positionName, regionName, cityName, sourceSystem));
    }

    @Override
    public ApiResponse<HrEmployeeView> employee(Long id) {
        return ApiResponse.success(service.employee(id));
    }
}
