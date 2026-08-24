package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.FundDocumentSummaryView;
import com.rigour.order.api.v1.model.OrderPageView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Order 资金收付款单持久化端口；只操作自研资金单据表。 */
public interface OrderFundDocumentStore {
    OrderPageView<FundDocumentSummaryView> fundDocuments(
            String tenantId, int begin, int step, FundDocumentSearchCriteria criteria);

    Optional<FundDocumentDetailView> fundDocument(String tenantId, Long id);

    boolean existsByNo(String tenantId, String documentNo);

    FundDocumentDetailView create(String tenantId, String documentNo,
                                  FundDocumentWrite command, String actorId);

    FundDocumentDetailView update(String tenantId, Long id,
                                  FundDocumentWrite command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    record FundDocumentSearchCriteria(
            String directionCode,
            String documentNo,
            String salesOrderNo,
            String counterpartyName,
            String handlerStaffCode,
            String settlementMethodCode,
            String businessTypeCode,
            String documentStatusCode,
            Instant occurredTimeFrom,
            Instant occurredTimeTo) {
    }

    record FundDocumentWrite(
            String directionCode,
            Long relatedOrderId,
            String salesOrderNoSnapshot,
            Long customerId,
            String customerCodeSnapshot,
            String customerNameSnapshot,
            String counterpartyTypeCode,
            String counterpartyCodeSnapshot,
            String counterpartyNameSnapshot,
            String handlerStaffCode,
            String handlerStaffNameSnapshot,
            Instant occurredTime,
            String settlementMethodCode,
            String businessTypeCode,
            String documentStatusCode,
            BigDecimal amount,
            List<String> voucherKeys,
            String remark,
            Integer revision) {
        public FundDocumentWrite {
            voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
        }
    }
}
