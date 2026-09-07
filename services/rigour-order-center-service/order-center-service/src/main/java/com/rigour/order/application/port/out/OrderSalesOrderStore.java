package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderSummaryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 自研销售订单仓储端口；只维护 `order_sales_order` 及其明细。 */
public interface OrderSalesOrderStore {
    OrderPageView<SalesOrderSummaryView> salesOrders(
            String tenantId, int begin, int step, SalesOrderSearchCriteria criteria);

    Optional<SalesOrderDetailView> salesOrder(String tenantId, Long id);

    boolean existsByNo(String tenantId, String orderNo);

    SalesOrderDetailView create(String tenantId, String orderNo, SalesOrderWrite command, String actorId);

    SalesOrderDetailView update(String tenantId, Long id, SalesOrderWrite command, String actorId);

    SalesOrderDetailView updateSourceStatus(
            String tenantId, Long id, String sourceStatusCode, int revision, String actorId);

    SalesOrderDetailView updateSourceProjection(
            String tenantId, Long id, SalesOrderSourceProjectionWrite command, String actorId);

    SalesOrderDetailView submit(String tenantId, Long id, int revision, String actorId);

    SalesOrderDetailView cancel(String tenantId, Long id, int revision, String actorId);

    SalesOrderDetailView cancelBySource(String tenantId, Long id, int revision, String actorId);

    SalesOrderDetailView confirmOutbound(String tenantId, Long id, int revision, Instant shipmentTime, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    record SalesOrderSearchCriteria(
            String orderNo,
            String sourceOrderNo,
            String sourceStatusCode,
            String customerName,
            String contactPhone,
            String regionCode,
            String ownerSalesUserId,
            String ownerStaffCode,
            String orderStatusCode,
            String paymentStatusCode,
            String outboundStatusCode,
            Instant orderDateFrom,
            Instant orderDateTo) {
        public SalesOrderSearchCriteria(String orderNo, String customerName,
                                        String contactPhone, String regionCode,
                                        String ownerSalesUserId, String ownerStaffCode,
                                        String orderStatusCode, String paymentStatusCode,
                                        String outboundStatusCode, Instant orderDateFrom,
                                        Instant orderDateTo) {
            this(orderNo, null, null, customerName, contactPhone, regionCode, ownerSalesUserId,
                    ownerStaffCode, orderStatusCode, paymentStatusCode, outboundStatusCode,
                    orderDateFrom, orderDateTo);
        }

        public SalesOrderSearchCriteria(String orderNo, String customerName,
                                        String contactPhone, String regionCode,
                                        String ownerSalesUserId, String orderStatusCode,
                                        String paymentStatusCode, String outboundStatusCode,
                                        Instant orderDateFrom, Instant orderDateTo) {
            this(orderNo, null, null, customerName, contactPhone, regionCode, ownerSalesUserId,
                    null, orderStatusCode, paymentStatusCode, outboundStatusCode,
                    orderDateFrom, orderDateTo);
        }
    }

    record SalesOrderWrite(
            Long customerId,
            String sourceSystemCode,
            String sourceOrderNo,
            String sourceStatusCode,
            String sourceCreatorId,
            String sourceCreatorStaffCode,
            String sourceCreatorName,
            String customerCodeSnapshot,
            String customerNameSnapshot,
            String contactNameSnapshot,
            String contactPhoneSnapshot,
            String regionCode,
            String ownerSalesUserId,
            String ownerSalesName,
            String ownerStaffCode,
            String ownerStaffNameSnapshot,
            Instant orderDate,
            String orderStatusCode,
            String orderTypeCode,
            String paymentMethodCode,
            BigDecimal totalQuantity,
            BigDecimal originalAmount,
            BigDecimal discountRate,
            BigDecimal discountAmount,
            BigDecimal payableAmount,
            List<SalesOrderLineWrite> lines,
            String remark,
            Integer revision) {
        public SalesOrderWrite(Long customerId, String customerCodeSnapshot,
                               String customerNameSnapshot, String contactNameSnapshot,
                               String contactPhoneSnapshot, String regionCode,
                               String ownerSalesUserId, String ownerSalesName,
                               String ownerStaffCode, String ownerStaffNameSnapshot,
                               Instant orderDate, String orderStatusCode,
                               String orderTypeCode, String paymentMethodCode,
                               BigDecimal totalQuantity, BigDecimal originalAmount,
                               BigDecimal discountRate, BigDecimal discountAmount,
                               BigDecimal payableAmount, List<SalesOrderLineWrite> lines,
                               String remark, Integer revision) {
            this(customerId, null, null, null, null, null, null, customerCodeSnapshot,
                    customerNameSnapshot, contactNameSnapshot, contactPhoneSnapshot, regionCode,
                    ownerSalesUserId, ownerSalesName, ownerStaffCode, ownerStaffNameSnapshot,
                    orderDate, orderStatusCode, orderTypeCode, paymentMethodCode,
                    totalQuantity, originalAmount, discountRate, discountAmount,
                    payableAmount, lines, remark, revision);
        }

        public SalesOrderWrite(Long customerId, String customerCodeSnapshot,
                               String customerNameSnapshot, String contactNameSnapshot,
                               String contactPhoneSnapshot, String regionCode,
                               String ownerSalesUserId, String ownerSalesName,
                               Instant orderDate, String orderStatusCode,
                               String orderTypeCode, String paymentMethodCode,
                               BigDecimal totalQuantity, BigDecimal originalAmount,
                               BigDecimal discountRate, BigDecimal discountAmount,
                               BigDecimal payableAmount, List<SalesOrderLineWrite> lines,
                               String remark, Integer revision) {
            this(customerId, null, null, null, null, null, null, customerCodeSnapshot,
                    customerNameSnapshot, contactNameSnapshot, contactPhoneSnapshot, regionCode,
                    ownerSalesUserId, ownerSalesName, null, null, orderDate,
                    orderStatusCode, orderTypeCode, paymentMethodCode, totalQuantity,
                    originalAmount, discountRate, discountAmount, payableAmount,
                    lines, remark, revision);
        }
    }

    record SalesOrderSourceProjectionWrite(
            String sourceStatusCode,
            String sourceCreatorId,
            String sourceCreatorStaffCode,
            String sourceCreatorName,
            String ownerSalesUserId,
            String ownerSalesName,
            String ownerStaffCode,
            String ownerStaffNameSnapshot,
            Integer revision) {
    }

    record SalesOrderLineWrite(
            Integer lineNo,
            Long productId,
            Long productVariantId,
            String productCodeSnapshot,
            String skuCodeSnapshot,
            String productNameSnapshot,
            String specificationSnapshot,
            String unitCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountRate,
            BigDecimal discountAmount,
            BigDecimal lineAmount,
            String remark) {
    }
}
