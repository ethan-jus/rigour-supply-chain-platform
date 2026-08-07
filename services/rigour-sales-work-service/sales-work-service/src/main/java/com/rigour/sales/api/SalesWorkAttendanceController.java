package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkApi;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.InterruptionCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchResult;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayView;
import com.rigour.sales.application.service.SalesWorkAttendanceService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 阶段 2 外勤事实写入和本人工作日查询 API。 */
@RestController
public final class SalesWorkAttendanceController {

    private final SalesWorkAttendanceService service;

    public SalesWorkAttendanceController(SalesWorkAttendanceService service) {
        this.service = service;
    }

    @PostMapping(SalesWorkApi.CHECK_IN_PATH)
    public ApiResponse<WorkDayView> checkIn(@RequestBody CheckInCommand command) {
        return ApiResponse.success(service.checkIn(command));
    }

    @PostMapping(SalesWorkApi.LOCATION_BATCH_PATH)
    public ApiResponse<LocationBatchResult> uploadLocationPoints(
            @PathVariable("workDayId") UUID workDayId, @RequestBody LocationBatchCommand command) {
        return ApiResponse.success(service.uploadLocationPoints(workDayId, command));
    }

    @PostMapping(SalesWorkApi.CHECK_OUT_PATH)
    public ApiResponse<WorkDayView> checkOut(
            @PathVariable("workDayId") UUID workDayId, @RequestBody CheckOutCommand command) {
        return ApiResponse.success(service.checkOut(workDayId, command));
    }

    @PostMapping(SalesWorkApi.INTERRUPTION_PATH)
    public ApiResponse<WorkDayView> reportInterruption(
            @PathVariable("workDayId") UUID workDayId, @RequestBody InterruptionCommand command) {
        return ApiResponse.success(service.reportInterruption(workDayId, command));
    }

    @GetMapping(SalesWorkApi.WORK_DAY_PATH)
    public ApiResponse<WorkDayView> workDay(@PathVariable("date") LocalDate date) {
        return ApiResponse.success(service.workDay(date));
    }
}
