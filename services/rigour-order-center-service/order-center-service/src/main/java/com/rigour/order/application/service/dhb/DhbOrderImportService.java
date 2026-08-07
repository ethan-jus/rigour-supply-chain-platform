package com.rigour.order.application.service.dhb;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderImportResult;
import com.rigour.order.application.port.out.DhbShipmentLogisticsRepository;
import com.rigour.order.application.port.out.OrderDocumentRepository;
import com.rigour.order.application.port.out.OrderRepository;
import com.rigour.order.domain.model.order.ImportedOrder;
import com.rigour.order.domain.model.order.Order;
import com.rigour.order.domain.model.order.OrderLine;
import com.rigour.order.domain.model.order.OrderShipment;
import com.rigour.order.domain.model.order.enums.OrderStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Integration完成供应商调用后，订单中心执行幂等本地落库的内部用例。 */
@Service
public class DhbOrderImportService {
    private final OrderRepository orderRepository;
    private final OrderDocumentRepository documentRepository;
    private final DhbShipmentLogisticsRepository shipmentLogisticsRepository;

    public DhbOrderImportService(OrderRepository orderRepository, OrderDocumentRepository documentRepository,
                                 DhbShipmentLogisticsRepository shipmentLogisticsRepository) {
        this.orderRepository = orderRepository;
        this.documentRepository = documentRepository;
        this.shipmentLogisticsRepository = shipmentLogisticsRepository;
    }

    /**
     * 供Order Center受信任的定时同步调用；不依赖HTTP线程中的AuthorizationContext。
     *
     * <p>该方法不暴露为Controller接口，调用方必须是本服务内部的定时编排器；
     * 外部请求没有直接导入入口。</p>
     *
     * @param tenantId Gateway/Integration共同签名的租户ID，不接受请求体覆盖
     * @param batch 已完成字段归一化的订单、发货、getWaitShips物流、退货和收付款数据
     * @return 每类单据本次实际新增或内容变化的数量
     */
    @Transactional
    public DhbOrderImportResult importBatchInternal(String tenantId, DhbOrderImportBatch batch) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId不能为空");
        Objects.requireNonNull(batch, "batch不能为空");
        int orders = 0;
        for (DhbOrderImportBatch.OrderItem item : batch.orders()) {
            OrderRepository.ImportResult result = orderRepository.importOrder(importedOrder(tenantId, item));
            if (result.changed()) orders++;
        }
        int shipments = documentRepository.importShipments(tenantId, batch.shipments());
        int shipmentLogistics = shipmentLogisticsRepository.importSnapshots(tenantId, batch.shipmentLogistics());
        int returns = documentRepository.importReturns(tenantId, batch.returns());
        int financialDocuments = documentRepository.importFinancialDocuments(
                tenantId, batch.financialDocuments());
        return new DhbOrderImportResult(orders, shipments, shipmentLogistics, returns, financialDocuments);
    }

    private static ImportedOrder importedOrder(String tenantId, DhbOrderImportBatch.OrderItem item) {
        requireText(item.sourceOrderNo(), "sourceOrderNo");
        requireText(item.payloadHash(), "payloadHash");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Order order = new Order(null, tenantId, item.sourceOrderNo(), Order.SOURCE_DINGHUOBAO,
                item.sourceOrderNo(), initialStatus(item.sourceStatus()), item.sourceStatus(), item.paymentStatus(),
                item.orderType(), item.totalAmount(), utc(item.orderedAt()), utc(item.sourceUpdatedAt()),
                item.sourceUpdateTime(), item.deliveryDate(), item.remark(), item.customerNo(), item.customerGuid(),
                item.customerName(), item.receiverName(), item.receiverCompany(), item.receiverPhone(),
                item.receiverAddress(), item.province(), item.city(), item.district(), item.sourceApiStatus(),
                item.sourceExceptionStatus(), item.sourceSendType(), item.sourceLastOrderAt(), item.sourceDevice(),
                item.sourceAdminOrder(), item.splitType(), item.splitTypeName(), item.payloadHash(),
                item.detailIncluded() ? now : null, null, now);
        List<OrderLine> lines = item.lines().stream().map(line -> new OrderLine(null, line.sourceLineId(),
                line.sourceProductGuid(), line.skuNo(), line.sourceOptionsGoodsNo(), line.sourceBarcode(),
                line.productName(), line.productCode(), line.specificationFirst(), line.specificationSecond(),
                line.specificationName(), line.unitPrice(), line.quantity(), line.lineAmount(), line.unit(),
                line.remark())).toList();
        List<OrderShipment> shipments = item.shipmentSnapshots().stream().map(shipment -> new OrderShipment(
                null, shipment.sourceShipmentNo(), shipment.status(), shipment.shipmentDate(),
                shipment.stockUpTime())).toList();
        return new ImportedOrder(order, lines, shipments, item.rawListJson(), item.rawDetailJson(),
                item.payloadHash(), item.detailIncluded());
    }

    /**
     * 仅在首次建单时映射内部状态：pricing/pending待确认，stockup待出库，shipped已发货，
     * received已收货，finished/forcedone完成，cancelled取消；未知值进入EXCEPTION。
     */
    private static String initialStatus(String sourceStatus) {
        if (sourceStatus == null) return OrderStatus.RECEIVED.code();
        return switch (sourceStatus.toLowerCase()) {
            case "pricing", "pending" -> OrderStatus.PENDING_CONFIRMATION.code();
            case "stock_up", "stockup" -> OrderStatus.ALLOCATING.code();
            case "shipped" -> OrderStatus.SHIPPED.code();
            case "received" -> OrderStatus.COMPLETED.code();
            case "finished", "forcedone" -> OrderStatus.COMPLETED.code();
            case "cancelled" -> OrderStatus.CANCELLED.code();
            default -> OrderStatus.EXCEPTION.code();
        };
    }

    private static LocalDateTime utc(java.time.Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    }

}
