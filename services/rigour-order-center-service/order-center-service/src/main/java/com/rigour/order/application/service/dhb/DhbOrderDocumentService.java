package com.rigour.order.application.service.dhb;

import com.rigour.order.api.v1.DhbSourceStatuses;
import com.rigour.order.api.v1.model.DhbDocumentPageView;
import com.rigour.order.api.v1.model.DhbFinancialDocumentView;
import com.rigour.order.api.v1.model.DhbReturnDetailView;
import com.rigour.order.api.v1.model.DhbReturnDocumentView;
import com.rigour.order.api.v1.model.DhbReturnLineView;
import com.rigour.order.api.v1.model.DhbShipmentDetailView;
import com.rigour.order.api.v1.model.DhbShipmentDocumentView;
import com.rigour.order.api.v1.model.DhbShipmentLineView;
import com.rigour.order.application.port.out.OrderDocumentRepository;
import com.rigour.order.application.port.out.OrderDocumentRepository.DocumentFilter;
import com.rigour.order.domain.model.order.DhbOrderDocuments;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;

/** 出库/发货、退货、收款和付款的本地只读查询用例。 */
@Service
public class DhbOrderDocumentService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final OrderDocumentRepository repository;

    public DhbOrderDocumentService(OrderDocumentRepository repository) {
        this.repository = repository;
    }

    /** 查询统一的本地出库/发货单，status和typeId均沿用订货宝getShipsList原值。 */
    public DhbDocumentPageView<DhbShipmentDocumentView> shipments(String tenantId, Query query) {
        requireRead();
        DocumentFilter filter = filter(query, DhbSourceStatuses.SHIPMENT);
        return new DhbDocumentPageView<>(repository.countShipments(tenantId, filter),
                repository.findShipments(tenantId, filter).stream().map(DhbOrderDocumentService::shipment).toList());
    }

    /** 按发货单号读取本地主信息和明细，不触发订货宝实时请求。 */
    public DhbShipmentDetailView shipment(String tenantId, String shipmentNo) {
        requireRead();
        DhbOrderDocuments.ShipmentDetail detail = repository.findShipment(tenantId, shipmentNo);
        if (detail == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return new DhbShipmentDetailView(shipment(detail.shipment()), detail.lines().stream()
                .map(line -> new DhbShipmentLineView(line.sourceLineId(), line.sourceProductGuid(), line.skuNo(),
                        line.productCode(), line.productName(), line.quantity(), line.unitPrice(), line.amount(),
                        line.unit(), line.warehouseNo(), line.remark())).toList());
    }

    /** 查询本地退货单，status允许return_audit、shipp_cust、shipped、refunded、finished、cancelled。 */
    public DhbDocumentPageView<DhbReturnDocumentView> returns(String tenantId, Query query) {
        requireRead();
        DocumentFilter filter = filter(query, DhbSourceStatuses.RETURN);
        return new DhbDocumentPageView<>(repository.countReturns(tenantId, filter),
                repository.findReturns(tenantId, filter).stream().map(DhbOrderDocumentService::returnView).toList());
    }

    /** 按退货单号读取本地主信息和商品明细。 */
    public DhbReturnDetailView returnDetail(String tenantId, String returnNo) {
        requireRead();
        DhbOrderDocuments.ReturnDetail detail = repository.findReturn(tenantId, returnNo);
        if (detail == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return new DhbReturnDetailView(returnView(detail.returnDocument()), detail.lines().stream()
                .map(line -> new DhbReturnLineView(line.sourceLineId(), line.sourceProductGuid(), line.skuNo(),
                        line.productCode(), line.productName(), line.quantity(), line.confirmedQuantity(),
                        line.unitPrice(), line.confirmedPrice(), line.unit(), line.warehouseNo(),
                        line.warehouseName(), line.remark())).toList());
    }

    /** 查询本地收款单(RECEIPT)或付款单(PAYMENT)。 */
    public DhbDocumentPageView<DhbFinancialDocumentView> financialDocuments(
            String tenantId, String documentType, Query query) {
        requireRead();
        String type = documentType == null ? "" : documentType.toUpperCase();
        if (!DhbSourceStatuses.FINANCIAL_DOCUMENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("documentType必须为RECEIPT或PAYMENT");
        }
        DocumentFilter filter = filter(query, DhbSourceStatuses.FINANCIAL);
        return new DhbDocumentPageView<>(repository.countFinancialDocuments(tenantId, type, filter),
                repository.findFinancialDocuments(tenantId, type, filter).stream()
                        .map(DhbOrderDocumentService::financial).toList());
    }

    private static void requireRead() { AuthorizationContext.requirePermission("order:read"); }

    private static DocumentFilter filter(Query query, java.util.Map<String, String> statuses) {
        if (query.begin() < 0) throw new IllegalArgumentException("begin不能小于0");
        if (query.step() < 1 || query.step() > 1000) throw new IllegalArgumentException("step必须在1到1000之间");
        if (query.status() != null && !query.status().isBlank() && !statuses.containsKey(query.status())) {
            throw new IllegalArgumentException("不支持的来源状态: " + query.status());
        }
        if (query.typeId() != null && !query.typeId().isBlank()) {
            for (String typeId : query.typeId().split(",")) {
                if (!DhbSourceStatuses.SHIPMENT_TYPES.containsKey(typeId.strip())) {
                    throw new IllegalArgumentException("不支持的出库类型: " + typeId.strip());
                }
            }
        }
        return new DocumentFilter(query.begin(), query.step(), blank(query.status()), blank(query.typeId()),
                blank(query.orderNo()), parse(query.from()), parse(query.to()));
    }

    private static DhbShipmentDocumentView shipment(DhbOrderDocuments.Shipment value) {
        return new DhbShipmentDocumentView(value.shipmentNo(), value.orderNo(), value.status(), value.statusName(),
                value.typeId(), value.typeName(), value.customerNo(), value.customerName(), value.warehouseNo(), value.warehouseName(),
                instant(value.shipmentAt()), value.logisticsName(), value.trackingNo(), value.remark(),
                value.detailAvailable(), instant(value.syncedAt()));
    }

    private static DhbReturnDocumentView returnView(DhbOrderDocuments.ReturnDocument value) {
        return new DhbReturnDocumentView(value.returnNo(), value.orderNo(), value.status(), value.returnAmount(),
                value.settlementAmount(), instant(value.returnedAt()), value.reason(), value.customerNo(),
                value.consignee(), value.logisticsCompany(), value.logisticsNo(), value.detailAvailable(),
                instant(value.syncedAt()));
    }

    private static DhbFinancialDocumentView financial(DhbOrderDocuments.FinancialDocument value) {
        return new DhbFinancialDocumentView(value.documentType(), value.documentNo(), value.relatedDocumentNo(),
                value.orderNo(), value.customerNo(), value.businessType(), value.paymentMethod(), value.amount(),
                value.status(), instant(value.transactionAt()), value.serialNumber(), value.accountName(),
                value.bankName(), value.accountNumber(), value.remark(), instant(value.syncedAt()));
    }

    private static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replace('T', ' ');
        try { return LocalDateTime.parse(normalized, DATE_TIME); }
        catch (DateTimeParseException ignored) {
            try { return LocalDate.parse(normalized).atStartOfDay(); }
            catch (DateTimeParseException error) {
                throw new IllegalArgumentException("时间格式必须为yyyy-MM-dd或yyyy-MM-dd HH:mm:ss");
            }
        }
    }

    private static java.time.Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    /**
     * 单据查询参数。
     * begin为零基偏移；step范围1..1000；status和typeId为供应商原值；orderNo为关联订单号；
     * from/to支持yyyy-MM-dd或yyyy-MM-dd HH:mm:ss并按来源业务时间过滤。
     */
    public record Query(int begin, int step, String status, String typeId, String orderNo, String from, String to) {
    }
}
