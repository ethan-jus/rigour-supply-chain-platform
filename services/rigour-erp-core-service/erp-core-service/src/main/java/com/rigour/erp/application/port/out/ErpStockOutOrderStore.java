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

    Optional<InternalStockOutOrderDetailView> stockOutOrderBySource(
            String tenantId, String sourceSystemCode, String sourceDocumentNo);

    boolean existsByStockOutNo(String tenantId, String stockOutNo);

    boolean existsByFlowNo(String tenantId, String flowNo);

    boolean existsActiveSalesStockOut(String tenantId, Long salesOrderId);

    boolean warehouseActive(String tenantId, Long warehouseId);

    Optional<ProductVariantSnapshot> productVariant(String tenantId, Long productId, Long productVariantId);

    InternalStockOutOrderDetailView confirmSalesStockOut(
            String tenantId, String stockOutNo, SalesStockOutWrite command, String actorId);

    InternalStockOutOrderDetailView confirmExternalStockOut(
            String tenantId, String stockOutNo, ExternalStockOutWrite command, String actorId);

    InternalStockOutOrderDetailView confirmExternalGenericStockOut(
            String tenantId, String stockOutNo, ExternalGenericStockOutWrite command, String actorId);

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

    /** 商品规格快照；写入出库明细时固化。 */
    record ProductVariantSnapshot(
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode) {
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

    /** 外部来源出库写入模型；按 sourceSystemCode + sourceDocumentNo 做幂等。 */
    record ExternalStockOutWrite(
            String sourceSystemCode,
            String sourceDocumentNo,
            Long salesOrderId,
            String salesOrderNo,
            Long transferOrderId,
            String transferOrderNo,
            Long warehouseId,
            Long customerId,
            String customerNameSnapshot,
            String stockOutTypeCode,
            String statusCode,
            Instant stockOutTime,
            List<ExternalStockOutLineWrite> lines,
            String remark) {
        public ExternalStockOutWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 外部来源出库明细写入模型，同时携带库存流水号。 */
    record ExternalStockOutLineWrite(
            Integer lineNo,
            Long salesOrderLineId,
            Long transferOrderLineId,
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

    /** 外部来源通用出库写入模型；不关联销售订单或调拨单。 */
    record ExternalGenericStockOutWrite(
            String sourceSystemCode,
            String sourceDocumentNo,
            Long warehouseId,
            String stockOutTypeCode,
            String statusCode,
            Instant stockOutTime,
            List<ExternalGenericStockOutLineWrite> lines,
            String remark) {
        public ExternalGenericStockOutWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 外部来源通用出库明细写入模型，同时携带库存流水号。 */
    record ExternalGenericStockOutLineWrite(
            Integer lineNo,
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
