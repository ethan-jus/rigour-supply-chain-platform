package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.HrEmployeeView;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** HR 员工主档接口；员工身份归 HR 管理，IAM 只负责账号、角色与权限。 */
public interface HrEmployeeApi {
    String BASE_PATH = "/api/v1/hr/employees";

    @GetMapping(BASE_PATH)
    ApiResponse<HrPageView<HrEmployeeView>> employees(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String employeeCode,
            @RequestParam(required = false) String employeeName,
            @RequestParam(required = false) String mobile,
            @RequestParam(required = false) String employmentStatus,
            @RequestParam(required = false) String jobCategory,
            @RequestParam(required = false) String positionName,
            @RequestParam(required = false) String regionName,
            @RequestParam(required = false) String cityName,
            @RequestParam(required = false) String sourceSystem);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<HrEmployeeView> employee(@PathVariable("id") Long id);
}
