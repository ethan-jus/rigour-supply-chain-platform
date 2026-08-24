package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductImageManagementView;
import com.rigour.erp.api.v1.model.ProductManagementDetailView;
import com.rigour.erp.api.v1.model.ProductManagementSummaryView;
import com.rigour.erp.api.v1.model.ProductVariantManagementView;
import com.rigour.erp.application.port.out.ErpProductManagementStore;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductImageWrite;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductSearchCriteria;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductVariantWrite;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductWrite;
import com.rigour.erp.application.port.out.ProductMediaUrlResolver;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductBrandEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductCategoryEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductTagEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductBrandMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductCategoryMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductTagMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** MyBatis-Plus 商品管理仓储；CRUD 内部统一使用 BaseMapper 和 LambdaWrapper。 */
@Repository
public class MybatisPlusProductManagementRepository
        extends ServiceImpl<InternalProductMapper, InternalProductEntity>
        implements ErpProductManagementStore {
    private static final Logger log = LoggerFactory.getLogger(MybatisPlusProductManagementRepository.class);
    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<Object>> RAW_IMAGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    private final InternalProductVariantMapper variantMapper;
    private final InternalProductCategoryMapper categoryMapper;
    private final InternalProductBrandMapper brandMapper;
    private final InternalProductTagMapper tagMapper;
    private final InternalInventoryWarehouseMapper warehouseMapper;
    private final ProductMediaUrlResolver productMediaUrlResolver;
    private final Clock clock;

    public MybatisPlusProductManagementRepository(
            InternalProductMapper mapper,
            InternalProductVariantMapper variantMapper,
            InternalProductCategoryMapper categoryMapper,
            InternalProductBrandMapper brandMapper,
            InternalProductTagMapper tagMapper,
            InternalInventoryWarehouseMapper warehouseMapper,
            ProductMediaUrlResolver productMediaUrlResolver,
            Clock erpClock) {
        this.baseMapper = mapper;
        this.variantMapper = Objects.requireNonNull(variantMapper, "variantMapper");
        this.categoryMapper = Objects.requireNonNull(categoryMapper, "categoryMapper");
        this.brandMapper = Objects.requireNonNull(brandMapper, "brandMapper");
        this.tagMapper = Objects.requireNonNull(tagMapper, "tagMapper");
        this.warehouseMapper = Objects.requireNonNull(warehouseMapper, "warehouseMapper");
        this.productMediaUrlResolver = Objects.requireNonNull(productMediaUrlResolver, "productMediaUrlResolver");
        this.clock = Objects.requireNonNull(erpClock, "erpClock");
    }

    @Override
    public MasterDataPageView<ProductManagementSummaryView> products(
            String tenantId, int begin, int step, ProductSearchCriteria criteria) {
        InternalProductMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalProductEntity> page = mapper.selectList(query(tenantId, criteria)
                .orderByDesc(InternalProductEntity::getUpdatedTime)
                .orderByDesc(InternalProductEntity::getId)
                .last("LIMIT " + step + " OFFSET " + begin));
        Map<Long, List<InternalProductVariantEntity>> variants = variantsByProduct(tenantId, ids(page));
        Map<Long, String> categories = categoryNames(tenantId,
                page.stream().map(InternalProductEntity::getCategoryId).collect(Collectors.toSet()));
        Map<Long, String> brands = brandNames(tenantId,
                page.stream().map(InternalProductEntity::getBrandId).collect(Collectors.toSet()));
        Map<Long, String> warehouses = warehouseNames(tenantId,
                page.stream().map(InternalProductEntity::getDefaultWarehouseId).collect(Collectors.toSet()));
        List<ProductManagementSummaryView> items = page.stream()
                .map(product -> summary(product, variants.getOrDefault(product.getId(), List.of()),
                        categories, brands, warehouses, tenantId))
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<ProductManagementDetailView> product(String tenantId, Long id) {
        return selectActive(tenantId, id).map(product -> detail(tenantId, product,
                variantsByProduct(tenantId, Set.of(product.getId())).getOrDefault(product.getId(), List.of())));
    }

    @Override
    public boolean existsByCode(String tenantId, String productCode) {
        return getBaseMapper().selectCount(Wrappers.<InternalProductEntity>lambdaQuery()
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getProductCode, productCode)) > 0;
    }

    @Override
    public boolean existsVariantByCode(String tenantId, String variantCode) {
        return variantMapper.selectCount(Wrappers.<InternalProductVariantEntity>lambdaQuery()
                .eq(InternalProductVariantEntity::getTenantId, tenantId)
                .eq(InternalProductVariantEntity::getVariantCode, variantCode)) > 0;
    }

    @Override
    public boolean categoryActive(String tenantId, Long categoryId) {
        return categoryMapper.selectCount(Wrappers.<InternalProductCategoryEntity>lambdaQuery()
                .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                .eq(InternalProductCategoryEntity::getId, categoryId)
                .eq(InternalProductCategoryEntity::getDeleted, 0)) > 0;
    }

    @Override
    public boolean brandActive(String tenantId, Long brandId) {
        return brandMapper.selectCount(Wrappers.<InternalProductBrandEntity>lambdaQuery()
                .eq(InternalProductBrandEntity::getTenantId, tenantId)
                .eq(InternalProductBrandEntity::getId, brandId)
                .eq(InternalProductBrandEntity::getDeleted, 0)) > 0;
    }

    @Override
    public boolean warehouseActive(String tenantId, Long warehouseId) {
        return warehouseMapper.selectCount(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                .eq(InternalInventoryWarehouseEntity::getId, warehouseId)
                .eq(InternalInventoryWarehouseEntity::getDeleted, 0)) > 0;
    }

    @Override
    public Set<String> activeTagCodes(String tenantId, Set<String> tagCodes) {
        if (tagCodes == null || tagCodes.isEmpty()) return Set.of();
        return tagMapper.selectList(Wrappers.<InternalProductTagEntity>lambdaQuery()
                        .eq(InternalProductTagEntity::getTenantId, tenantId)
                        .in(InternalProductTagEntity::getTagCode, tagCodes)
                        .eq(InternalProductTagEntity::getDeleted, 0))
                .stream()
                .map(InternalProductTagEntity::getTagCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public Set<Long> activeProductIds(String tenantId, Set<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return Set.of();
        return getBaseMapper().selectList(Wrappers.<InternalProductEntity>lambdaQuery()
                        .eq(InternalProductEntity::getTenantId, tenantId)
                        .in(InternalProductEntity::getId, productIds)
                        .eq(InternalProductEntity::getDeleted, 0))
                .stream()
                .map(InternalProductEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductManagementDetailView create(String tenantId, String productCode,
                                              ProductWrite command, String actorId) {
        LocalDateTime now = now();
        InternalProductEntity entity = productEntity(tenantId, productCode, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
            for (ProductVariantWrite variant : command.variants()) {
                variantMapper.insert(variantEntity(tenantId, entity.getId(), variant, actorId, now));
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("商品编码已存在或商品引用数据无效");
        }
        return product(tenantId, entity.getId()).orElseThrow(() -> notFound("商品不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductManagementDetailView update(String tenantId, Long id, ProductWrite command, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductEntity>lambdaUpdate()
                .set(InternalProductEntity::getProductName, command.productName())
                .set(InternalProductEntity::getCategoryId, command.categoryId())
                .set(InternalProductEntity::getBrandId, command.brandId())
                .set(InternalProductEntity::getProductSpecification, command.productSpecification())
                .set(InternalProductEntity::getUnitCode, command.unitCode())
                .set(InternalProductEntity::getMinOrderQuantity, command.minOrderQuantity())
                .set(InternalProductEntity::getOrderMultipleFlag, command.orderMultipleFlag())
                .set(InternalProductEntity::getOrderMultipleQuantity, command.orderMultipleQuantity())
                .set(InternalProductEntity::getSaleTypeCode, command.saleTypeCode())
                .set(InternalProductEntity::getShelfStatusCode, command.shelfStatusCode())
                .set(InternalProductEntity::getTagCodesJson, json(command.tagCodes()))
                .set(InternalProductEntity::getLimitQuantity, command.limitQuantity())
                .set(InternalProductEntity::getDefaultWarehouseId, command.defaultWarehouseId())
                .set(InternalProductEntity::getImageKeysJson, json(images(command.images())))
                .set(InternalProductEntity::getRecommendProductIdsJson, json(command.recommendProductIds()))
                .set(InternalProductEntity::getSubmitStatusCode, command.submitStatusCode())
                .set(InternalProductEntity::getRemark, command.remark())
                .set(InternalProductEntity::getRevision, command.revision() + 1)
                .set(InternalProductEntity::getUpdatedBy, actorId)
                .set(InternalProductEntity::getUpdatedTime, now)
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getId, id)
                .eq(InternalProductEntity::getRevision, command.revision())
                .eq(InternalProductEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品已被其他人修改，请刷新后重试");
        syncVariants(tenantId, id, command.variants(), actorId, now);
        return product(tenantId, id).orElseThrow(() -> notFound("商品不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductEntity>lambdaUpdate()
                .set(InternalProductEntity::getDeleted, 1)
                .set(InternalProductEntity::getRevision, revision + 1)
                .set(InternalProductEntity::getUpdatedBy, actorId)
                .set(InternalProductEntity::getUpdatedTime, now)
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getId, id)
                .eq(InternalProductEntity::getRevision, revision)
                .eq(InternalProductEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品已被其他人修改，请刷新后重试");
        logicDeleteVariants(tenantId, id, actorId, now);
    }

    private void syncVariants(String tenantId, Long productId, List<ProductVariantWrite> writes,
                              String actorId, LocalDateTime now) {
        Map<Long, InternalProductVariantEntity> current = variantsByProduct(tenantId, Set.of(productId))
                .getOrDefault(productId, List.of())
                .stream()
                .collect(Collectors.toMap(InternalProductVariantEntity::getId, Function.identity(), (a, b) -> a));
        Set<Long> handled = new LinkedHashSet<>();
        for (ProductVariantWrite write : writes) {
            if (write.id() == null) {
                try {
                    variantMapper.insert(variantEntity(tenantId, productId, write, actorId, now));
                } catch (DataIntegrityViolationException exception) {
                    throw conflict("商品规格编码已存在或商品规格引用数据无效");
                }
                continue;
            }
            InternalProductVariantEntity existing = current.get(write.id());
            if (existing == null) throw notFound("商品规格不存在或已删除");
            handled.add(write.id());
            int updated = variantMapper.update(null, Wrappers.<InternalProductVariantEntity>lambdaUpdate()
                    .set(InternalProductVariantEntity::getSpecificationSnapshot, write.specificationSnapshot())
                    .set(InternalProductVariantEntity::getUnitCode, write.unitCode())
                    .set(InternalProductVariantEntity::getSalePrice, write.salePrice())
                    .set(InternalProductVariantEntity::getMarketPrice, write.marketPrice())
                    .set(InternalProductVariantEntity::getPurchasePrice, write.purchasePrice())
                    .set(InternalProductVariantEntity::getMinOrderQuantity, write.minOrderQuantity())
                    .set(InternalProductVariantEntity::getOrderMultipleQuantity, write.orderMultipleQuantity())
                    .set(InternalProductVariantEntity::getLimitQuantity, write.limitQuantity())
                    .set(InternalProductVariantEntity::getDefaultFlag, write.defaultFlag())
                    .set(InternalProductVariantEntity::getRemark, write.remark())
                    .set(InternalProductVariantEntity::getRevision, existing.getRevision() + 1)
                    .set(InternalProductVariantEntity::getUpdatedBy, actorId)
                    .set(InternalProductVariantEntity::getUpdatedTime, now)
                    .eq(InternalProductVariantEntity::getTenantId, tenantId)
                    .eq(InternalProductVariantEntity::getProductId, productId)
                    .eq(InternalProductVariantEntity::getId, write.id())
                    .eq(InternalProductVariantEntity::getRevision, existing.getRevision())
                    .eq(InternalProductVariantEntity::getDeleted, 0));
            if (updated != 1) throw conflict("商品规格已被其他人修改，请刷新后重试");
        }
        current.values().stream()
                .filter(existing -> !handled.contains(existing.getId()))
                .forEach(existing -> logicDeleteVariant(tenantId, productId, existing, actorId, now));
    }

    private void logicDeleteVariants(String tenantId, Long productId, String actorId, LocalDateTime now) {
        variantMapper.selectList(Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .eq(InternalProductVariantEntity::getProductId, productId)
                        .eq(InternalProductVariantEntity::getDeleted, 0))
                .forEach(existing -> logicDeleteVariant(tenantId, productId, existing, actorId, now));
    }

    private void logicDeleteVariant(String tenantId, Long productId, InternalProductVariantEntity existing,
                                    String actorId, LocalDateTime now) {
        int updated = variantMapper.update(null, Wrappers.<InternalProductVariantEntity>lambdaUpdate()
                .set(InternalProductVariantEntity::getDeleted, 1)
                .set(InternalProductVariantEntity::getRevision, existing.getRevision() + 1)
                .set(InternalProductVariantEntity::getUpdatedBy, actorId)
                .set(InternalProductVariantEntity::getUpdatedTime, now)
                .eq(InternalProductVariantEntity::getTenantId, tenantId)
                .eq(InternalProductVariantEntity::getProductId, productId)
                .eq(InternalProductVariantEntity::getId, existing.getId())
                .eq(InternalProductVariantEntity::getRevision, existing.getRevision())
                .eq(InternalProductVariantEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品规格已被其他人修改，请刷新后重试");
    }

    private Optional<InternalProductEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalProductEntity>lambdaQuery()
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getId, id)
                .eq(InternalProductEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private void requireActive(String tenantId, Long id) {
        selectActive(tenantId, id).orElseThrow(() -> notFound("商品不存在"));
    }

    private LambdaQueryWrapper<InternalProductEntity> query(String tenantId, ProductSearchCriteria criteria) {
        LambdaQueryWrapper<InternalProductEntity> query = Wrappers.<InternalProductEntity>lambdaQuery()
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getDeleted, 0);
        if (criteria.productCode() != null) query.like(InternalProductEntity::getProductCode, criteria.productCode());
        if (criteria.productName() != null) query.like(InternalProductEntity::getProductName, criteria.productName());
        if (criteria.categoryId() != null) query.eq(InternalProductEntity::getCategoryId, criteria.categoryId());
        if (criteria.brandId() != null) query.eq(InternalProductEntity::getBrandId, criteria.brandId());
        if (criteria.unitCode() != null) query.eq(InternalProductEntity::getUnitCode, criteria.unitCode());
        if (criteria.saleTypeCode() != null) query.eq(InternalProductEntity::getSaleTypeCode, criteria.saleTypeCode());
        if (criteria.shelfStatusCode() != null) {
            query.eq(InternalProductEntity::getShelfStatusCode, criteria.shelfStatusCode());
        }
        if (criteria.submitStatusCode() != null) {
            query.eq(InternalProductEntity::getSubmitStatusCode, criteria.submitStatusCode());
        }
        if (criteria.defaultWarehouseId() != null) {
            query.eq(InternalProductEntity::getDefaultWarehouseId, criteria.defaultWarehouseId());
        }
        return query;
    }

    private InternalProductEntity productEntity(String tenantId, String productCode, ProductWrite command,
                                                String actorId, LocalDateTime now) {
        InternalProductEntity entity = new InternalProductEntity();
        entity.setTenantId(tenantId);
        entity.setProductCode(productCode);
        entity.setProductName(command.productName());
        entity.setCategoryId(command.categoryId());
        entity.setBrandId(command.brandId());
        entity.setProductSpecification(command.productSpecification());
        entity.setUnitCode(command.unitCode());
        entity.setMinOrderQuantity(command.minOrderQuantity());
        entity.setOrderMultipleFlag(command.orderMultipleFlag());
        entity.setOrderMultipleQuantity(command.orderMultipleQuantity());
        entity.setSaleTypeCode(command.saleTypeCode());
        entity.setShelfStatusCode(command.shelfStatusCode());
        entity.setTagCodesJson(json(command.tagCodes()));
        entity.setLimitQuantity(command.limitQuantity());
        entity.setDefaultWarehouseId(command.defaultWarehouseId());
        entity.setImageKeysJson(json(images(command.images())));
        entity.setRecommendProductIdsJson(json(command.recommendProductIds()));
        entity.setSubmitStatusCode(command.submitStatusCode());
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private InternalProductVariantEntity variantEntity(String tenantId, Long productId, ProductVariantWrite command,
                                                       String actorId, LocalDateTime now) {
        InternalProductVariantEntity entity = new InternalProductVariantEntity();
        entity.setTenantId(tenantId);
        entity.setProductId(productId);
        entity.setVariantCode(command.variantCode());
        entity.setSpecificationSnapshot(command.specificationSnapshot());
        entity.setUnitCode(command.unitCode());
        entity.setSalePrice(command.salePrice());
        entity.setMarketPrice(command.marketPrice());
        entity.setPurchasePrice(command.purchasePrice());
        entity.setMinOrderQuantity(command.minOrderQuantity());
        entity.setOrderMultipleQuantity(command.orderMultipleQuantity());
        entity.setLimitQuantity(command.limitQuantity());
        entity.setDefaultFlag(command.defaultFlag());
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private ProductManagementSummaryView summary(InternalProductEntity entity,
                                                 List<InternalProductVariantEntity> variants,
                                                 Map<Long, String> categories,
                                                 Map<Long, String> brands,
                                                 Map<Long, String> warehouses,
                                                 String tenantId) {
        InternalProductVariantEntity defaultVariant = defaultVariant(variants);
        ProductImageJson mainImage = mainImage(parseImages(entity.getImageKeysJson()));
        String mainImageKey = mainImage == null ? null : mainImage.imageKey();
        return new ProductManagementSummaryView(entity.getId(), entity.getProductCode(), entity.getProductName(),
                entity.getCategoryId(), value(categories, entity.getCategoryId()), entity.getBrandId(),
                value(brands, entity.getBrandId()), entity.getUnitCode(), entity.getSaleTypeCode(),
                entity.getShelfStatusCode(), entity.getSubmitStatusCode(), entity.getDefaultWarehouseId(),
                value(warehouses, entity.getDefaultWarehouseId()),
                defaultVariant == null ? null : defaultVariant.getSalePrice(),
                mainImageKey, temporaryUrl(tenantId, mainImageKey), variants.size(), entity.getRevision(),
                instant(entity.getUpdatedTime()));
    }

    private ProductManagementDetailView detail(String tenantId, InternalProductEntity entity,
                                               List<InternalProductVariantEntity> variants) {
        return new ProductManagementDetailView(entity.getId(), entity.getProductCode(), entity.getProductName(),
                entity.getCategoryId(), categoryName(tenantId, entity.getCategoryId()),
                entity.getBrandId(), brandName(tenantId, entity.getBrandId()), entity.getProductSpecification(),
                entity.getUnitCode(), entity.getMinOrderQuantity(), entity.getOrderMultipleFlag(),
                entity.getOrderMultipleQuantity(), entity.getSaleTypeCode(), entity.getShelfStatusCode(),
                parseStrings(entity.getTagCodesJson()), entity.getLimitQuantity(), entity.getDefaultWarehouseId(),
                warehouseName(tenantId, entity.getDefaultWarehouseId()), imageViews(tenantId, entity.getImageKeysJson()),
                variants.stream().map(MybatisPlusProductManagementRepository::variantView).toList(),
                parseLongs(entity.getRecommendProductIdsJson()), entity.getSubmitStatusCode(), entity.getRemark(),
                entity.getRevision(), entity.getCreatedBy(), instant(entity.getCreatedTime()),
                entity.getUpdatedBy(), instant(entity.getUpdatedTime()));
    }

    private List<ProductImageManagementView> imageViews(String tenantId, String json) {
        return parseImages(json).stream()
                .map(image -> new ProductImageManagementView(
                        image.imageKey(), temporaryUrl(tenantId, image.imageKey()), image.imageTypeCode(),
                        image.ordinal()))
                .toList();
    }

    private static ProductVariantManagementView variantView(InternalProductVariantEntity entity) {
        return new ProductVariantManagementView(entity.getId(), entity.getVariantCode(),
                entity.getSpecificationSnapshot(), entity.getUnitCode(), entity.getSalePrice(),
                entity.getMarketPrice(), entity.getPurchasePrice(), entity.getMinOrderQuantity(),
                entity.getOrderMultipleQuantity(), entity.getLimitQuantity(), entity.getDefaultFlag(),
                entity.getRemark(), entity.getRevision(), instant(entity.getUpdatedTime()));
    }

    private Map<Long, List<InternalProductVariantEntity>> variantsByProduct(String tenantId, Set<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return variantMapper.selectList(Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .in(InternalProductVariantEntity::getProductId, productIds)
                        .eq(InternalProductVariantEntity::getDeleted, 0)
                        .orderByDesc(InternalProductVariantEntity::getDefaultFlag)
                        .orderByAsc(InternalProductVariantEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(InternalProductVariantEntity::getProductId));
    }

    private Map<Long, String> categoryNames(String tenantId, Set<Long> ids) {
        ids.remove(null);
        if (ids.isEmpty()) return Map.of();
        return categoryMapper.selectList(Wrappers.<InternalProductCategoryEntity>lambdaQuery()
                        .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                        .in(InternalProductCategoryEntity::getId, ids)
                        .eq(InternalProductCategoryEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(InternalProductCategoryEntity::getId,
                        InternalProductCategoryEntity::getCategoryName, (a, b) -> a));
    }

    private Map<Long, String> brandNames(String tenantId, Set<Long> ids) {
        ids.remove(null);
        if (ids.isEmpty()) return Map.of();
        return brandMapper.selectList(Wrappers.<InternalProductBrandEntity>lambdaQuery()
                        .eq(InternalProductBrandEntity::getTenantId, tenantId)
                        .in(InternalProductBrandEntity::getId, ids)
                        .eq(InternalProductBrandEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(InternalProductBrandEntity::getId,
                        InternalProductBrandEntity::getBrandName, (a, b) -> a));
    }

    private Map<Long, String> warehouseNames(String tenantId, Set<Long> ids) {
        ids.remove(null);
        if (ids.isEmpty()) return Map.of();
        return warehouseMapper.selectList(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                        .in(InternalInventoryWarehouseEntity::getId, ids)
                        .eq(InternalInventoryWarehouseEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(InternalInventoryWarehouseEntity::getId,
                        InternalInventoryWarehouseEntity::getWarehouseName, (a, b) -> a));
    }

    private String categoryName(String tenantId, Long id) {
        if (id == null) return null;
        return categoryNames(tenantId, new LinkedHashSet<>(List.of(id))).get(id);
    }

    private String brandName(String tenantId, Long id) {
        if (id == null) return null;
        return brandNames(tenantId, new LinkedHashSet<>(List.of(id))).get(id);
    }

    private String warehouseName(String tenantId, Long id) {
        if (id == null) return null;
        return warehouseNames(tenantId, new LinkedHashSet<>(List.of(id))).get(id);
    }

    private String temporaryUrl(String tenantId, String objectKey) {
        if (!StringUtils.hasText(objectKey)) return null;
        try {
            return productMediaUrlResolver.temporaryUrl(tenantId, objectKey);
        } catch (RuntimeException exception) {
            log.warn("ERP商品图片URL生成失败 tenantId={} objectKey={} error={}",
                    tenantId, objectKey, exception.getMessage());
            return null;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalProductVariantEntity defaultVariant(List<InternalProductVariantEntity> variants) {
        if (variants == null || variants.isEmpty()) return null;
        return variants.stream()
                .filter(variant -> Boolean.TRUE.equals(variant.getDefaultFlag()))
                .findFirst()
                .orElse(variants.get(0));
    }

    private static ProductImageJson mainImage(List<ProductImageJson> images) {
        if (images.isEmpty()) return null;
        return images.stream()
                .filter(image -> "MAIN".equals(image.imageTypeCode()))
                .findFirst()
                .orElse(images.get(0));
    }

    private static Set<Long> ids(List<InternalProductEntity> products) {
        return products.stream()
                .map(InternalProductEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<ProductImageJson> images(List<ProductImageWrite> writes) {
        return writes.stream()
                .map(write -> new ProductImageJson(write.imageKey(), write.imageTypeCode(), write.ordinal()))
                .sorted(Comparator.comparing(ProductImageJson::ordinal, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    private static String json(Object value) {
        try {
            return JSON_MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ERP商品管理JSON序列化失败", exception);
        }
    }

    private static List<ProductImageJson> parseImages(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<Object> rawImages = JSON_MAPPER.readValue(json, RAW_IMAGE_LIST_TYPE);
            List<ProductImageJson> images = new ArrayList<>();
            for (int i = 0; i < rawImages.size(); i++) {
                ProductImageJson image = image(rawImages.get(i), i);
                if (image != null && StringUtils.hasText(image.imageKey())) {
                    images.add(image);
                }
            }
            return images.stream()
                    .sorted(Comparator.comparing(ProductImageJson::ordinal, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ERP商品图片JSON反序列化失败", exception);
        }
    }

    private static ProductImageJson image(Object value, int fallbackOrdinal) {
        if (value instanceof ProductImageJson image) {
            return image;
        }
        if (value instanceof String imageKey) {
            return image(imageKey, null, null, fallbackOrdinal);
        }
        if (value instanceof Map<?, ?> map) {
            return image(text(map, "imageKey", "objectKey", "key"),
                    text(map, "imageTypeCode", "typeCode", "imageType"),
                    integer(map, "ordinal", "sortOrder", "sort"),
                    fallbackOrdinal);
        }
        return null;
    }

    private static ProductImageJson image(String imageKey, String imageTypeCode, Integer ordinal,
                                          int fallbackOrdinal) {
        if (!StringUtils.hasText(imageKey)) return null;
        int actualOrdinal = ordinal == null ? fallbackOrdinal : ordinal;
        String actualType = StringUtils.hasText(imageTypeCode)
                ? imageTypeCode.strip()
                : defaultImageTypeCode(actualOrdinal);
        return new ProductImageJson(imageKey.strip(), actualType, actualOrdinal);
    }

    private static String defaultImageTypeCode(int ordinal) {
        return ordinal == 0 ? "MAIN" : "DETAIL";
    }

    private static String text(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).strip();
            }
        }
        return null;
    }

    private static Integer integer(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number number) return number.intValue();
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                try {
                    return Integer.parseInt(String.valueOf(value).strip());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<String> parseStrings(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return JSON_MAPPER.readValue(json, STRING_LIST_TYPE).stream()
                    .filter(StringUtils::hasText)
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ERP商品标签JSON反序列化失败", exception);
        }
    }

    private static List<Long> parseLongs(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return JSON_MAPPER.readValue(json, LONG_LIST_TYPE).stream()
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ERP推荐商品JSON反序列化失败", exception);
        }
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static <K, V> V value(Map<K, V> values, K key) {
        return key == null ? null : values.get(key);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private record ProductImageJson(String imageKey, String imageTypeCode, Integer ordinal) {
    }
}
