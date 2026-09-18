package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CustomerOrderAttributionApi;
import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.merchant.application.service.CustomerOrderAttributionService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

/** 内部客户归属 HTTP 适配。 */
@RestController
public final class CustomerOrderAttributionController implements CustomerOrderAttributionApi {
    private final CustomerOrderAttributionService service;

    public CustomerOrderAttributionController(CustomerOrderAttributionService service) {
        this.service = service;
    }

    public ApiResponse<CustomerOrderAttributionView> attribution(long id) {
        return ApiResponse.success(service.read(id));
    }
}
