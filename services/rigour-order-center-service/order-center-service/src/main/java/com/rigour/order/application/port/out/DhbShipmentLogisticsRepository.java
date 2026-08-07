package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** getWaitShips物流快照的订单中心持久化端口。 */
public interface DhbShipmentLogisticsRepository {
    /** 按租户和订单号幂等保存一次getWaitShips快照。 */
    int importSnapshots(String tenantId, List<DhbOrderImportBatch.ShipmentLogisticsItem> items);

    /** 查询本地物流快照分页。 */
    List<Snapshot> findPage(String tenantId, Query query);

    /** 统计本地物流快照。 */
    long count(String tenantId, Query query);

    /** 按订单号查询物流快照及明细；不存在返回null。 */
    Detail findDetail(String tenantId, String orderNo);

    record Query(int begin, int step, String status, String orderNo,
                 LocalDateTime from, LocalDateTime to) {
    }

    record Snapshot(String orderNo, String shipmentNo, String status, String logisticsName,
                    String logisticsCode, String trackingNo, LocalDateTime shipmentAt,
                    LocalDateTime stockUpAt, String warehouseNo, String warehouseName,
                    int shippedCount, int waitStockCount, LocalDateTime syncedAt) {
    }

    record Detail(Snapshot snapshot, List<Line> lines) {
        public Detail { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    record Line(String lineType, String sourceLineId, String orderLineId, String productId,
                String skuNo, String productCode, String productName, String specification,
                String unit, String containerUnit, BigDecimal conversionNumber,
                BigDecimal quantity, BigDecimal orderedQuantity, BigDecimal stockedQuantity,
                BigDecimal realStock, BigDecimal waitQuantity, String warehouseNo,
                String warehouseName, String remark) {
    }
}
