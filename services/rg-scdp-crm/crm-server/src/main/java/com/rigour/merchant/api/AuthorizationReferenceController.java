package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.AuthorizationReferenceApi;
import com.rigour.merchant.api.v1.model.AuthorizationReferenceView;
import com.rigour.merchant.application.service.AuthorizationReferenceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class AuthorizationReferenceController implements AuthorizationReferenceApi {
    private final AuthorizationReferenceService service;

    public AuthorizationReferenceController(AuthorizationReferenceService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<List<AuthorizationReferenceView>> references() {
        return ApiResponse.success(service.references());
    }
}
