package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.api.v1.model.SalesShipmentSummaryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Order 销售发货单持久化端口；只操作自研发货单业务表。 */
public interface OrderSalesShipmentStore {
    OrderPageView<SalesShipmentSummaryView> shipments(
            String tenantId, int begin, int step, SalesShipmentSearchCriteria criteria);

    Optional<SalesShipmentDetailView> shipment(String tenantId, Long id);

    boolean existsByNo(String tenantId, String shipmentNo);

    SalesShipmentDetailView create(String tenantId, String shipmentNo,
                                   SalesShipmentWrite command, String actorId);

    SalesShipmentDetailView update(String tenantId, Long id,
                                   SalesShipmentWrite command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    record SalesShipmentSearchCriteria(
            String shipmentNo,
            String salesOrderNo,
            String customerName,
            String trackingNo,
            String shipmentStatusCode,
            Instant shipTimeFrom,
            Instant shipTimeTo) {
    }

    record SalesShipmentWrite(
            UUID connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            Long salesOrderId,
            String salesOrderNoSnapshot,
            Long customerId,
            String customerCodeSnapshot,
            String customerNameSnapshot,
            String contactPhoneSnapshot,
            String regionCode,
            String ownerStaffCode,
            Long warehouseId,
            Long stockOutOrderId,
            String stockOutNo,
            String shipmentStatusCode,
            String logisticsCompany,
            String trackingNo,
            Instant shipTime,
            BigDecimal totalQuantity,
            List<SalesShipmentLineWrite> lines,
            String remark,
            Integer revision) {
        public SalesShipmentWrite {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        public SalesShipmentWrite(
                Long salesOrderId,
                String salesOrderNoSnapshot,
                Long customerId,
                String customerCodeSnapshot,
                String customerNameSnapshot,
                String contactPhoneSnapshot,
                String regionCode,
                String ownerStaffCode,
                Long warehouseId,
                Long stockOutOrderId,
                String stockOutNo,
                String shipmentStatusCode,
                String logisticsCompany,
                String trackingNo,
                Instant shipTime,
                BigDecimal totalQuantity,
                List<SalesShipmentLineWrite> lines,
                String remark,
                Integer revision) {
            this(null, null, null, salesOrderId, salesOrderNoSnapshot, customerId,
                    customerCodeSnapshot, customerNameSnapshot, contactPhoneSnapshot,
                    regionCode, ownerStaffCode, warehouseId, stockOutOrderId, stockOutNo,
                    shipmentStatusCode, logisticsCompany, trackingNo, shipTime, totalQuantity,
                    lines, remark, revision);
        }
    }

    record SalesShipmentLineWrite(
            Long salesOrderLineId,
            Integer lineNo,
            Long productId,
            Long productVariantId,
            String productCodeSnapshot,
            String skuCodeSnapshot,
            String productNameSnapshot,
            String specificationSnapshot,
            String unitCode,
            BigDecimal shippedQuantity,
            String remark) {
    }
}
