package com.rigour.integration.api.controller.thirdparty;

import com.rigour.integration.application.service.thirdparty.oauth.ThirdPartyAuthorizationService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/integration/thirdparty/{platform}/authorizations")
public final class ThirdPartyAuthorizationController {
    private final ThirdPartyAuthorizationService service;

    public ThirdPartyAuthorizationController(ThirdPartyAuthorizationService service) {
        this.service = service;
    }

    public record Create(UUID connectorId) {}

    @PostMapping
    public ApiResponse<ThirdPartyAuthorizationService.Start> create(
            @PathVariable String platform, @RequestBody Create c) {
        if (c == null || c.connectorId() == null) throw new IllegalArgumentException("请选择平台连接");
        return ApiResponse.success(service.create(platform, c.connectorId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ThirdPartyAuthorizationService.Status> status(
            @PathVariable String platform, @PathVariable UUID id) {
        return ApiResponse.success(service.status(platform, id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String platform, @PathVariable UUID id) {
        service.cancel(platform, id);
        return ApiResponse.success(null);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(RuntimeException failure) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("OAUTH_REQUEST", failure.getMessage(), List.of()));
    }
}
