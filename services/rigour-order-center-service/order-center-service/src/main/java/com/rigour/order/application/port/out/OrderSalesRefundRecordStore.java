package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordSummaryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Order 销售退款记录持久化端口；只操作自研退款业务表。 */
public interface OrderSalesRefundRecordStore {
    OrderPageView<SalesRefundRecordSummaryView> refunds(
            String tenantId, int begin, int step, SalesRefundSearchCriteria criteria);

    Optional<SalesRefundRecordDetailView> refund(String tenantId, Long id);

    boolean existsByNo(String tenantId, String refundNo);

    SalesRefundRecordDetailView create(String tenantId, String refundNo,
                                       SalesRefundWrite command, String actorId);

    SalesRefundRecordDetailView update(String tenantId, Long id,
                                       SalesRefundWrite command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    record SalesRefundSearchCriteria(
            String refundNo,
            String salesOrderNo,
            String customerName,
            String refundStaffCode,
            String refundMethodCode,
            String refundStatusCode,
            Instant refundTimeFrom,
            Instant refundTimeTo) {
    }

    record SalesRefundWrite(
            Long orderId,
            String salesOrderNoSnapshot,
            Long customerId,
            String customerCodeSnapshot,
            String customerNameSnapshot,
            String refundStaffCode,
            String refundStaffNameSnapshot,
            Instant refundTime,
            String refundMethodCode,
            String refundStatusCode,
            BigDecimal refundAmount,
            List<String> voucherKeys,
            String remark,
            Integer revision) {
        public SalesRefundWrite {
            voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
        }
    }
}
