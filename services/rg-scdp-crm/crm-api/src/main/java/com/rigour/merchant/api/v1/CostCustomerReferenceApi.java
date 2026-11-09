package com.rigour.merchant.api.v1;

import com.rigour.merchant.api.v1.model.CostCustomerView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

public interface CostCustomerReferenceApi {
    @PostMapping("/api/v1/crm/cost-customer-references/batch")
    ApiResponse<List<CostCustomerView>> customersByCodes(
            @RequestParam String cityCode,
            @RequestParam String action,
            @RequestBody List<String> codes);

    @GetMapping("/api/v1/crm/cost-customer-references")
    ApiResponse<List<CostCustomerView>> customers(
            @RequestParam String cityCode,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long id,
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "order:logistics-cost:read") String action);
}
