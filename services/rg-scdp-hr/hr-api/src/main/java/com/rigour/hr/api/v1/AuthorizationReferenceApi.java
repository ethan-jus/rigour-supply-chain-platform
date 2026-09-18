package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.AuthorizationReferenceView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

public interface AuthorizationReferenceApi {
    @GetMapping("/api/v1/hr/authorization/references")
    ApiResponse<List<AuthorizationReferenceView>> references();
}
