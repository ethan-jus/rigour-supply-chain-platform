package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.PaymentVoucherAuditModels.*;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

public interface PaymentVoucherAuditApi {
    String PATH = OrderRegisterApi.BASE_PATH + "/payments/voucher-audit";
    @GetMapping(PATH) ApiResponse<Scan> scan();
    @PostMapping(PATH + "/reviews") ApiResponse<Review> review(@RequestBody ReviewRequest request);
}
