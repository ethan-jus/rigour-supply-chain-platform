package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.CustomerPaymentOwnerView;
import com.rigour.merchant.application.port.out.CustomerPaymentOwnerStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/** 可信服务读取历史回款归属，不向浏览器开放任意租户查询。 */
@RestController
public class CustomerPaymentOwnerController {
    private final CustomerPaymentOwnerStore store;

    public CustomerPaymentOwnerController(CustomerPaymentOwnerStore store) {
        this.store = store;
    }

    @GetMapping("/internal/v1/crm/customers/{id}/payment-owner")
    public ApiResponse<CustomerPaymentOwnerView> owner(
            @PathVariable long id, @RequestParam Instant at) {
        var a = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission("crm:customer:attribution-read");
        if (!"SERVICE".equals(a.principalScope()))
            throw new AuthorizationDeniedException("service-caller-required");
        return ApiResponse.success(store.at(a.tenantId().toString(), id, at));
    }
}
