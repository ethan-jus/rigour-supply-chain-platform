package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.*;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.shared.context.*;
import com.rigour.shared.core.code.BusinessCodeGenerator;

import org.springframework.stereotype.Service;

import java.util.*;

/** 新出库必须经过 Order 当前授权和执行意图核验；SERVICE 恢复只能查询已执行回执。 */
@Service
public final class ErpOrderExecutionService {
    private final OrderExecutionClient orders;
    private final ErpOrderExecutionStore executions;
    private final ErpStockOutOrderStore stock;
    private final ErpInventoryWarehouseStore warehouses;
    private final ErpStockOutOrderService validation;

    public ErpOrderExecutionService(
            OrderExecutionClient orders,
            ErpOrderExecutionStore executions,
            ErpStockOutOrderStore stock,
            ErpInventoryWarehouseStore warehouses,
            ErpStockOutOrderService validation) {
        this.orders = orders;
        this.executions = executions;
        this.stock = stock;
        this.warehouses = warehouses;
        this.validation = validation;
    }

    public SalesExecutionReceipt execute(String id) {
        UUID.fromString(id);
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope()) || actor.tenantId() == null)
            throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission("order:outbound:confirm");
        // 即使 ERP 已执行，普通 POST 仍要求当前动作资格；恢复 GET 使用独立服务权限。
        var prior = executions.receipt(actor.tenantId().toString(), id);
        if (prior.isPresent()) return prior.get();
        var c = orders.claim(actor, id);
        if (!actor.tenantId().toString().equals(c.tenantId()) || !id.equals(c.executionId()))
            throw new IllegalStateException("Order 执行核验返回不一致");
        var command =
                new InternalSalesStockOutCommand(
                        c.orderId(),
                        c.orderNo(),
                        c.warehouseId(),
                        c.customerId(),
                        c.customerName(),
                        c.stockOutTime(),
                        c.lines().stream()
                                .map(
                                        l ->
                                                new InternalSalesStockOutLineCommand(
                                                        l.orderLineId(),
                                                        l.productId(),
                                                        l.variantId(),
                                                        l.productCode(),
                                                        l.variantCode(),
                                                        l.productName(),
                                                        l.unitCode(),
                                                        l.quantity(),
                                                        l.remark()))
                                .toList(),
                        c.remark());
        return executions.execute(
                c,
                () -> {
                    var normalized = validation.normalizeOrderExecution(c.tenantId(), command);
                    String no =
                            new BusinessCodeGenerator()
                                    .generateUnique(
                                            ErpBusinessCodeRules.STOCK_OUT_ORDER,
                                            n -> !stock.existsByStockOutNo(c.tenantId(), n));
                    return stock.confirmSalesStockOut(
                            c.tenantId(), no, normalized, actor.principalId().toString());
                });
    }

    public SalesExecutionReceipt receipt(String id) {
        var c = service("erp:execution:receipt-read");
        UUID.fromString(id);
        return executions.receipt(c.tenantId().toString(), id).orElse(null);
    }

    public boolean warehouseUsable(long id) {
        var c = service("erp:warehouse:identity-read");
        if (id < 1) return false;
        return warehouses
                .warehouse(c.tenantId().toString(), id)
                .filter(w -> "ACTIVE".equals(w.statusCode()))
                .isPresent();
    }

    private static CallerIdentity service(String permission) {
        var c = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(c.principalScope()) || c.tenantId() == null)
            throw new AuthorizationDeniedException("service-caller");
        AuthorizationContext.requirePermission(permission);
        return c;
    }
}
