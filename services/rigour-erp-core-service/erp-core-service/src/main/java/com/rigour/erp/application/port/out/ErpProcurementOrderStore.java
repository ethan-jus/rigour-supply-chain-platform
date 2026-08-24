package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ERP 自研采购订单持久化端口。 */
public interface ErpProcurementOrderStore {
    MasterDataPageView<InternalProcurementOrderSummaryView> procurementOrders(
            String tenantId, int begin, int step, ProcurementOrderSearchCriteria criteria);

    Optional<InternalProcurementOrderDetailView> procurementOrder(String tenantId, Long id);

    boolean existsByNo(String tenantId, String procurementNo);

    boolean supplierActive(String tenantId, Long supplierId);

    boolean warehouseActive(String tenantId, Long warehouseId);

    Optional<ProductVariantSnapshot> productVariant(String tenantId, Long productId, Long productVariantId);

    InternalProcurementOrderDetailView create(
            String tenantId, String procurementNo, ProcurementOrderWrite command, String actorId);

    InternalProcurementOrderDetailView update(
            String tenantId, Long id, ProcurementOrderWrite command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 采购订单列表独立筛选条件。 */
    record ProcurementOrderSearchCriteria(
            String procurementNo,
            Long supplierId,
            Long targetWarehouseId,
            String statusCode,
            Instant expectedArrivalFrom,
            Instant expectedArrivalTo) {
    }

    /** 商品规格快照；写入采购明细时固化，避免后续商品改名影响历史单据。 */
    record ProductVariantSnapshot(
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal purchasePrice) {
    }

    /** 采购订单聚合写入模型；Service 已完成校验、状态归一和金额汇总。 */
    record ProcurementOrderWrite(
            Long supplierId,
            Long targetWarehouseId,
            String statusCode,
            Instant expectedArrivalTime,
            BigDecimal totalQuantity,
            BigDecimal totalAmount,
            List<ProcurementOrderLineWrite> lines,
            String remark,
            Integer revision) {
        public ProcurementOrderWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 采购订单明细写入模型。 */
    record ProcurementOrderLineWrite(
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
            String remark) {
    }
}
