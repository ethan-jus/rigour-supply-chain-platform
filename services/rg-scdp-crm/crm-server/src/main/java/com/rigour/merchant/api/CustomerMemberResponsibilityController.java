package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CustomerMemberResponsibilityApi;
import com.rigour.merchant.application.service.CustomerMemberResponsibilityService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public final class CustomerMemberResponsibilityController
        implements CustomerMemberResponsibilityApi {
    private final CustomerMemberResponsibilityService service;

    public CustomerMemberResponsibilityController(CustomerMemberResponsibilityService service) {
        this.service = service;
    }

    public ApiResponse<Page> customers(
            UUID userId,
            String mode,
            String keyword,
            String customerType,
            String regionCode,
            String status,
            int page,
            int size) {
        return ApiResponse.success(
                service.customers(
                        userId, mode, keyword, customerType, regionCode, status, page, size));
    }

    public ApiResponse<Preview> preview(UUID userId, PreviewCommand command) {
        return ApiResponse.success(service.preview(userId, command));
    }

    public ApiResponse<Applied> apply(UUID userId, ApplyCommand command) {
        return ApiResponse.success(service.apply(userId, command));
    }
}
