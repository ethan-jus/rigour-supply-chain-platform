package com.rigour.order.application.service.dhb;

import com.rigour.order.api.v1.model.DhbOrderDetailView;
import com.rigour.order.api.v1.model.DhbOrderLineView;
import com.rigour.order.api.v1.model.DhbOrderPageView;
import com.rigour.order.api.v1.model.DhbOrderView;
import com.rigour.order.api.v1.model.DhbOrderSourceRecordView;
import com.rigour.order.api.v1.model.DhbFinancialDocumentView;
import com.rigour.order.api.v1.model.DhbShipmentView;
import com.rigour.order.application.port.out.OrderDocumentRepository;
import com.rigour.order.application.port.out.OrderRepository;
import com.rigour.order.domain.model.order.Order;
import com.rigour.order.domain.model.order.OrderLine;
import com.rigour.order.domain.model.order.OrderShipment;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * 订货宝订单本地投影查询用例。
 *
 * <p>第三方认证、分页和同步由 Integration 负责；订单中心只读取自己的订单模型，
 * 不保存订货宝凭据，也不直接访问订货宝。</p>
 */
@Service
public class DhbOrderService {
    private final OrderRepository repository;
    private final OrderDocumentRepository documentRepository;

    public DhbOrderService(OrderRepository repository, OrderDocumentRepository documentRepository) {
        this.repository = repository;
        this.documentRepository = documentRepository;
    }

    public DhbOrderPageView list(String tenantId, OrderQuery query) {
        requireRead();
        OrderRepository.OrderFilter filter = filter(query);
        return page(repository.count(tenantId, filter), repository.findPage(tenantId, filter));
    }

    public DhbOrderDetailView detail(String tenantId, String orderSn) {
        requireRead();
        OrderRepository.InternalOrderDetailData data = repository.findDetail(tenantId, orderSn);
        if (data == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return detailView(tenantId, data);
    }

    private static void requireRead() {
        AuthorizationContext.requirePermission("order:read");
    }

    private static OrderRepository.OrderFilter filter(OrderQuery query) {
        validate(query);
        return new OrderRepository.OrderFilter(query.begin(), query.step(), query.orderStatusVal(),
                parseDate(query.startTime()), parseDate(query.endTime()), parseDate(query.updateGe()),
                parseDate(query.updateLe()), query.exceptionStatus(), query.apiStatus(), query.payStatus(),
                query.splitType(), false, query.keyword());
    }

    private static void validate(OrderQuery query) {
        if (query.begin() < 0) throw new IllegalArgumentException("begin不能小于0");
        if (query.step() < 1 || query.step() > 1000) throw new IllegalArgumentException("step必须在1到1000之间");
    }

    private static DhbOrderPageView page(long total, List<Order> orders) {
        return new DhbOrderPageView(total, 0, 0,
                orders.stream().map(order -> view(order, order.detailSyncedAt() != null)).toList());
    }

    private static DhbOrderView view(Order order, boolean detailAvailable) {
        return new DhbOrderView(order.sourceOrderNo(), order.deliveryDate(), order.remark(), order.totalAmount(),
                order.sourceStatus(), instant(order.orderedAt()), instant(order.sourceUpdatedAt()), order.sourceUpdateTime(),
                order.orderType(), order.sourceApiStatus(), order.sourceExceptionStatus(), order.sourceSendType(),
                order.sourceLastOrderAt(), order.sourceCustomerNo(), order.sourceCustomerGuid(), order.sourceDevice(),
                order.sourceAdminOrder(), order.paymentStatus(), order.customerName(), order.receiverName(),
                order.receiverCompany(), order.receiverPhone(), order.receiverAddress(), order.province(), order.city(),
                order.district(), order.splitType(), order.splitTypeName(), detailAvailable, instant(order.syncedAt()),
                order.customerType(), order.customerArea(), order.adminUser(), order.operationName(),
                order.salesPerson(), order.salesPersonMobile(), order.assistantSalesPersons(), order.auditAt(),
                order.settlementMethod(), order.goodsWeight(), order.taxAmount(), order.discountPrice(),
                order.discountTotal(), order.freightAmount(), order.applyTotal(), order.couponDiscountedAmount(),
                order.customerRemark(), order.internalComment(),
                order.invoiceTitle(), order.invoiceContent(), order.invoiceBank(), order.invoiceBankAccount(),
                order.taxpayerNumber(), order.customerTag(), order.invoiceType());
    }

    private DhbOrderDetailView detailView(String tenantId, OrderRepository.InternalOrderDetailData data) {
        List<DhbFinancialDocumentView> financialDocuments = Stream.of("RECEIPT", "PAYMENT")
                .flatMap(type -> documentRepository.findFinancialDocuments(tenantId, type,
                        new OrderDocumentRepository.DocumentFilter(0, 1000, null, null,
                                data.order().sourceOrderNo(), null, null)).stream())
                .map(DhbOrderService::financialView)
                .toList();
        return new DhbOrderDetailView(view(data.order(), data.detailAvailable()), data.lines().stream()
                .map(DhbOrderService::lineView).toList(), data.shipments().stream()
                .map(DhbOrderService::shipmentView).toList(), financialDocuments,
                data.sourceRecords().stream().map(DhbOrderService::sourceRecordView).toList(), false);
    }

    private static DhbOrderLineView lineView(OrderLine line) {
        return new DhbOrderLineView(line.sourceLineId(), line.sourceProductGuid(), line.skuNo(),
                line.sourceOptionsGoodsNo(), line.sourceBarcode(), line.productName(), line.productCode(),
                line.specificationFirst(), line.specificationSecond(), line.specificationName(), line.unitPrice(),
                line.quantity(), line.lineAmount(), line.unit(), line.remark(), line.purchasePrice(),
                line.conversionNumber(), line.offerPrice(), line.actualAmount(), line.goodsWeight(), line.preSale(),
                line.contentType(), line.invoiceTax(), line.contentPercent());
    }

    private static DhbShipmentView shipmentView(OrderShipment shipment) {
        return new DhbShipmentView(shipment.sourceShipmentNo(), shipment.status(), shipment.shipmentDate(),
                shipment.stockUpTime());
    }

    private static DhbFinancialDocumentView financialView(
            com.rigour.order.domain.model.order.DhbOrderDocuments.FinancialDocument document) {
        return new DhbFinancialDocumentView(document.documentType(), document.documentNo(),
                document.relatedDocumentNo(), document.orderNo(), document.customerNo(), document.businessType(),
                document.paymentMethod(), document.amount(), document.status(), instant(document.transactionAt()),
                document.serialNumber(), document.accountName(), document.bankName(), document.accountNumber(),
                document.remark(), instant(document.syncedAt()));
    }

    private static DhbOrderSourceRecordView sourceRecordView(
            com.rigour.order.domain.model.order.OrderSourceRecord record) {
        return new DhbOrderSourceRecordView(record.payloadType(), record.payloadJson(), record.payloadHash(),
                instant(record.receivedAt()));
    }

    private static LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replace('T', ' ');
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException ignored) {
            try {
                return java.time.LocalDate.parse(normalized).atStartOfDay();
            } catch (DateTimeParseException ignoredAgain) {
                throw new IllegalArgumentException("日期格式必须为yyyy-MM-dd或yyyy-MM-dd HH:mm:ss");
            }
        }
    }

    private static java.time.Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record OrderQuery(int begin, int step, String orderStatusVal, String startTime, String endTime,
                             String updateGe, String updateLe, String exceptionStatus, String apiStatus,
                             String payStatus, Integer splitType, String keyword) {
    }
}
