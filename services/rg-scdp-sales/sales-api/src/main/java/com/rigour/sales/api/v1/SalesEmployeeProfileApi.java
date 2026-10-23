package com.rigour.sales.api.v1;

import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** 本人销售档案；身份全部由 IAM/HR 核验，不接受员工、账号或租户参数。 */
public interface SalesEmployeeProfileApi {
    String PATH = "/api/v1/sales/me/profile";

    @GetMapping(PATH)
    ApiResponse<ProfileView> profile();

    @PostMapping(PATH)
    ApiResponse<ProfileView> register();

    record ProfileView(String hrEmployeeId, String employeeCode, String employeeName,
                       UUID salesProfileId, String status) {}
}
