package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpPayrollProductApi;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.erp.application.service.product.ErpPayrollProductService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class ErpPayrollProductController implements ErpPayrollProductApi {
    private final ErpPayrollProductService service;

    public ErpPayrollProductController(ErpPayrollProductService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<List<ErpPayrollProductView>> products(
            String keyword, Long productVariantId) {
        return ApiResponse.success(service.products(keyword, productVariantId));
    }
}
