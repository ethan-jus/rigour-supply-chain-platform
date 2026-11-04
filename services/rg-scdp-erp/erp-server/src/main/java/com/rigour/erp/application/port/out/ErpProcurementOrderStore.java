package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderPageView;
import com.rigour.erp.api.v1.model.InternalProcurementProductView;
import com.rigour.erp.api.v1.model.MasterDataPageView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** ERP 自研采购订单持久化端口。 */
public interface ErpProcurementOrderStore {
    InternalProcurementOrderPageView procurementOrders(
            String tenantId, int begin, int step, ProcurementOrderSearchCriteria criteria);

    Optional<InternalProcurementOrderDetailView> procurementOrder(String tenantId, Long id);

    boolean existsByNo(String tenantId, String procurementNo);

    Optional<MasterReference> supplierReference(String tenantId, String code, Long legacyId);

    Optional<MasterReference> warehouseReference(String tenantId, String code, Long legacyId);

    Optional<ProductVariantSnapshot> productVariantByCode(
            String tenantId, String productCode, String variantCode);

    MasterDataPageView<InternalProcurementProductView> procurementProducts(
            String tenantId,
            int begin,
            int step,
            String productName,
            String productCode,
            String variantCode);

    record MasterReference(
            Long id, String code, String contactName, String contactPhone, String address) {
        public MasterReference(Long id, String code) {
            this(id, code, null, null, null);
        }
    }

    boolean supplierActive(String tenantId, Long supplierId);

    boolean warehouseActive(String tenantId, Long warehouseId);

    Optional<ProductVariantSnapshot> productVariant(String tenantId, Long productId, Long productVariantId);

    InternalProcurementOrderDetailView create(
            String tenantId, String procurementNo, ProcurementOrderWrite command, String actorId);

    InternalProcurementOrderDetailView update(
            String tenantId, Long id, ProcurementOrderWrite command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    InternalProcurementOrderDetailView confirm(
            String tenantId, Long id, int revision, String actorId);

    /** 采购订单列表独立筛选条件。 */
    record ProcurementOrderSearchCriteria(
            String procurementNo,
            Long supplierId,
            Long targetWarehouseId,
            String statusCode,
            Instant expectedArrivalFrom,
            Instant expectedArrivalTo,
            String supplierCode,
            String targetWarehouseCode,
            String confirmationStatusCode,
            LocalDate purchaseDateFrom,
            LocalDate purchaseDateTo) {
        public ProcurementOrderSearchCriteria(
                String procurementNo,
                Long supplierId,
                Long targetWarehouseId,
                String statusCode,
                Instant expectedArrivalFrom,
                Instant expectedArrivalTo) {
            this(
                    procurementNo,
                    supplierId,
                    targetWarehouseId,
                    statusCode,
                    expectedArrivalFrom,
                    expectedArrivalTo,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** 商品规格及单位配置；查询使用；名称不再写入采购明细。 */
    record ProductVariantSnapshot(
            Long productId,
            Long productVariantId,
            String productCode,
            String variantCode,
            String productName,
            String unitCode,
            BigDecimal purchasePrice,
            String baseUnitCode,
            String middleUnitCode,
            BigDecimal baseToMiddleRate,
            String bigUnitCode,
            BigDecimal baseToBigRate,
            String specification) {
        public ProductVariantSnapshot(
                Long productId,
                Long productVariantId,
                String productCode,
                String variantCode,
                String productName,
                String unitCode,
                BigDecimal purchasePrice) {
            this(
                    productId,
                    productVariantId,
                    productCode,
                    variantCode,
                    productName,
                    unitCode,
                    purchasePrice,
                    unitCode,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
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
            Integer revision,
            String supplierCode,
            String targetWarehouseCode,
            LocalDate purchaseDate,
            BigDecimal freightAmount,
            String freightBearerCode,
            String contractNo,
            String contractFileKey,
            String receiverName,
            String receiverPhone,
            String receivingAddress) {
        public ProcurementOrderWrite(
                Long supplierId,
                Long targetWarehouseId,
                String statusCode,
                Instant expectedArrivalTime,
                BigDecimal totalQuantity,
                BigDecimal totalAmount,
                List<ProcurementOrderLineWrite> lines,
                String remark,
                Integer revision) {
            this(
                    supplierId,
                    targetWarehouseId,
                    statusCode,
                    expectedArrivalTime,
                    totalQuantity,
                    totalAmount,
                    lines,
                    remark,
                    revision,
                    null,
                    null,
                    null,
                    null,
                    "BUYER",
                    null,
                    null,
                    null,
                    null,
                    null);
        }

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
            String remark,
            String baseUnitCode,
            BigDecimal conversionRate) {
        public ProcurementOrderLineWrite(
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
            this(
                    lineNo,
                    productId,
                    productVariantId,
                    productCode,
                    variantCode,
                    productName,
                    unitCode,
                    quantity,
                    unitPrice,
                    lineAmount,
                    remark,
                    unitCode,
                    BigDecimal.ONE);
        }
    }
}
