package com.rigour.hr.api.v1;

import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 历史业务归属最小投影；不表示员工当前可任用或可登录。 */
public interface HrHistoricalDepartmentApi {
    record Query(String employeeCode, LocalDate businessDate) {}

    record Attribution(
            String employeeCode,
            LocalDate businessDate,
            String employeeName,
            Long departmentId,
            String departmentName,
            String basis,
            String issue) {}

    @PostMapping("/api/v1/hr/employee-identities/department-attributions")
    ApiResponse<List<Attribution>> resolveDepartments(@RequestBody List<Query> queries);
}
