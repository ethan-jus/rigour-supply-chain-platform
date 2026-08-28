package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalStockInOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockInOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ERP 入库单持久化端口；入库单、采购回写、库存余额和库存流水必须同事务完成。 */
public interface ErpStockInOrderStore {
    MasterDataPageView<InternalStockInOrderSummaryView> stockInOrders(
            String tenantId, int begin, int step, StockInOrderSearchCriteria criteria);

    Optional<InternalStockInOrderDetailView> stockInOrder(String tenantId, Long id);

    Optional<ProcurementOrderSnapshot> procurementOrderForStockIn(String tenantId, Long procurementOrderId);

    boolean existsByStockInNo(String tenantId, String stockInNo);

    boolean existsByFlowNo(String tenantId, String flowNo);

    InternalStockInOrderDetailView confirmProcurementStockIn(
            String tenantId, String stockInNo, ProcurementStockInWrite command, String actorId);

    /** 入库单列表独立筛选条件。 */
    record StockInOrderSearchCriteria(
            String stockInNo,
            String stockInTypeCode,
            Long procurementOrderId,
            Long warehouseId,
            Long supplierId,
            String statusCode,
            Instant stockInTimeFrom,
            Instant stockInTimeTo) {
    }

    /** 采购订单快照；用于校验可入库数量并固化入库单来源。 */
    record ProcurementOrderSnapshot(
            Long id,
            String procurementNo,
            String sourceSystemCode,
            String sourceDocumentNo,
            Long supplierId,
            Long targetWarehouseId,
            String statusCode,
            Integer revision,
            List<ProcurementOrderLineSnapshot> lines) {
        public ProcurementOrderSnapshot {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 采购订单明细快照；商品编码、名称、单位来自采购下单时快照。 */
    record ProcurementOrderLineSnapshot(
            Long id,
            Integer lineNo,
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineAmount,
            BigDecimal receivedQuantity) {
    }

    /** 采购入库聚合写入模型；Service 已完成超收、重复行和状态流转校验。 */
    record ProcurementStockInWrite(
            Long procurementOrderId,
            Integer procurementRevision,
            String stockInTypeCode,
            String statusCode,
            String nextProcurementStatusCode,
            Instant stockInTime,
            Long warehouseId,
            Long supplierId,
            String procurementNo,
            List<ProcurementStockInLineWrite> lines,
            String remark) {
        public ProcurementStockInWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 采购入库明细写入模型，同时携带本次库存流水号。 */
    record ProcurementStockInLineWrite(
            Integer lineNo,
            Long procurementOrderLineId,
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            String flowNo,
            String remark) {
    }
}
