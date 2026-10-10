package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.PaymentVoucherModels.TransactionMatch;
import com.rigour.order.api.v1.model.PaymentVoucherModels.VoucherTransaction;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

public interface PaymentVoucherApi {
    @GetMapping(OrderRegisterApi.BASE_PATH + "/payments/{id}/voucher-transactions")
    ApiResponse<List<VoucherTransaction>> vouchers(@PathVariable("id") long id);

    @GetMapping(OrderRegisterApi.BASE_PATH + "/payments/transaction-check")
    ApiResponse<List<TransactionMatch>> checkTransaction(@RequestParam String transactionNo);
}
