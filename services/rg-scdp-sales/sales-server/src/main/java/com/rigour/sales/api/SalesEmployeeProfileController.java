package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesEmployeeProfileApi;
import com.rigour.sales.application.service.SalesEmployeeProfileService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SalesEmployeeProfileController implements SalesEmployeeProfileApi {
    private final SalesEmployeeProfileService service;
    public SalesEmployeeProfileController(SalesEmployeeProfileService service) { this.service = service; }
    @Override public ApiResponse<ProfileView> profile() { return ApiResponse.success(service.profile()); }
    @Override public ApiResponse<ProfileView> register() { return ApiResponse.success(service.register()); }
}
