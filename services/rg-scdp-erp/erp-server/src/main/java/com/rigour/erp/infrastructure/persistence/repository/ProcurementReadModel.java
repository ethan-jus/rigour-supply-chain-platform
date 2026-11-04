package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.erp.api.v1.model.InternalProcurementOrderLineView;
import com.rigour.erp.domain.model.supply.PurchaseUnitConversion;
import com.rigour.erp.infrastructure.persistence.entity.*;
import com.rigour.erp.infrastructure.persistence.mapper.*;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 编码关联的采购读模型；名称批量查主档，履约数量只从有效入库记录取得。 */
@Component
public final class ProcurementReadModel {
    private final InternalSupplierProfileMapper suppliers;
    private final InternalInventoryWarehouseMapper warehouses;
    private final InternalProductMapper products;
    private final InternalProductVariantMapper variants;
    private final InternalProcurementOrderLineMapper lines;
    private final ProcurementReferenceMapper references;

    public ProcurementReadModel(
            InternalSupplierProfileMapper suppliers,
            InternalInventoryWarehouseMapper warehouses,
            InternalProductMapper products,
            InternalProductVariantMapper variants,
            InternalProcurementOrderLineMapper lines,
            ProcurementReferenceMapper references) {
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.products = products;
        this.variants = variants;
        this.lines = lines;
        this.references = references;
    }

    public record Party(Long id, String code, String name) {}

    public record Parties(Party supplier, Party warehouse) {}

    public record Line(
            InternalProcurementOrderLineView view,
            String inventoryUnitCode,
            BigDecimal inventoryRate) {}

    public Map<Long, Parties> parties(String tenant, List<InternalProcurementOrderEntity> orders) {
        if (orders.isEmpty()) return Map.of();
        Set<String> supplierCodes = values(orders, InternalProcurementOrderEntity::getSupplierCode);
        Set<Long> supplierIds = values(orders, InternalProcurementOrderEntity::getSupplierId);
        Set<String> warehouseCodes =
                values(orders, InternalProcurementOrderEntity::getTargetWarehouseCode);
        Set<Long> warehouseIds =
                values(orders, InternalProcurementOrderEntity::getTargetWarehouseId);
        var sq =
                Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                        .eq(InternalSupplierProfileEntity::getTenantId, tenant);
        sq.and(
                q -> {
                    q.in(
                                    !supplierCodes.isEmpty(),
                                    InternalSupplierProfileEntity::getSupplierCode,
                                    supplierCodes)
                            .or()
                            .in(
                                    !supplierIds.isEmpty(),
                                    InternalSupplierProfileEntity::getId,
                                    supplierIds);
                });
        var wq =
                Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenant);
        wq.and(
                q -> {
                    q.in(
                                    !warehouseCodes.isEmpty(),
                                    InternalInventoryWarehouseEntity::getWarehouseCode,
                                    warehouseCodes)
                            .or()
                            .in(
                                    !warehouseIds.isEmpty(),
                                    InternalInventoryWarehouseEntity::getId,
                                    warehouseIds);
                });
        var ss =
                supplierCodes.isEmpty() && supplierIds.isEmpty()
                        ? List.<InternalSupplierProfileEntity>of()
                        : suppliers.selectList(sq);
        var ws =
                warehouseCodes.isEmpty() && warehouseIds.isEmpty()
                        ? List.<InternalInventoryWarehouseEntity>of()
                        : warehouses.selectList(wq);
        Map<Long, Parties> result = new LinkedHashMap<>();
        for (var order : orders) {
            var s =
                    ss.stream()
                            .filter(
                                    x ->
                                            order.getSupplierCode() != null
                                                    ? order.getSupplierCode()
                                                            .equals(x.getSupplierCode())
                                                    : Objects.equals(
                                                            order.getSupplierId(), x.getId()))
                            .findFirst()
                            .orElse(null);
            var w =
                    ws.stream()
                            .filter(
                                    x ->
                                            order.getTargetWarehouseCode() != null
                                                    ? order.getTargetWarehouseCode()
                                                            .equals(x.getWarehouseCode())
                                                    : Objects.equals(
                                                            order.getTargetWarehouseId(),
                                                            x.getId()))
                            .findFirst()
                            .orElse(null);
            result.put(
                    order.getId(),
                    new Parties(
                            s == null
                                    ? new Party(
                                            null,
                                            order.getSupplierCode(),
                                            missing(order.getSupplierCode()))
                                    : new Party(
                                            s.getId(), s.getSupplierCode(), s.getSupplierName()),
                            w == null
                                    ? new Party(
                                            null,
                                            order.getTargetWarehouseCode(),
                                            missing(order.getTargetWarehouseCode()))
                                    : new Party(
                                            w.getId(),
                                            w.getWarehouseCode(),
                                            w.getWarehouseName())));
        }
        return result;
    }

    public List<Line> lines(String tenant, InternalProcurementOrderEntity order) {
        var rows =
                lines.selectList(
                        Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                                .eq(InternalProcurementOrderLineEntity::getTenantId, tenant)
                                .and(
                                        q ->
                                                q.eq(
                                                                InternalProcurementOrderLineEntity
                                                                        ::getProcurementNo,
                                                                order.getProcurementNo())
                                                        .or()
                                                        .eq(
                                                                InternalProcurementOrderLineEntity
                                                                        ::getProcurementOrderId,
                                                                order.getId()))
                                .eq(InternalProcurementOrderLineEntity::getDeleted, 0)
                                .orderByAsc(InternalProcurementOrderLineEntity::getId));
        if (rows.isEmpty()) return List.of();
        Set<String> pc = values(rows, InternalProcurementOrderLineEntity::getProductCode);
        Set<Long> pi = values(rows, InternalProcurementOrderLineEntity::getProductId);
        Set<String> vc = values(rows, InternalProcurementOrderLineEntity::getVariantCode);
        Set<Long> vi = values(rows, InternalProcurementOrderLineEntity::getProductVariantId);
        var pq =
                Wrappers.<InternalProductEntity>lambdaQuery()
                        .eq(InternalProductEntity::getTenantId, tenant);
        pq.and(
                q ->
                        q.in(!pc.isEmpty(), InternalProductEntity::getProductCode, pc)
                                .or()
                                .in(!pi.isEmpty(), InternalProductEntity::getId, pi));
        var vq =
                Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenant);
        vq.and(
                q ->
                        q.in(!vc.isEmpty(), InternalProductVariantEntity::getVariantCode, vc)
                                .or()
                                .in(!vi.isEmpty(), InternalProductVariantEntity::getId, vi));
        var ps =
                pc.isEmpty() && pi.isEmpty()
                        ? List.<InternalProductEntity>of()
                        : products.selectList(pq);
        var vs =
                vc.isEmpty() && vi.isEmpty()
                        ? List.<InternalProductVariantEntity>of()
                        : variants.selectList(vq);
        var receipts =
                references.receipts(tenant, order.getProcurementNo()).stream()
                        .collect(
                                Collectors.toMap(
                                        ProcurementReferenceMapper.ReceiptTotal::lineId,
                                        Function.identity()));
        List<Line> result = new ArrayList<>();
        for (var row : rows) {
            var p =
                    ps.stream()
                            .filter(
                                    x ->
                                            row.getProductCode() != null
                                                    ? row.getProductCode()
                                                            .equals(x.getProductCode())
                                                    : Objects.equals(row.getProductId(), x.getId()))
                            .findFirst()
                            .orElse(null);
            var v =
                    vs.stream()
                            .filter(
                                    x ->
                                            row.getVariantCode() != null
                                                    ? row.getVariantCode()
                                                            .equals(x.getVariantCode())
                                                    : Objects.equals(
                                                            row.getProductVariantId(), x.getId()))
                            .findFirst()
                            .orElse(null);
            var receipt = receipts.get(row.getId());
            boolean external =
                    order.getSourceSystemCode() != null && !order.getSourceSystemCode().isBlank();
            boolean verified =
                    receipt != null
                            ? receipt.invalidUnits() == 0
                            : !external
                                    && (row.getConversionRate() != null
                                            || row.getReceivedQuantity() == null
                                            || row.getReceivedQuantity().signum() == 0);
            BigDecimal receivedBase =
                    receipt != null && receipt.invalidUnits() == 0
                            ? receipt.receivedBaseQuantity()
                            : row.getConversionRate() != null
                                    ? BigDecimal.ZERO
                                    : row.getReceivedQuantity();
            BigDecimal received =
                    receivedBase == null
                            ? null
                            : receivedBase.divide(
                                    row.getConversionRate() == null
                                            ? BigDecimal.ONE
                                            : row.getConversionRate(),
                                    6,
                                    java.math.RoundingMode.HALF_UP);
            if (received == null && verified) received = BigDecimal.ZERO;
            String productCode = p == null ? row.getProductCode() : p.getProductCode();
            String variantCode = v == null ? row.getVariantCode() : v.getVariantCode();
            String inventoryUnit =
                    v == null
                            ? null
                            : v.getUnitCode() == null && p != null
                                    ? p.getUnitCode()
                                    : v.getUnitCode();
            BigDecimal inventoryRate = null;
            if (row.getBaseUnitCode() == null)
                inventoryRate =
                        Objects.equals(inventoryUnit, row.getUnitCode()) ? BigDecimal.ONE : null;
            else if (p != null && Objects.equals(row.getBaseUnitCode(), p.getUnitCode())) {
                inventoryRate =
                        PurchaseUnitConversion.factor(
                                        inventoryUnit,
                                        p.getUnitCode(),
                                        p.getMiddleUnitCode(),
                                        p.getBaseToMiddleRate(),
                                        p.getBigUnitCode(),
                                        p.getBaseToBigRate())
                                .orElse(null);
                BigDecimal current =
                        PurchaseUnitConversion.factor(
                                        row.getUnitCode(),
                                        p.getUnitCode(),
                                        p.getMiddleUnitCode(),
                                        p.getBaseToMiddleRate(),
                                        p.getBigUnitCode(),
                                        p.getBaseToBigRate())
                                .orElse(null);
                if (current == null || current.compareTo(row.getConversionRate()) != 0)
                    inventoryRate = null;
            }
            result.add(
                    new Line(
                            new InternalProcurementOrderLineView(
                                    row.getId(),
                                    result.size() + 1,
                                    p == null ? null : p.getId(),
                                    v == null ? null : v.getId(),
                                    productCode,
                                    variantCode,
                                    p == null ? missing(productCode) : p.getProductName(),
                                    row.getUnitCode(),
                                    row.getQuantity(),
                                    row.getUnitPrice(),
                                    row.getLineAmount(),
                                    received,
                                    row.getRemark(),
                                    row.getBaseUnitCode(),
                                    row.getConversionRate(),
                                    v == null ? null : v.getSpecificationSnapshot(),
                                    verified,
                                    receivedBase),
                            inventoryUnit,
                            inventoryRate));
        }
        return result;
    }

    private static <T, V> Set<V> values(List<T> rows, Function<T, V> value) {
        return rows.stream()
                .map(value)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String missing(String code) {
        return (code == null ? "未知编码" : code) + "（资料缺失）";
    }
}
