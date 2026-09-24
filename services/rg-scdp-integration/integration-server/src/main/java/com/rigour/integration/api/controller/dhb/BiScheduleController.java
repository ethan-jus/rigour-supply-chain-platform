package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.BiScheduleApi;
import com.rigour.integration.application.service.dhb.BiSchedulePlanService;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.scheduling.*;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class BiScheduleController implements BiScheduleApi {
    private final BiSchedulePlanService service;

    public BiScheduleController(BiSchedulePlanService service) {
        this.service = service;
    }

    public ApiResponse<ScheduleView> get() {
        return ApiResponse.success(service.get());
    }

    public ApiResponse<ScheduleView> save(SaveScheduleCommand command) {
        return ApiResponse.success(service.save(command));
    }
}
