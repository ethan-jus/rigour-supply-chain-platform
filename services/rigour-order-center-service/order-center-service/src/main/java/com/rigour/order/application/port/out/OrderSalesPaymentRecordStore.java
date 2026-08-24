package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordSummaryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Order 销售回款记录持久化端口；只操作自研回款业务表。 */
public interface OrderSalesPaymentRecordStore {
    OrderPageView<SalesPaymentRecordSummaryView> payments(
            String tenantId, int begin, int step, SalesPaymentSearchCriteria criteria);

    Optional<SalesPaymentRecordDetailView> payment(String tenantId, Long id);

    boolean existsByNo(String tenantId, String paymentNo);

    SalesPaymentRecordDetailView create(String tenantId, String paymentNo,
                                        SalesPaymentWrite command, String actorId);

    SalesPaymentRecordDetailView update(String tenantId, Long id,
                                        SalesPaymentWrite command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    record SalesPaymentSearchCriteria(
            String paymentNo,
            String salesOrderNo,
            String customerName,
            String collectorStaffCode,
            String paymentMethodCode,
            Instant paymentTimeFrom,
            Instant paymentTimeTo) {
    }

    record SalesPaymentWrite(
            Long orderId,
            String salesOrderNoSnapshot,
            Long customerId,
            String customerCodeSnapshot,
            String customerNameSnapshot,
            String collectorStaffCode,
            String collectorNameSnapshot,
            Instant paymentTime,
            String paymentMethodCode,
            BigDecimal paidAmount,
            List<String> voucherKeys,
            String remark,
            Integer revision) {
        public SalesPaymentWrite {
            voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
        }
    }
}
