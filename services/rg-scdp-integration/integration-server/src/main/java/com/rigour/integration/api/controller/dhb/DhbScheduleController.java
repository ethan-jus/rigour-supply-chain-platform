package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbScheduleApi;
import com.rigour.integration.application.service.dhb.DhbScheduleService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.scheduling.*;

import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class DhbScheduleController implements DhbScheduleApi {
    private final DhbScheduleService service;

    public DhbScheduleController(DhbScheduleService service) {
        this.service = service;
    }

    public ApiResponse<ScheduleView> get(UUID connectorId) {
        return ApiResponse.success(service.get(AuthorizationContext.requireCurrent(), connectorId));
    }

    public ApiResponse<ScheduleView> save(UUID connectorId, SaveScheduleCommand command) {
        return ApiResponse.success(
                service.save(AuthorizationContext.requireCurrent(), connectorId, command));
    }
}
