package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsCostLedger.*;
import com.rigour.order.api.v1.model.ShipmentTransportModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.*;

@Service
public class ShipmentTransportService {
    private final ShipmentTransportStore store;
    private final OrderSalesOrderStore orders;
    private final OrderLogisticsLedgerService ledgers;
    private final OrderLogisticsReferences refs;

    public ShipmentTransportService(
            ShipmentTransportStore store,
            OrderSalesOrderStore orders,
            OrderLogisticsLedgerService ledgers,
            OrderLogisticsReferences refs) {
        this.store = store;
        this.orders = orders;
        this.ledgers = ledgers;
        this.refs = refs;
    }

    @Transactional
    public ShipmentTransportModels.Source sync(Command c) {
        var caller = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission("order:logistics-cost:sync-source");
        if (!"SERVICE".equals(caller.principalScope()) || caller.tenantId() == null)
            throw new AuthorizationDeniedException("shipment-transport-service-only");
        if (c == null
                || c.connectorId() == null
                || blank(c.shipmentId())
                || blank(c.shipmentNo())
                || c.shipmentId().length() > 128
                || c.shipmentNo().length() > 128
                || c.salesOrderId() == null
                || c.shipTime() == null
                || c.trackingNumbers().size() > 100
                || c.trackingNumbers().stream().anyMatch(n -> blank(n) || n.length() > 80)
                || !Set.of("shipped", "receivedin", "received", "cancelled", "unknown")
                        .contains(c.sourceStatus())) throw bad("来源发货信息不完整或状态无效");
        String tenant = caller.tenantId().toString();
        var order =
                orders.salesOrder(tenant, c.salesOrderId()).orElseThrow(() -> bad("来源销售订单尚未同步"));
        var rows =
                ledgers.sources(
                        new LogisticsLedgerSourceQuery(
                                "SALES_ORDER",
                                order.orderNo(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                0,
                                true,
                                true),
                        "order:logistics-cost:read");
        var source =
                rows.stream()
                        .filter(r -> order.orderNo().equals(r.documentNo()))
                        .findFirst()
                        .orElseThrow(() -> bad("来源销售订单不可读取"));
        var warehouse =
                c.warehouseId() == null
                        ? null
                        : ledgers
                                .parties(
                                        "WAREHOUSE",
                                        null,
                                        c.warehouseId(),
                                        null,
                                        null,
                                        0,
                                        "order:logistics-cost:read")
                                .stream()
                                .findFirst()
                                .orElse(null);
        var sender =
                new Party(
                        "FROM",
                        warehouse == null ? "OTHER" : "WAREHOUSE",
                        warehouse == null ? null : warehouse.code(),
                        warehouse == null
                                ? Objects.toString(c.sourceFields().get("stock_name"), null)
                                : warehouse.name(),
                        warehouse == null
                                ? Objects.toString(c.sourceFields().get("stock_name"), null)
                                : warehouse.name(),
                        warehouse == null ? null : warehouse.contactName(),
                        warehouse == null ? null : warehouse.phone(),
                        warehouse == null ? null : warehouse.address(),
                        warehouse == null ? source.cityCode() : warehouse.cityCode());
        var p = c.receiver();
        var receiver =
                new Party(
                        "TO0",
                        "CUSTOMER",
                        source.receiverCode(),
                        p == null
                                ? source.receiverName()
                                : first(p.displayName(), source.receiverName()),
                        p == null
                                ? source.receiverName()
                                : first(p.displayName(), source.receiverName()),
                        p == null
                                ? source.contactName()
                                : first(p.contactName(), source.contactName()),
                        p == null ? source.contactPhone() : first(p.phone(), source.contactPhone()),
                        p == null
                                ? source.destinationAddress()
                                : first(p.address(), source.destinationAddress()),
                        source.cityCode());
        String carrier =
                c.carrierCode() != null
                                && refs.validCodes(tenant, "COST_CARRIER").contains(c.carrierCode())
                        ? c.carrierCode()
                        : null;
        String mode =
                c.deliveryModeCode() != null
                                && refs.validCodes(tenant, "COST_DELIVERY_MODE")
                                        .contains(c.deliveryModeCode())
                        ? c.deliveryModeCode()
                        : null;
        var date = c.shipTime().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var link =
                new LogisticsCostLedger.Source(
                                "SALES_ORDER",
                                order.orderNo(),
                                "COST",
                                List.of(
                                        new Allocation(
                                                "freight",
                                                "TO0",
                                                source.cityCode(),
                                                java.math.BigDecimal.ONE,
                                                java.math.BigDecimal.ZERO)),
                                null)
                        .attributed(source);
        var ledger =
                new LogisticsCostLedger(
                                2,
                                "DELIVERY",
                                mode,
                                carrier,
                                date.toString().substring(0, 7),
                                null,
                                null,
                                sender,
                                List.of(receiver),
                                List.of(
                                        new Fee(
                                                "freight", "FREIGHT", null, "COMPANY", null,
                                                "EQUAL", null, null)),
                                List.of(link),
                                null,
                                null,
                                null,
                                c.warehouseId() == null ? List.of() : List.of(c.warehouseId()),
                                source.cityCode() == null ? List.of() : List.of(source.cityCode()))
                        .withPlatformOrderNo(
                                c.trackingNumbers().size() == 1
                                        ? c.trackingNumbers().getFirst()
                                        : null)
                        .withManualBillItems(List.of());
        var command =
                new LogisticsCostCommand(
                        UUID.randomUUID().toString(),
                        null,
                        "销售订单配送",
                        "DELIVERY",
                        source.cityCode(),
                        null,
                        null,
                        null,
                        date,
                        null,
                        "UNKNOWN",
                        null,
                        null,
                        null,
                        ledger);
        return store.sync(
                tenant, caller.principalId().toString(), c, command, source, c.warehouseId());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String first(String s, String fallback) {
        return blank(s) ? fallback : s;
    }

    private static BusinessException bad(String s) {
        return new BusinessException(ErrorCode.BAD_REQUEST, s, List.of());
    }
}
