package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrPositionApi;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.HrPositionCommand;
import com.rigour.hr.api.v1.model.HrPositionView;
import com.rigour.hr.application.service.HrPositionService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** HR 岗位职位 HTTP 边界。 */
@RestController
public final class HrPositionController implements HrPositionApi {
    private final HrPositionService service;

    public HrPositionController(HrPositionService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<HrPageView<HrPositionView>> positions(
            int begin, int step, String positionCode, String positionName,
            String positionType, String statusCode, String sourceSystem) {
        return ApiResponse.success(service.positions(
                begin, step, positionCode, positionName, positionType, statusCode, sourceSystem));
    }

    @Override
    public ApiResponse<HrPositionView> position(Long id) {
        return ApiResponse.success(service.position(id));
    }

    @Override
    public ApiResponse<HrPositionView> create(HrPositionCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    public ApiResponse<HrPositionView> update(Long id, HrPositionCommand command) {
        return ApiResponse.success(service.update(id, command));
    }

    @Override
    public ApiResponse<Void> delete(Long id, int revision) {
        service.delete(id, revision);
        return ApiResponse.success(null);
    }
}
