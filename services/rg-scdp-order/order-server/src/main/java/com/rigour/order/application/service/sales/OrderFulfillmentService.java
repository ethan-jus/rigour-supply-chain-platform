package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.springframework.stereotype.Service;

/** 执行意图 → ERP 幂等执行 → Order 结果回写；重试先查既有结果。 */
@Service
public final class OrderFulfillmentService {
    private final OrderFulfillmentStore store;
    private final ErpFulfillmentClient erp;

    public OrderFulfillmentService(OrderFulfillmentStore store, ErpFulfillmentClient erp) {
        this.store = store;
        this.erp = erp;
    }

    public OrderFulfillmentQueueView queue(int begin,int step,String keyword,String outboundStatus) {
        var a=tenant(); AuthorizationContext.requirePermission("order:outbound:read");
        var result=store.queue(a.tenantId().toString(),begin,step,keyword,outboundStatus);
        var names=erp.warehouses(a.tenantId().toString()).stream().collect(java.util.stream.Collectors.toMap(w->String.valueOf(w.id()),com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption::warehouseName));
        return new OrderFulfillmentQueueView(result.total(),result.begin(),result.step(),result.items().stream().map(i->new OrderFulfillmentQueueView.Item(i.orderId(),i.orderNo(),i.warehouseId(),names.getOrDefault(i.warehouseId(),i.warehouseId()),i.outboundStatus(),i.revision())).toList());
    }
    public OrderFulfillmentQueueView.Detail detail(long id) {
        var a=tenant(); AuthorizationContext.requirePermission("order:outbound:read");
        return store.detail(a.tenantId().toString(),id);
    }

    public java.util.List<com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption> warehouses(
            long id) {
        var a = tenant();
        AuthorizationContext.requirePermission("order:warehouse:select");
        var candidates = erp.warehouses(a.tenantId().toString());
        var ids =
                store.permittedWarehouseIds(
                        a,
                        id,
                        candidates.stream()
                                .map(
                                        com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption
                                                ::id)
                                .toList());
        // 客户归属地区对应的仓库排前面，前端据此默认选中；没有地区或没有匹配仓时保持原顺序。
        String region = store.regionCode(a.tenantId().toString(), id);
        return candidates.stream()
                .filter(w -> ids.contains(w.id()))
                .sorted(
                        java.util.Comparator.comparingInt(
                                        (com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption w) ->
                                                        region != null
                                                                        && region.equals(w.regionCode())
                                                                ? 0
                                                                : 1)
                                .thenComparing(
                                        com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption
                                                ::warehouseName))
                .toList();
    }

    public OrderFulfillmentStatusView status(long id) {
        var a = tenant();
        AuthorizationContext.requirePermission("order:read");
        return store.status(a.tenantId().toString(), id);
    }

    public OrderFulfillmentStatusView select(long id, long warehouse, int revision) {
        var a = tenant();
        AuthorizationContext.requirePermission("order:warehouse:select");
        erp.requireWarehouse(a.tenantId().toString(), warehouse);
        return store.select(a, id, warehouse, revision);
    }

    public OrderFulfillmentStatusView execute(long id, SalesOrderStockOutCommand command) {
        var a = tenant();
        AuthorizationContext.requirePermission("order:outbound:confirm");
        if (command == null || command.warehouseId() == null || command.revision() == null)
            throw new IllegalArgumentException("请选择订单仓库并提供版本");
        var e =
                store.prepare(
                        a,
                        id,
                        command.warehouseId(),
                        command.revision(),
                        command.stockOutTime(),
                        command.remark());
        try {
            var result =
                    erp.receipt(e.tenantId(), e.executionId())
                            .orElseGet(() -> erp.execute(a, e.executionId()));
            return finish(e.tenantId(), e.executionId(), e.orderId(), result);
        } catch (RuntimeException error) {
            store.failed(e.tenantId(), e.executionId(), "执行结果尚未确认，请重试或核对");
            throw error;
        }
    }

    public FulfillmentExecutionView claim(String id) {
        var a = tenant();
        AuthorizationContext.requirePermission("order:outbound:confirm");
        return store.claim(a, id);
    }

    public OrderFulfillmentStatusView reconcile(long id) {
        var a = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(a.principalScope()) || a.tenantId() == null)
            throw new AuthorizationDeniedException("service-caller");
        AuthorizationContext.requirePermission("order:fulfillment:reconcile");
        var status = store.status(a.tenantId().toString(), id);
        if (status.executionId() == null) return status;
        var receipt = erp.receipt(a.tenantId().toString(), status.executionId());
        return receipt.map(
                        r ->
                                finish(
                                        a.tenantId().toString(),
                                        status.executionId(),
                                        status.orderId(),
                                        r))
                .orElse(status);
    }

    private OrderFulfillmentStatusView finish(
            String tenant, String id, long orderId, ErpFulfillmentClient.Receipt r) {
        if (!id.equals(r.executionId()) || orderId != r.orderId())
            throw new IllegalStateException("ERP 执行标识不匹配");
        return store.complete(
                tenant, id, r.stockOutId(), r.stockOutNo(), r.stockOutTime(), r.warehouseId());
    }

    private static CallerIdentity tenant() {
        var c = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(c.principalScope()) || c.tenantId() == null)
            throw new AuthorizationDeniedException("tenant-caller");
        return c;
    }
}
