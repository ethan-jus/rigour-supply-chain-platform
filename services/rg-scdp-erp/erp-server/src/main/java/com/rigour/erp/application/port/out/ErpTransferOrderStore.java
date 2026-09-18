package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ERP 调拨单持久化端口；调拨出入库需要同事务写统一出入库单、库存余额和库存流水。 */
public interface ErpTransferOrderStore {
    MasterDataPageView<InternalTransferOrderSummaryView> transferOrders(
            String tenantId, int begin, int step, TransferOrderSearchCriteria criteria);

    Optional<InternalTransferOrderDetailView> transferOrder(String tenantId, Long id);

    Optional<InternalTransferOrderDetailView> transferOrderBySource(
            String tenantId, String connectorId, String sourceSystemCode, String sourceDocumentNo);

    boolean existsByTransferNo(String tenantId, String transferNo);

    boolean existsByStockOutNo(String tenantId, String stockOutNo);

    boolean existsByStockInNo(String tenantId, String stockInNo);

    boolean existsByFlowNo(String tenantId, String flowNo);

    boolean warehouseActive(String tenantId, Long warehouseId);

    Optional<ProductVariantSnapshot> productVariant(String tenantId, Long productId, Long productVariantId);

    Optional<TransferOrderSnapshot> transferOrderForStockOut(String tenantId, Long transferOrderId);

    Optional<TransferOrderSnapshot> transferOrderForStockIn(String tenantId, Long transferOrderId);

    InternalTransferOrderDetailView create(
            String tenantId, String transferNo, TransferOrderWrite command, String actorId);

    InternalTransferOrderDetailView update(
            String tenantId, Long id, TransferOrderWrite command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    InternalTransferOrderDetailView confirmStockOut(
            String tenantId, String stockOutNo, TransferStockOutWrite command, String actorId);

    InternalTransferOrderDetailView confirmExternalStockOut(
            String tenantId, String transferNo, String stockOutNo,
            ExternalTransferStockOutWrite command, String actorId);

    InternalTransferOrderDetailView confirmStockIn(
            String tenantId, String stockInNo, TransferStockInWrite command, String actorId);

    /** 调拨单列表独立筛选条件。 */
    record TransferOrderSearchCriteria(
            String transferNo,
            Long sourceWarehouseId,
            Long targetWarehouseId,
            String statusCode,
            Instant stockOutTimeFrom,
            Instant stockOutTimeTo) {
    }

    /** 商品规格快照；写入调拨明细时固化。 */
    record ProductVariantSnapshot(
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode) {
    }

    /** 调拨单聚合写入模型。 */
    record TransferOrderWrite(
            String connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            Long sourceWarehouseId,
            Long targetWarehouseId,
            String statusCode,
            String outboundOperatorStaffCode,
            String outboundOperatorStaffNameSnapshot,
            String inboundOperatorStaffCode,
            String inboundOperatorStaffNameSnapshot,
            List<TransferOrderLineWrite> lines,
            String remark,
            Integer revision) {
        public TransferOrderWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 调拨单明细写入模型。 */
    record TransferOrderLineWrite(
            Integer lineNo,
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal quantity,
            String remark) {
    }

    /** 调拨出库前的调拨单快照。 */
    record TransferOrderSnapshot(
            Long id,
            String transferNo,
            String sourceSystemCode,
            String sourceDocumentNo,
            Long sourceWarehouseId,
            Long targetWarehouseId,
            String statusCode,
            Integer revision,
            List<TransferOrderLineSnapshot> lines) {
        public TransferOrderSnapshot {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 调拨出库前的调拨明细快照。 */
    record TransferOrderLineSnapshot(
            Long id,
            Integer lineNo,
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal quantity,
            String remark) {
    }

    /** 调拨出库确认写入模型；使用统一出库单，按 stockOutTypeCode 区分出库类型。 */
    record TransferStockOutWrite(
            Long transferOrderId,
            Integer transferRevision,
            String transferNo,
            String connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            String stockOutTypeCode,
            String stockOutStatusCode,
            String nextTransferStatusCode,
            Instant stockOutTime,
            Long sourceWarehouseId,
            List<TransferStockOutLineWrite> lines,
            String remark,
            boolean affectStockBalance) {
        public TransferStockOutWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 调拨出库明细写入模型，同时携带本次库存流水号。 */
    record TransferStockOutLineWrite(
            Integer lineNo,
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

    /** 外部来源调拨出库写入模型；先创建调拨单，再生成调拨出库单。 */
    record ExternalTransferStockOutWrite(
            String connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            Long sourceWarehouseId,
            Long targetWarehouseId,
            Instant stockOutTime,
            boolean affectStockBalance,
            String outboundOperatorStaffCode,
            String outboundOperatorStaffNameSnapshot,
            String inboundOperatorStaffCode,
            String inboundOperatorStaffNameSnapshot,
            List<ExternalTransferStockOutLineWrite> lines,
            String remark) {
        public ExternalTransferStockOutWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 外部来源调拨出库明细写入模型，同时携带本次库存流水号。 */
    record ExternalTransferStockOutLineWrite(
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

    /** 调拨入库确认写入模型；使用统一入库单，按 stockInTypeCode 区分入库类型。 */
    record TransferStockInWrite(
            Long transferOrderId,
            Integer transferRevision,
            String transferNo,
            String connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            String stockInTypeCode,
            String stockInStatusCode,
            String nextTransferStatusCode,
            Instant stockInTime,
            Long targetWarehouseId,
            List<TransferStockInLineWrite> lines,
            String remark) {
        public TransferStockInWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 调拨入库明细写入模型，同时携带本次库存流水号。 */
    record TransferStockInLineWrite(
            Integer lineNo,
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
}
