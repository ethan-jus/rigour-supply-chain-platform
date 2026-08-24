package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockOutOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ERP 出库单持久化端口；销售出库需要同事务写出库单、库存余额和库存流水。 */
public interface ErpStockOutOrderStore {
    MasterDataPageView<InternalStockOutOrderSummaryView> stockOutOrders(
            String tenantId, int begin, int step, StockOutOrderSearchCriteria criteria);

    Optional<InternalStockOutOrderDetailView> stockOutOrder(String tenantId, Long id);

    boolean existsByStockOutNo(String tenantId, String stockOutNo);

    boolean existsByFlowNo(String tenantId, String flowNo);

    boolean existsActiveSalesStockOut(String tenantId, Long salesOrderId);

    boolean warehouseActive(String tenantId, Long warehouseId);

    InternalStockOutOrderDetailView confirmSalesStockOut(
            String tenantId, String stockOutNo, SalesStockOutWrite command, String actorId);

    /** 出库单列表独立筛选条件。 */
    record StockOutOrderSearchCriteria(
            String stockOutNo,
            String stockOutTypeCode,
            Long warehouseId,
            String salesOrderNo,
            String transferOrderNo,
            String customerName,
            String statusCode,
            Instant stockOutTimeFrom,
            Instant stockOutTimeTo) {
    }

    /** 销售出库确认写入模型；使用统一出库单，按 stockOutTypeCode 区分出库类型。 */
    record SalesStockOutWrite(
            Long salesOrderId,
            String salesOrderNo,
            Long warehouseId,
            Long customerId,
            String customerNameSnapshot,
            String stockOutTypeCode,
            String statusCode,
            Instant stockOutTime,
            List<SalesStockOutLineWrite> lines,
            String remark) {
        public SalesStockOutWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 销售出库明细写入模型，同时携带本次库存流水号。 */
    record SalesStockOutLineWrite(
            Integer lineNo,
            Long salesOrderLineId,
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal quantity,
            String flowNo,
            String remark) {
    }
}
