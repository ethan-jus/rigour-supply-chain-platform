package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.PaymentVoucherModels.TransactionMatch;
import com.rigour.order.api.v1.model.PaymentVoucherModels.VoucherTransaction;
import java.util.List;

public interface PaymentVoucherStore {
    default List<String> attachmentKeys(String tenantId, long paymentId) { return List.of(); }
    List<VoucherTransaction> vouchers(String tenantId, long paymentId);
    List<TransactionMatch> transactionMatches(String tenantId, String transactionNo);
}
