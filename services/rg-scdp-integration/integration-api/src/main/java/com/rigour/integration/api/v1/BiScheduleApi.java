package com.rigour.integration.api.v1;

import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.scheduling.*;

import org.springframework.web.bind.annotation.*;

public interface BiScheduleApi {
    @GetMapping("/api/v1/integration/sync-schedules/bi")
    ApiResponse<ScheduleView> get();

    @PutMapping("/api/v1/integration/sync-schedules/bi")
    ApiResponse<ScheduleView> save(@RequestBody SaveScheduleCommand command);
}
