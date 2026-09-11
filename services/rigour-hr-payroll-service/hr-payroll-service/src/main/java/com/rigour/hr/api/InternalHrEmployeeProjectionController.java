package com.rigour.hr.api;

import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.HrEmployeeProjectionApi;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.application.service.HrEmployeeService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/** HR 内部员工投影 HTTP 边界。 */
@RestController
public final class InternalHrEmployeeProjectionController implements HrEmployeeProjectionApi {
    private final HrEmployeeService service;

    public InternalHrEmployeeProjectionController(HrEmployeeService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<ExternalEmployeeSyncResult> syncExternalEmployees(ExternalEmployeeSyncCommand command) {
        return ApiResponse.success(service.syncExternalEmployees(command));
    }

    @Override
    public ApiResponse<List<ExternalEmployeeResolvedView>> resolveExternalEmployees(
            ExternalEmployeeResolveCommand command) {
        return ApiResponse.success(service.resolveExternalEmployees(command));
    }
}
