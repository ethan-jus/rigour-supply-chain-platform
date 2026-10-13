package com.rigour.sales.api.v1;

import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

/** HR 核算读取已关联员工的拜访证据，不暴露客户联系方式和录音文件。 */
public interface SalesPayrollSourceApi {

    @GetMapping("/api/v1/sales/payroll-evidence")
    ApiResponse<SalesPayrollEvidenceView> evidence(
            @RequestParam String employeeCode,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to);
}
