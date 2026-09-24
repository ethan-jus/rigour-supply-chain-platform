package com.rigour.integration.api.v1;

import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.scheduling.*;

import org.springframework.web.bind.annotation.*;

public interface DhbScheduleApi {
    @GetMapping("/api/v1/integration/dhb/schedules/{connectorId}")
    ApiResponse<ScheduleView> get(@PathVariable("connectorId") java.util.UUID connectorId);

    @PutMapping("/api/v1/integration/dhb/schedules/{connectorId}")
    ApiResponse<ScheduleView> save(
            @PathVariable("connectorId") java.util.UUID connectorId,
            @RequestBody SaveScheduleCommand command);
}
