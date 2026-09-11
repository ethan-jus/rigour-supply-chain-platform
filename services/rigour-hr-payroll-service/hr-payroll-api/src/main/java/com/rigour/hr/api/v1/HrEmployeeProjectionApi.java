package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.ExternalEmployeeSyncCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** HR 内部投影接口；Integration 通过可信上下文把外部人员资料写入 HR。 */
public interface HrEmployeeProjectionApi {
    String BASE_PATH = "/internal/v1/hr/employees";

    @PostMapping(BASE_PATH + "/external-sync")
    ApiResponse<ExternalEmployeeSyncResult> syncExternalEmployees(
            @RequestBody ExternalEmployeeSyncCommand command);

    @PostMapping(BASE_PATH + "/source-resolve")
    ApiResponse<List<ExternalEmployeeResolvedView>> resolveExternalEmployees(
            @RequestBody ExternalEmployeeResolveCommand command);
}
