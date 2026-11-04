package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.PayrollPerformanceExportView;
import com.rigour.hr.api.v1.model.PayrollQuery;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

/** 只读业绩导出，人员范围与工资单导出一致。 */
public interface HrPayrollPerformanceExportApi {
    @PostMapping(HrPayrollApi.BASE_PATH + "/performance-export")
    ApiResponse<List<PayrollPerformanceExportView>> performanceExport(@RequestBody PayrollQuery query);

    @PostMapping(HrPayrollApi.BASE_PATH + "/commission-review")
    ApiResponse<List<com.rigour.hr.api.v1.model.PayrollCommissionReviewView>> commissionReview(@RequestBody PayrollQuery query);
}
