package com.rigour.merchant.api.v1;

import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

/** 内部归属读取接口，禁止把请求人或来源自由文本当作主责。 */
public interface CustomerOrderAttributionApi {
    @GetMapping("/internal/v1/crm/customers/{id}/order-attribution")
    ApiResponse<CustomerOrderAttributionView> attribution(@PathVariable long id);
}
