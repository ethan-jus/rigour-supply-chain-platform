package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CustomerResponsibilityApi;
import com.rigour.merchant.application.service.CustomerResponsibilityService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class CustomerResponsibilityController implements CustomerResponsibilityApi {
    private final CustomerResponsibilityService service;

    public CustomerResponsibilityController(CustomerResponsibilityService service) {
        this.service = service;
    }

    public ApiResponse<List<Employee>> employees(String keyword) {
        return ApiResponse.success(service.employees(keyword));
    }

    public ApiResponse<Overview> overview(long id) {
        return ApiResponse.success(service.overview(id));
    }

    public ApiResponse<Overview> transfer(long id, Change c) {
        return ApiResponse.success(service.transfer(id, c));
    }

    public ApiResponse<Overview> resolve(long id, long conflictId, Resolution c) {
        return ApiResponse.success(service.resolve(id, conflictId, c));
    }
}
