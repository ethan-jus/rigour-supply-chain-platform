package com.rigour.hr.api.controller;

import com.rigour.hr.api.v1.HrTargetSettingsApi;
import com.rigour.hr.api.v1.model.TargetSettingsModels.*;
import com.rigour.hr.application.service.TargetSettingsService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HrTargetSettingsController implements HrTargetSettingsApi {
    private final TargetSettingsService service;

    public HrTargetSettingsController(TargetSettingsService service) {
        this.service = service;
    }

    public ApiResponse<Settings> settings(String month) {
        return ApiResponse.success(service.settings(month));
    }

    public ApiResponse<Void> save(Batch command) {
        service.save(command);
        return ApiResponse.success(null);
    }

    public ApiResponse<List<Target>> values(String from, String to) {
        return ApiResponse.success(service.values(from, to));
    }

    public ApiResponse<List<History>> history(String month, String dimensionType, String code) {
        return ApiResponse.success(service.history(month, dimensionType, code));
    }
}
