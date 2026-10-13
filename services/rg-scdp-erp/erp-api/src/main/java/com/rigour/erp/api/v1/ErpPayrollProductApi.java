package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface ErpPayrollProductApi {
    @GetMapping("/api/v1/erp/payroll-products")
    ApiResponse<List<ErpPayrollProductView>> products(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long productVariantId);
}
