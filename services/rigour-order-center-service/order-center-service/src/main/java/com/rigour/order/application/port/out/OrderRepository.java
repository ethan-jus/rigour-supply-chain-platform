package com.rigour.order.application.port.out;

import com.rigour.order.domain.model.order.ImportedOrder;
import com.rigour.order.domain.model.order.Order;
import com.rigour.order.domain.model.order.OrderLine;
import com.rigour.order.domain.model.order.OrderShipment;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单聚合持久化端口。
 *
 * <p>应用层只依赖该端口，不感知 MyBatis-Plus、数据库实体和 Mapper。</p>
 */
public interface OrderRepository {

    List<Order> findPage(String tenantId, OrderFilter filter);

    long count(String tenantId, OrderFilter filter);

    InternalOrderDetailData findDetail(String tenantId, String sourceOrderNo);

    ImportResult importOrder(ImportedOrder imported);

    record OrderFilter(int begin, int step, String sourceStatus, LocalDateTime startTime,
                       LocalDateTime endTime, LocalDateTime sourceUpdatedFrom,
                       LocalDateTime sourceUpdatedTo, String exceptionStatus, String apiStatus,
                       String paymentStatus, Integer splitType, boolean excludeDemoData) {}

    record InternalOrderDetailData(Order order, List<OrderLine> lines, List<OrderShipment> shipments,
                                   boolean detailAvailable) {}

    record ImportResult(String orderId, boolean created, boolean changed) {}
}
