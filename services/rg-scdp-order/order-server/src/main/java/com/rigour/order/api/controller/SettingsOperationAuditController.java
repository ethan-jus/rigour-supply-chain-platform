package com.rigour.order.api.controller;

import com.rigour.order.api.v1.SettingsOperationAuditApi;
import com.rigour.order.application.service.SettingsOperationAuditService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SettingsOperationAuditController implements SettingsOperationAuditApi {
    private final SettingsOperationAuditService service;

    public SettingsOperationAuditController(SettingsOperationAuditService service) {
        this.service = service;
    }

    public ApiResponse<Page> audits(String action, String keyword, int page, int pageSize) {
        return ApiResponse.success(service.audits(action, keyword, page, pageSize));
    }
}
