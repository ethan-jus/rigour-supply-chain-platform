package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportItem;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportResult;
import com.rigour.erp.api.v1.model.CustomerTypePriceItem;
import com.rigour.erp.api.v1.model.CustomerTypePriceView;
import com.rigour.erp.application.port.out.ErpCustomerTypePriceStore;
import com.rigour.erp.infrastructure.persistence.entity.InternalCustomerTypePriceEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalCustomerTypePriceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 客户类型等级价仓储；按商品批量查询，按商品规格整体保存。 */
@Repository
public class MybatisPlusCustomerTypePriceRepository
        extends ServiceImpl<InternalCustomerTypePriceMapper, InternalCustomerTypePriceEntity>
        implements ErpCustomerTypePriceStore {
    private final InternalProductMapper productMapper;
    private final InternalProductVariantMapper variantMapper;
    private final Clock clock;

    public MybatisPlusCustomerTypePriceRepository(
            InternalCustomerTypePriceMapper mapper,
            InternalProductMapper productMapper,
            InternalProductVariantMapper variantMapper,
            Clock erpClock) {
        this.baseMapper = mapper;
        this.productMapper = productMapper;
        this.variantMapper = variantMapper;
        this.clock = erpClock;
    }

    @Override
    public List<CustomerTypePriceView> pricesByProductIds(String tenantId, List<Long> productIds) {
        if (productIds.isEmpty()) return List.of();
        List<InternalCustomerTypePriceEntity> rows = getBaseMapper().selectList(
                Wrappers.<InternalCustomerTypePriceEntity>lambdaQuery()
                        .eq(InternalCustomerTypePriceEntity::getTenantId, tenantId)
                        .in(InternalCustomerTypePriceEntity::getProductId, productIds)
                        .eq(InternalCustomerTypePriceEntity::getDeleted, 0)
                        .orderByAsc(InternalCustomerTypePriceEntity::getProductId)
                        .orderByAsc(InternalCustomerTypePriceEntity::getProductVariantId)
                        .orderByAsc(InternalCustomerTypePriceEntity::getCustomerTypeCode));
        return views(rows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CustomerTypePriceView> syncVariantPrices(
            String tenantId, Long productVariantId, List<CustomerTypePriceItem> items, String actorId) {
        InternalProductVariantEntity variant = requireVariant(tenantId, productVariantId);
        LocalDateTime now = now();
        Map<String, InternalCustomerTypePriceEntity> existingByType = getBaseMapper().selectList(
                        Wrappers.<InternalCustomerTypePriceEntity>lambdaQuery()
                                .eq(InternalCustomerTypePriceEntity::getTenantId, tenantId)
                                .eq(InternalCustomerTypePriceEntity::getProductVariantId, productVariantId))
                .stream()
                .collect(Collectors.toMap(
                        InternalCustomerTypePriceEntity::getCustomerTypeCode,
                        item -> item,
                        (left, right) -> left));
        Set<String> submitted = items.stream()
                .map(CustomerTypePriceItem::customerTypeCode)
                .collect(Collectors.toSet());
        try {
            for (CustomerTypePriceItem item : items) {
                InternalCustomerTypePriceEntity existing = existingByType.get(item.customerTypeCode());
                if (existing == null) {
                    insert(tenantId, productVariantId, variant.getProductId(), item.customerTypeCode(),
                            item.salePrice(), item.remark(), actorId, now);
                    continue;
                }
                int updated = getBaseMapper().update(null, Wrappers.<InternalCustomerTypePriceEntity>lambdaUpdate()
                        .set(InternalCustomerTypePriceEntity::getSalePrice, item.salePrice())
                        .set(InternalCustomerTypePriceEntity::getRemark, item.remark())
                        .set(InternalCustomerTypePriceEntity::getDeleted, 0)
                        .set(InternalCustomerTypePriceEntity::getRevision, nextRevision(existing.getRevision()))
                        .set(InternalCustomerTypePriceEntity::getUpdatedBy, actorId)
                        .set(InternalCustomerTypePriceEntity::getUpdatedTime, now)
                        .eq(InternalCustomerTypePriceEntity::getTenantId, tenantId)
                        .eq(InternalCustomerTypePriceEntity::getId, existing.getId()));
                if (updated != 1) throw conflict("等级价保存冲突，请刷新后重试");
            }
            existingByType.values().stream()
                    .filter(row -> row.getDeleted() == null || row.getDeleted() == 0)
                    .filter(row -> !submitted.contains(row.getCustomerTypeCode()))
                    .forEach(row -> {
                        int updated = getBaseMapper().update(null,
                                Wrappers.<InternalCustomerTypePriceEntity>lambdaUpdate()
                                        .set(InternalCustomerTypePriceEntity::getDeleted, 1)
                                        .set(InternalCustomerTypePriceEntity::getRevision,
                                                nextRevision(row.getRevision()))
                                        .set(InternalCustomerTypePriceEntity::getUpdatedBy, actorId)
                                        .set(InternalCustomerTypePriceEntity::getUpdatedTime, now)
                                        .eq(InternalCustomerTypePriceEntity::getTenantId, tenantId)
                                        .eq(InternalCustomerTypePriceEntity::getId, row.getId())
                                        .eq(InternalCustomerTypePriceEntity::getDeleted, 0));
                        if (updated != 1) throw conflict("等级价保存冲突，请刷新后重试");
                    });
        } catch (DataIntegrityViolationException exception) {
            throw conflict("等级价保存冲突，请刷新后重试");
        }
        return viewsByVariant(tenantId, productVariantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerTypePriceImportResult importPrices(
            String tenantId, List<CustomerTypePriceImportItem> items, String actorId) {
        Set<Long> variantIds = items.stream()
                .map(CustomerTypePriceImportItem::productVariantId)
                .collect(Collectors.toSet());
        Map<Long, InternalProductVariantEntity> variants = variantsByIds(variantIds);
        if (variants.size() != variantIds.size()) throw notFound("商品规格不存在或已删除");
        LocalDateTime now = now();
        Map<String, InternalCustomerTypePriceEntity> existingByKey = getBaseMapper().selectList(
                        Wrappers.<InternalCustomerTypePriceEntity>lambdaQuery()
                                .eq(InternalCustomerTypePriceEntity::getTenantId, tenantId)
                                .in(InternalCustomerTypePriceEntity::getProductVariantId, variantIds))
                .stream()
                .collect(Collectors.toMap(
                        row -> importKey(row.getProductVariantId(), row.getCustomerTypeCode()),
                        row -> row, (left, right) -> left));
        int created = 0;
        int updated = 0;
        try {
            for (CustomerTypePriceImportItem item : items) {
                InternalProductVariantEntity variant = variants.get(item.productVariantId());
                if (!tenantId.equals(variant.getTenantId())
                        || variant.getDeleted() == null
                        || variant.getDeleted() != 0) {
                    throw notFound("商品规格不存在或已删除");
                }
                InternalCustomerTypePriceEntity existing =
                        existingByKey.get(importKey(item.productVariantId(), item.customerTypeCode()));
                if (existing == null) {
                    insert(tenantId, item.productVariantId(), variant.getProductId(), item.customerTypeCode(),
                            item.salePrice(), item.remark(), actorId, now);
                    created++;
                    continue;
                }
                int rows = getBaseMapper().update(null, Wrappers.<InternalCustomerTypePriceEntity>lambdaUpdate()
                        .set(InternalCustomerTypePriceEntity::getSalePrice, item.salePrice())
                        .set(InternalCustomerTypePriceEntity::getRemark, item.remark())
                        .set(InternalCustomerTypePriceEntity::getDeleted, 0)
                        .set(InternalCustomerTypePriceEntity::getRevision, nextRevision(existing.getRevision()))
                        .set(InternalCustomerTypePriceEntity::getUpdatedBy, actorId)
                        .set(InternalCustomerTypePriceEntity::getUpdatedTime, now)
                        .eq(InternalCustomerTypePriceEntity::getTenantId, tenantId)
                        .eq(InternalCustomerTypePriceEntity::getId, existing.getId()));
                if (rows != 1) throw conflict("等级价保存冲突，请刷新后重试");
                updated++;
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("等级价导入冲突，请刷新后重试");
        }
        return new CustomerTypePriceImportResult(items.size(), created, updated);
    }

    private static String importKey(Long productVariantId, String customerTypeCode) {
        return productVariantId + "::" + customerTypeCode;
    }

    private void insert(String tenantId, Long productVariantId, Long productId, String customerTypeCode,
                        BigDecimal salePrice, String remark, String actorId, LocalDateTime now) {
        InternalCustomerTypePriceEntity entity = new InternalCustomerTypePriceEntity();
        entity.setTenantId(tenantId);
        entity.setProductId(productId);
        entity.setProductVariantId(productVariantId);
        entity.setCustomerTypeCode(customerTypeCode);
        entity.setSalePrice(salePrice);
        entity.setRemark(remark);
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        getBaseMapper().insert(entity);
    }

    private List<CustomerTypePriceView> viewsByVariant(String tenantId, Long productVariantId) {
        List<InternalCustomerTypePriceEntity> rows = getBaseMapper().selectList(
                Wrappers.<InternalCustomerTypePriceEntity>lambdaQuery()
                        .eq(InternalCustomerTypePriceEntity::getTenantId, tenantId)
                        .eq(InternalCustomerTypePriceEntity::getProductVariantId, productVariantId)
                        .eq(InternalCustomerTypePriceEntity::getDeleted, 0)
                        .orderByAsc(InternalCustomerTypePriceEntity::getCustomerTypeCode));
        return views(rows);
    }

    private List<CustomerTypePriceView> views(List<InternalCustomerTypePriceEntity> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, InternalProductEntity> products = productsByIds(rows.stream()
                .map(InternalCustomerTypePriceEntity::getProductId).collect(Collectors.toSet()));
        Map<Long, InternalProductVariantEntity> variants = variantsByIds(rows.stream()
                .map(InternalCustomerTypePriceEntity::getProductVariantId).collect(Collectors.toSet()));
        return rows.stream()
                .map(row -> view(row, products.get(row.getProductId()), variants.get(row.getProductVariantId())))
                .toList();
    }

    private Map<Long, InternalProductEntity> productsByIds(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return productMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(InternalProductEntity::getId, item -> item, (left, right) -> left));
    }

    private Map<Long, InternalProductVariantEntity> variantsByIds(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return variantMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(InternalProductVariantEntity::getId, item -> item, (left, right) -> left));
    }

    private InternalProductVariantEntity requireVariant(String tenantId, Long productVariantId) {
        InternalProductVariantEntity variant = variantMapper.selectById(productVariantId);
        if (variant == null
                || !tenantId.equals(variant.getTenantId())
                || variant.getDeleted() == null
                || variant.getDeleted() != 0) {
            throw notFound("商品规格不存在或已删除");
        }
        return variant;
    }

    private static CustomerTypePriceView view(InternalCustomerTypePriceEntity entity,
                                              InternalProductEntity product,
                                              InternalProductVariantEntity variant) {
        return new CustomerTypePriceView(
                entity.getId(),
                entity.getProductId(),
                product == null ? null : product.getProductCode(),
                product == null ? null : product.getProductName(),
                entity.getProductVariantId(),
                variant == null ? null : variant.getVariantCode(),
                variant == null ? null : variant.getSpecificationSnapshot(),
                entity.getCustomerTypeCode(),
                entity.getSalePrice(),
                entity.getRemark(),
                entity.getRevision(),
                entity.getCreatedBy(),
                instant(entity.getCreatedTime()),
                entity.getUpdatedBy(),
                instant(entity.getUpdatedTime()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static int nextRevision(Integer revision) {
        return (revision == null ? 1 : revision) + 1;
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
