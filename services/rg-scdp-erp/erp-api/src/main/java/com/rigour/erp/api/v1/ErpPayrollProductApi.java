package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.ErpPayrollCategoryView;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface ErpPayrollProductApi {
    @GetMapping("/api/v1/erp/payroll-products")
    ApiResponse<List<ErpPayrollProductView>> products(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long productVariantId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int begin);

    default ApiResponse<List<ErpPayrollProductView>> products(
            String keyword, Long productVariantId, Long categoryId) {
        return products(keyword, productVariantId, categoryId, 0);
    }

    @GetMapping("/api/v1/erp/payroll-products/by-variants")
    ApiResponse<List<ErpPayrollProductView>> productsByVariants(@RequestParam List<Long> ids);

    @GetMapping("/api/v1/erp/payroll-products/categories")
    ApiResponse<List<ErpPayrollCategoryView>> categories();
}
