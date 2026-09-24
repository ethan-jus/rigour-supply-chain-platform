package com.rigour.integration.api.v1;

import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

public interface BiScheduleDispatchApi {
    String BASE = "/internal/v1/integration/sync-schedules/bi";

    record Work(UUID tenantId, long version) {}

    record Claim(long version, UUID token) {
        public Claim {
            if (token == null || version < 1) throw new IllegalArgumentException("无效派发请求");
        }
    }

    record Completion(String status, String message) {}

    @GetMapping(BASE + "/due")
    ApiResponse<List<Work>> due();

    @GetMapping(BASE + "/managed")
    ApiResponse<Boolean> managed();

    @PostMapping(BASE + "/claim")
    ApiResponse<Boolean> claim(@RequestBody Claim command);

    @PostMapping(BASE + "/{token}/heartbeat")
    ApiResponse<Boolean> heartbeat(@PathVariable("token") UUID token);

    @PostMapping(BASE + "/{token}/complete")
    ApiResponse<Boolean> complete(
            @PathVariable("token") UUID token, @RequestBody Completion command);
}
