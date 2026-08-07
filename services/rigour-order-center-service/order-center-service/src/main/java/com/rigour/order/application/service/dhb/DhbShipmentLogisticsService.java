package com.rigour.order.application.service.dhb;

import com.rigour.order.api.v1.DhbSourceStatuses;
import com.rigour.order.api.v1.model.DhbDocumentPageView;
import com.rigour.order.api.v1.model.DhbShipmentLogisticsDetailView;
import com.rigour.order.api.v1.model.DhbShipmentLogisticsView;
import com.rigour.order.application.port.out.DhbShipmentLogisticsRepository;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;

/** getWaitShips物流快照的本地查询用例，不实时调用订货宝。 */
@Service
public class DhbShipmentLogisticsService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DhbShipmentLogisticsRepository repository;

    public DhbShipmentLogisticsService(DhbShipmentLogisticsRepository repository) {
        this.repository = repository;
    }

    /** 分页查询本地getWaitShips物流快照。 */
    public DhbDocumentPageView<DhbShipmentLogisticsView> list(String tenantId, Query query) {
        requireRead();
        validatePage(query);
        if (query.status() != null && !query.status().isBlank()
                && !DhbSourceStatuses.SHIPMENT.containsKey(query.status())) {
            throw new IllegalArgumentException("不支持的物流状态: " + query.status());
        }
        DhbShipmentLogisticsRepository.Query filter = new DhbShipmentLogisticsRepository.Query(
                query.begin(), query.step(), blank(query.status()), blank(query.orderNo()),
                parse(query.from()), parse(query.to()));
        return new DhbDocumentPageView<>(repository.count(tenantId, filter),
                repository.findPage(tenantId, filter).stream()
                        .map(DhbShipmentLogisticsService::view).toList());
    }

    /** 按订货宝订单号查询本地物流快照及明细。 */
    public DhbShipmentLogisticsDetailView detail(String tenantId, String orderNo) {
        requireRead();
        if (orderNo == null || orderNo.isBlank()) throw new IllegalArgumentException("orderNo不能为空");
        DhbShipmentLogisticsRepository.Detail detail = repository.findDetail(tenantId, orderNo.strip());
        if (detail == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return new DhbShipmentLogisticsDetailView(view(detail.snapshot()), detail.lines().stream()
                .map(line -> new DhbShipmentLogisticsDetailView.Line(line.lineType(), line.sourceLineId(),
                        line.orderLineId(), line.productId(), line.skuNo(), line.productCode(), line.productName(),
                        line.specification(), line.unit(), line.containerUnit(), line.conversionNumber(),
                        line.quantity(), line.orderedQuantity(), line.stockedQuantity(), line.realStock(),
                        line.waitQuantity(), line.warehouseNo(), line.warehouseName(), line.remark()))
                .toList());
    }

    private static DhbShipmentLogisticsView view(DhbShipmentLogisticsRepository.Snapshot value) {
        return new DhbShipmentLogisticsView(value.orderNo(), value.shipmentNo(), value.status(),
                value.logisticsName(), value.logisticsCode(), value.trackingNo(), instant(value.shipmentAt()),
                instant(value.stockUpAt()), value.warehouseNo(), value.warehouseName(), value.shippedCount(),
                value.waitStockCount(), instant(value.syncedAt()));
    }

    private static void requireRead() { AuthorizationContext.requirePermission("order:read"); }

    private static void validatePage(Query query) {
        if (query == null) throw new IllegalArgumentException("查询参数不能为空");
        if (query.begin() < 0) throw new IllegalArgumentException("begin不能小于0");
        if (query.step() < 1 || query.step() > 1000) throw new IllegalArgumentException("step必须在1到1000之间");
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

    /** getWaitShips物流查询参数；时间按本地快照的最近发货时间过滤。 */
    public record Query(int begin, int step, String status, String orderNo, String from, String to) {
    }
}
