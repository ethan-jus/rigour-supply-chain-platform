package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.PaymentVoucherAuditModels.*;
import java.util.List;
import java.util.Map;

public interface PaymentVoucherAuditStore {
    List<Payment> payments(String tenant, String action);
    Map<String, List<Review>> reviews(String tenant);
    Review appendReview(String tenant, String groupKey, String fingerprint, String conclusion,
            String note, String actor, List<String> paymentIds);
    default Review appendReview(String tenant, String groupKey, String fingerprint, String conclusion,
            String note, String actor, List<String> paymentIds, List<String> reasonCodes) {
        return appendReview(tenant, groupKey, fingerprint, conclusion, note, actor, paymentIds);
    }
}
