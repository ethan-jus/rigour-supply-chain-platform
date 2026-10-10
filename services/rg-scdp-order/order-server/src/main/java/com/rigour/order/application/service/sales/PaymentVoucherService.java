package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.PaymentVoucherModels.TransactionMatch;
import com.rigour.order.api.v1.model.PaymentVoucherModels.VoucherTransaction;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.application.port.out.PaymentVoucherStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PaymentVoucherService {
    private final PaymentVoucherStore store;
    private final FundAttachmentUrlResolver urls;
    public PaymentVoucherService(PaymentVoucherStore store, ObjectProvider<FundAttachmentUrlResolver> urls) {
        this.store = store;
        this.urls = urls.getIfAvailable(() -> FundAttachmentUrlResolver.NONE);
    }
    public List<VoucherTransaction> vouchers(long id) {
        String tenant = tenant();
        if (id < 1) throw badRequest("回款ID无效");
        var byKey = new java.util.LinkedHashMap<String, VoucherTransaction>();
        var known = store.vouchers(tenant, id);
        for (var key : store.attachmentKeys(tenant, id)) byKey.put(key,
                known.stream().filter(v -> v.voucherKey().equals(key)).findFirst()
                    .orElse(new VoucherTransaction(key, null, null, "尚无可确认的逐图识别结果，请核对原图", null)));
        return byKey.values().stream().map(v -> new VoucherTransaction(
                v.voucherKey(), v.voucherAmount(), v.transactionNo(), v.evidenceNote(),
                urls.temporaryUrl(tenant, v.voucherKey()))).toList();
    }
    public List<TransactionMatch> checkTransaction(String transactionNo) {
        String tenant = tenant();
        String value = transactionNo == null ? "" : transactionNo.trim();
        if (value.isEmpty() || value.length() > 128) throw badRequest("请输入不超过128字符的完整交易单号");
        return store.transactionMatches(tenant, value);
    }
    private static String tenant() {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission("order:read");
        return caller.tenantId().toString();
    }
    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
