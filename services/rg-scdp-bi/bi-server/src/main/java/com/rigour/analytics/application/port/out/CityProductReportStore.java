package com.rigour.analytics.application.port.out;

import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.api.v1.model.CityProductReportView.CustomerArchive;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 报表出站端口；返回完整订单的行，分类筛选不得提前缩小金额分母。 */
public interface CityProductReportStore {
    Optional<Instant> latestOrderDate(String tenantId);

    List<FactRow> load(String tenantId, SupplyDashboardFilter filter, int orderLimit, int lineLimit);

    List<FactRow> load(String tenantId, SupplyDashboardFilter filter, ProductSelection selection,
                      int orderLimit, int lineLimit);

    /** 与分类条件相交；不能在完整订单金额分配之前过滤订单行。 */
    record ProductSelection(Long brandId, Long productId, Long skuId) { }

    List<CustomerArchive> customerArchives(String tenantId, SupplyDashboardFilter filter);

    List<Long> categoryIds(String tenantId, Long parentId);

    /** 订单左联行快照；缺行订单保留一条 lineId=null 的记录。 */
    record FactRow(
            Long orderId, String orderNo, String sourceOrderNo, String sourceSystemCode,
            Long customerId, String regionCode, String regionName, String ownerStaffCode,
            LocalDateTime orderDate, BigDecimal payable, BigDecimal paid, BigDecimal unpaid,
            Long lineCount, BigDecimal lineTotal,
            Long lineId, Long categoryId, String categoryCode, String categoryName,
            Long productId, String productCode, String productName, Long skuId, String skuCode,
            String unitCode, BigDecimal quantity, BigDecimal salesAmount,
            BigDecimal salesNetAmount, BigDecimal refundAmount, LocalDateTime orderSyncedAt, LocalDateTime lineSyncedAt,
            String specification, String customerName, String ownerStaffName,
            Long brandId, String brandName) { }
}
