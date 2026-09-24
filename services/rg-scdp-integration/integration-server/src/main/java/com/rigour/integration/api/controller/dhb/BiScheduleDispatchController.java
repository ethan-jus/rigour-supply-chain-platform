package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.BiScheduleDispatchApi;
import com.rigour.integration.application.service.dhb.BiSchedulePlanService;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
public class BiScheduleDispatchController implements BiScheduleDispatchApi {
    private static final UUID WORKER =
            UUID.nameUUIDFromBytes("rigour-bi-schedule-worker".getBytes(StandardCharsets.UTF_8));
    private final BiSchedulePlanService service;

    public BiScheduleDispatchController(BiSchedulePlanService service) {
        this.service = service;
    }

    private UUID authorize(boolean tenantRequired) {
        var a = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(a.principalScope())
                || !WORKER.equals(a.principalId())
                || (tenantRequired && a.tenantId() == null))
            throw new AuthorizationDeniedException("bi-schedule-worker");
        AuthorizationContext.requirePermission("integration:schedule:execute-bi");
        return a.tenantId();
    }

    public ApiResponse<List<Work>> due() {
        authorize(false);
        return ApiResponse.success(
                service.due().stream()
                        .map(e -> new Work(UUID.fromString(e.tenant()), e.plan().version()))
                        .toList());
    }

    public ApiResponse<Boolean> managed() {
        return ApiResponse.success(service.managed(authorize(true)));
    }

    public ApiResponse<Boolean> claim(Claim c) {
        return ApiResponse.success(service.claim(authorize(true), c.version(), c.token()));
    }

    public ApiResponse<Boolean> heartbeat(UUID token) {
        service.heartbeat(authorize(true), token);
        return ApiResponse.success(true);
    }

    public ApiResponse<Boolean> complete(UUID token, Completion c) {
        service.finish(authorize(true), token, c.status(), c.message());
        return ApiResponse.success(true);
    }
}
