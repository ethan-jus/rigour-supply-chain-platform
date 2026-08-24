package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.erp.api.v1.model.InternalStockBalanceView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpStockBalanceStore;
import com.rigour.erp.application.port.out.ErpStockBalanceStore.StockBalanceSearchCriteria;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockBalanceEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockBalanceMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** MyBatis-Plus 库存余额仓储；先查余额分页，再批量补商品、规格和仓库展示字段。 */
@Repository
public class MybatisPlusStockBalanceRepository implements ErpStockBalanceStore {
    private static final int LOOKUP_LIMIT = 5000;

    private final InternalStockBalanceMapper stockBalanceMapper;
    private final InternalProductMapper productMapper;
    private final InternalProductVariantMapper variantMapper;
    private final InternalInventoryWarehouseMapper warehouseMapper;

    public MybatisPlusStockBalanceRepository(
            InternalStockBalanceMapper stockBalanceMapper,
            InternalProductMapper productMapper,
            InternalProductVariantMapper variantMapper,
            InternalInventoryWarehouseMapper warehouseMapper) {
        this.stockBalanceMapper = stockBalanceMapper;
        this.productMapper = productMapper;
        this.variantMapper = variantMapper;
        this.warehouseMapper = warehouseMapper;
    }

    @Override
    public MasterDataPageView<InternalStockBalanceView> stockBalances(
            String tenantId, int begin, int step, StockBalanceSearchCriteria criteria) {
        Set<Long> productIds = productIds(tenantId, criteria);
        if (productIds.isEmpty() && (criteria.productCode() != null || criteria.productName() != null)) {
            return new MasterDataPageView<>(0, begin, step, List.of());
        }
        Set<Long> warehouseIds = warehouseIds(tenantId, criteria);
        if (warehouseIds.isEmpty() && (criteria.warehouseId() != null || criteria.warehouseName() != null)) {
            return new MasterDataPageView<>(0, begin, step, List.of());
        }
        LambdaQueryWrapper<InternalStockBalanceEntity> query = query(tenantId, productIds, warehouseIds);
        long total = stockBalanceMapper.selectCount(query);
        List<InternalStockBalanceEntity> balances = stockBalanceMapper.selectList(query
                .orderByDesc(InternalStockBalanceEntity::getUpdatedTime)
                .orderByDesc(InternalStockBalanceEntity::getId)
                .last("LIMIT " + step + " OFFSET " + begin));
        if (balances.isEmpty()) return new MasterDataPageView<>(total, begin, step, List.of());
        Map<Long, InternalProductEntity> products = products(tenantId, ids(balances, InternalStockBalanceEntity::getProductId));
        Map<Long, InternalProductVariantEntity> variants = variants(tenantId, ids(balances, InternalStockBalanceEntity::getProductVariantId));
        Map<Long, InternalInventoryWarehouseEntity> warehouses = warehouses(tenantId, ids(balances, InternalStockBalanceEntity::getWarehouseId));
        List<InternalStockBalanceView> items = balances.stream()
                .map(balance -> view(balance, products.get(balance.getProductId()),
                        variants.get(balance.getProductVariantId()), warehouses.get(balance.getWarehouseId())))
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    private LambdaQueryWrapper<InternalStockBalanceEntity> query(
            String tenantId, Set<Long> productIds, Set<Long> warehouseIds) {
        LambdaQueryWrapper<InternalStockBalanceEntity> query =
                Wrappers.<InternalStockBalanceEntity>lambdaQuery()
                        .eq(InternalStockBalanceEntity::getTenantId, tenantId);
        if (!productIds.isEmpty()) {
            query.in(InternalStockBalanceEntity::getProductId, productIds);
        }
        if (!warehouseIds.isEmpty()) {
            query.in(InternalStockBalanceEntity::getWarehouseId, warehouseIds);
        }
        return query;
    }

    private Set<Long> productIds(String tenantId, StockBalanceSearchCriteria criteria) {
        if (criteria.productCode() == null && criteria.productName() == null) return Set.of();
        LambdaQueryWrapper<InternalProductEntity> query = Wrappers.<InternalProductEntity>lambdaQuery()
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getDeleted, 0);
        if (criteria.productCode() != null) {
            query.like(InternalProductEntity::getProductCode, criteria.productCode());
        }
        if (criteria.productName() != null) {
            query.like(InternalProductEntity::getProductName, criteria.productName());
        }
        return productMapper.selectList(query.last("LIMIT " + LOOKUP_LIMIT)).stream()
                .map(InternalProductEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Long> warehouseIds(String tenantId, StockBalanceSearchCriteria criteria) {
        Set<Long> ids = new LinkedHashSet<>();
        if (criteria.warehouseId() != null) ids.add(criteria.warehouseId());
        if (criteria.warehouseName() == null) return ids;
        Set<Long> named = warehouseMapper.selectList(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                        .eq(InternalInventoryWarehouseEntity::getDeleted, 0)
                        .like(InternalInventoryWarehouseEntity::getWarehouseName, criteria.warehouseName())
                        .last("LIMIT " + LOOKUP_LIMIT))
                .stream()
                .map(InternalInventoryWarehouseEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (criteria.warehouseId() == null) return named;
        ids.retainAll(named);
        return ids;
    }

    private Map<Long, InternalProductEntity> products(String tenantId, Set<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return productMapper.selectList(Wrappers.<InternalProductEntity>lambdaQuery()
                        .eq(InternalProductEntity::getTenantId, tenantId)
                        .in(InternalProductEntity::getId, ids))
                .stream()
                .collect(Collectors.toMap(InternalProductEntity::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, InternalProductVariantEntity> variants(String tenantId, Set<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return variantMapper.selectList(Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .in(InternalProductVariantEntity::getId, ids))
                .stream()
                .collect(Collectors.toMap(InternalProductVariantEntity::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, InternalInventoryWarehouseEntity> warehouses(String tenantId, Set<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return warehouseMapper.selectList(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                        .in(InternalInventoryWarehouseEntity::getId, ids))
                .stream()
                .collect(Collectors.toMap(InternalInventoryWarehouseEntity::getId, Function.identity(), (a, b) -> a));
    }

    private static <T> Set<Long> ids(Collection<T> rows, Function<T, Long> getter) {
        Set<Long> ids = new LinkedHashSet<>();
        for (T row : rows) {
            Long id = getter.apply(row);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static InternalStockBalanceView view(
            InternalStockBalanceEntity balance,
            InternalProductEntity product,
            InternalProductVariantEntity variant,
            InternalInventoryWarehouseEntity warehouse) {
        String unitCode = variant == null ? null : variant.getUnitCode();
        if (unitCode == null && product != null) unitCode = product.getUnitCode();
        return new InternalStockBalanceView(
                balance.getId(),
                balance.getWarehouseId(),
                warehouse == null ? null : warehouse.getWarehouseCode(),
                warehouse == null ? null : warehouse.getWarehouseName(),
                balance.getProductId(),
                product == null ? null : product.getProductCode(),
                product == null ? null : product.getProductName(),
                balance.getProductVariantId(),
                variant == null ? null : variant.getVariantCode(),
                variant == null ? null : variant.getSpecificationSnapshot(),
                unitCode,
                balance.getAvailableQuantity(),
                balance.getLockedQuantity(),
                balance.getInTransitQuantity(),
                balance.getRevision(),
                instant(balance.getUpdatedTime()));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
