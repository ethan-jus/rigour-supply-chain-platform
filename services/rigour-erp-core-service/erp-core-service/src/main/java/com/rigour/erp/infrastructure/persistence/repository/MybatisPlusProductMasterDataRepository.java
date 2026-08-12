package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.erp.api.v1.model.BrandView;
import com.rigour.erp.api.v1.model.CategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductImageView;
import com.rigour.erp.api.v1.model.ProductPageView;
import com.rigour.erp.api.v1.model.ProductView;
import com.rigour.erp.api.v1.model.SkuPageView;
import com.rigour.erp.api.v1.model.SkuView;
import com.rigour.erp.api.v1.model.SpecificationView;
import com.rigour.erp.api.v1.model.SpecificationValueView;
import com.rigour.erp.api.v1.model.TagView;
import com.rigour.erp.application.port.out.ProductMasterDataStore;
import com.rigour.erp.application.port.out.ProductMediaUrlResolver;
import com.rigour.erp.domain.model.product.Brand;
import com.rigour.erp.domain.model.product.Category;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.ProductImage;
import com.rigour.erp.domain.model.product.Sku;
import com.rigour.erp.domain.model.product.Specification;
import com.rigour.erp.domain.model.product.SpecificationValue;
import com.rigour.erp.domain.model.product.Tag;
import com.rigour.erp.infrastructure.persistence.entity.BrandEntity;
import com.rigour.erp.infrastructure.persistence.entity.CategoryEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncRunEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncLockEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterSourceBindingEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductCustomFieldEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductImageEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductInventoryPolicyEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductPriceEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductSkuEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductSkuSpecificationValueEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductSpuCategoryEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductSpuEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductSpuSpecificationEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductTagEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductUnitEntity;
import com.rigour.erp.infrastructure.persistence.entity.SpecificationEntity;
import com.rigour.erp.infrastructure.persistence.entity.SpecificationValueEntity;
import com.rigour.erp.infrastructure.persistence.entity.TagGroupEntity;
import com.rigour.erp.infrastructure.persistence.mapper.BrandMapper;
import com.rigour.erp.infrastructure.persistence.mapper.CategoryMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncLockMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductCustomFieldMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductImageMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductInventoryPolicyMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductPriceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSkuMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSkuSpecificationValueMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSpuCategoryMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSpuMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSpuSpecificationMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductTagMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductUnitMapper;
import com.rigour.erp.infrastructure.persistence.mapper.SpecificationMapper;
import com.rigour.erp.infrastructure.persistence.mapper.SpecificationValueMapper;
import com.rigour.erp.infrastructure.persistence.mapper.TagGroupMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * ERP 商品主数据 MyBatis-Plus Repository。
 *
 * <p>结构和 order-center 的 MybatisPlus*Repository 保持一致：Entity 只描述表，Mapper 只负责表操作，
 * 本类负责 ERP 聚合的查询组装、来源幂等绑定和事务边界。订货宝来源不会直接覆盖 ERP 内部状态。</p>
 */
@Repository
public class MybatisPlusProductMasterDataRepository implements ProductMasterDataStore {
    private static final Logger log = LoggerFactory.getLogger(MybatisPlusProductMasterDataRepository.class);
    private static final String SOURCE_SYSTEM = "DINGHUOBAO";
    private static final String EXTERNAL_PRIMARY = "EXTERNAL_PRIMARY";

    private final ProductSpuMapper productSpuMapper;
    private final ProductSkuMapper productSkuMapper;
    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;
    private final SpecificationMapper specificationMapper;
    private final SpecificationValueMapper specificationValueMapper;
    private final ProductTagMapper productTagMapper;
    private final TagGroupMapper tagGroupMapper;
    private final ProductImageMapper productImageMapper;
    private final ProductPriceMapper productPriceMapper;
    private final ProductUnitMapper productUnitMapper;
    private final ProductInventoryPolicyMapper productInventoryPolicyMapper;
    private final ProductCustomFieldMapper productCustomFieldMapper;
    private final ProductSpuCategoryMapper productSpuCategoryMapper;
    private final ProductSpuSpecificationMapper productSpuSpecificationMapper;
    private final ProductSkuSpecificationValueMapper productSkuSpecificationValueMapper;
    private final MasterSourceBindingMapper bindingMapper;
    private final MasterDataSyncRunMapper syncRunMapper;
    private final MasterDataSyncLockMapper syncLockMapper;
    private final ProductMediaUrlResolver productMediaUrlResolver;
    private final Clock clock;

    public MybatisPlusProductMasterDataRepository(
            ProductSpuMapper productSpuMapper,
            ProductSkuMapper productSkuMapper,
            CategoryMapper categoryMapper,
            BrandMapper brandMapper,
            SpecificationMapper specificationMapper,
            SpecificationValueMapper specificationValueMapper,
            ProductTagMapper productTagMapper,
            TagGroupMapper tagGroupMapper,
            ProductImageMapper productImageMapper,
            ProductPriceMapper productPriceMapper,
            ProductUnitMapper productUnitMapper,
            ProductInventoryPolicyMapper productInventoryPolicyMapper,
            ProductCustomFieldMapper productCustomFieldMapper,
            ProductSpuCategoryMapper productSpuCategoryMapper,
            ProductSpuSpecificationMapper productSpuSpecificationMapper,
            ProductSkuSpecificationValueMapper productSkuSpecificationValueMapper,
            MasterSourceBindingMapper bindingMapper,
            MasterDataSyncRunMapper syncRunMapper,
            MasterDataSyncLockMapper syncLockMapper,
            ProductMediaUrlResolver productMediaUrlResolver,
            Clock clock) {
        this.productSpuMapper = productSpuMapper;
        this.productSkuMapper = productSkuMapper;
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
        this.specificationMapper = specificationMapper;
        this.specificationValueMapper = specificationValueMapper;
        this.productTagMapper = productTagMapper;
        this.tagGroupMapper = tagGroupMapper;
        this.productImageMapper = productImageMapper;
        this.productPriceMapper = productPriceMapper;
        this.productUnitMapper = productUnitMapper;
        this.productInventoryPolicyMapper = productInventoryPolicyMapper;
        this.productCustomFieldMapper = productCustomFieldMapper;
        this.productSpuCategoryMapper = productSpuCategoryMapper;
        this.productSpuSpecificationMapper = productSpuSpecificationMapper;
        this.productSkuSpecificationValueMapper = productSkuSpecificationValueMapper;
        this.bindingMapper = bindingMapper;
        this.syncRunMapper = syncRunMapper;
        this.syncLockMapper = syncLockMapper;
        this.productMediaUrlResolver = productMediaUrlResolver;
        this.clock = clock;
    }

    @Override
    public ProductPageView products(String tenantId, int begin, int step, String query,
                                   String internalStatus, String sourcePutaway) {
        QueryWrapper<ProductSpuEntity> wrapper = Wrappers.<ProductSpuEntity>query()
                .eq("tenant_id", tenantId);
        if (!missing(query)) {
            String like = "%" + query.strip() + "%";
            wrapper.and(w -> w.like("spu_code", like).or().like("name", like)
                    .or().like("default_barcode", like));
        }
        if (!missing(internalStatus)) wrapper.eq("internal_status", internalStatus.strip());
        applySourcePutaway(wrapper, tenantId, "PRODUCT_SPU", sourcePutaway, "id");
        long total = productSpuMapper.selectCount(wrapper);
        List<ProductSpuEntity> page = productSpuMapper.selectList(wrapper
                .orderByDesc("updated_at").orderByAsc("id").last(limitSql(begin, step)));
        Map<String, MasterSourceBindingEntity> bindings = bindingMap(tenantId, "PRODUCT_SPU",
                ids(page, item -> item.id));
        Set<String> brandIds = ids(page, item -> item.brandId);
        Map<String, BrandEntity> brands = brandIds.isEmpty() ? Map.of() : byId(
                brandMapper.selectList(Wrappers.<BrandEntity>query()
                        .eq("tenant_id", tenantId).in("id", brandIds)), item -> item.id);
        Map<String, CategoryEntity> categories = primaryCategories(tenantId, page);
        ProductViewDetails details = productViewDetails(tenantId, ids(page, item -> item.id));
        List<ProductView> views = page.stream().map(item -> toProductView(item,
                sourceBinding(bindings, item.id), brands, categories, details)).toList();
        return new ProductPageView(total, begin, step, views);
    }

    @Override
    public SkuPageView skus(String tenantId, int begin, int step, String query,
                            String internalStatus, String sourcePutaway) {
        QueryWrapper<ProductSkuEntity> wrapper = Wrappers.<ProductSkuEntity>query()
                .eq("tenant_id", tenantId);
        if (!missing(internalStatus)) wrapper.eq("internal_status", internalStatus.strip());
        if (!missing(query)) {
            String like = "%" + query.strip() + "%";
            wrapper.and(w -> w.like("sku_code", query.strip()).or().like("barcode", query.strip())
                    .or().apply("EXISTS (SELECT 1 FROM erp_product_spu p "
                            + "WHERE p.tenant_id = {0} AND p.id = spu_id "
                            + "AND (p.spu_code LIKE {1} OR p.name LIKE {1}))", tenantId, like));
        }
        if (!missing(sourcePutaway)) {
            String putaway = sourcePutaway.strip();
            wrapper.and(w -> w.apply("EXISTS (SELECT 1 FROM erp_master_source_binding b "
                            + "WHERE b.tenant_id = {0} AND b.source_system = 'DINGHUOBAO' "
                            + "AND b.source_object_type = 'PRODUCT_SKU' AND b.target_id = id "
                            + "AND b.source_putaway = {1})", tenantId, putaway)
                    .or().apply("EXISTS (SELECT 1 FROM erp_master_source_binding b "
                            + "WHERE b.tenant_id = {0} AND b.source_system = 'DINGHUOBAO' "
                            + "AND b.source_object_type = 'PRODUCT_SPU' AND b.target_id = spu_id "
                            + "AND b.source_putaway = {1})", tenantId, putaway));
        }
        long total = productSkuMapper.selectCount(wrapper);
        List<ProductSkuEntity> page = productSkuMapper.selectList(wrapper
                .orderByDesc("updated_at").orderByAsc("id").last(limitSql(begin, step)));
        Set<String> spuIds = ids(page, item -> item.spuId);
        Map<String, ProductSpuEntity> products = spuIds.isEmpty() ? Map.of() : byId(productSpuMapper.selectList(
                Wrappers.<ProductSpuEntity>query().eq("tenant_id", tenantId).in("id", spuIds)), item -> item.id);
        Map<String, MasterSourceBindingEntity> skuBindings = bindingMap(tenantId, "PRODUCT_SKU",
                ids(page, item -> item.id));
        Map<String, MasterSourceBindingEntity> productBindings = bindingMap(tenantId, "PRODUCT_SPU", spuIds);
        Map<String, PriceValues> skuPrices = pricesByTarget(tenantId, "SKU", ids(page, item -> item.id));
        List<SkuView> views = page.stream().map(item -> {
            ProductSpuEntity product = products.get(item.spuId);
            if (product == null) return null;
            MasterSourceBindingEntity binding = sourceBinding(skuBindings, item.id);
            if (binding == null) binding = sourceBinding(productBindings, item.spuId);
            PriceValues prices = skuPrices.getOrDefault(item.id, PriceValues.empty());
            return skuView(item, product, binding, prices);
        }).filter(Objects::nonNull).toList();
        return new SkuPageView(total, begin, step, views);
    }

    @Override
    public MasterDataPageView<CategoryView> categories(String tenantId, int begin, int step,
                                                       String query, String status) {
        QueryWrapper<CategoryEntity> wrapper = simpleWrapper(tenantId, query, status,
                "category_code", "name");
        long total = categoryMapper.selectCount(wrapper);
        List<CategoryEntity> page = categoryMapper.selectList(wrapper.orderByAsc("sort_order")
                .orderByAsc("name").orderByAsc("id").last(limitSql(begin, step)));
        Map<String, MasterSourceBindingEntity> bindings = bindingMap(tenantId, "CATEGORY",
                ids(page, item -> item.id));
        List<CategoryView> views = page.stream().map(item -> {
            MasterSourceBindingEntity binding = sourceBinding(bindings, item.id);
            return new CategoryView(item.id, binding == null ? null : binding.sourceObjectId,
                    binding == null ? null : binding.sourceCode, item.categoryCode, item.name,
                    item.parentId, value(item.categoryLevel, 1),
                    item.sourceParentId, item.sourceCategoryNumber, item.sourceDefaultFlag,
                    item.status, item.ownershipState, instant(binding == null ? null : binding.syncedAt));
        }).toList();
        return new MasterDataPageView<>(total, begin, step, views);
    }

    @Override
    public MasterDataPageView<BrandView> brands(String tenantId, int begin, int step,
                                                String query, String status) {
        QueryWrapper<BrandEntity> wrapper = simpleWrapper(tenantId, query, status,
                "brand_code", "name");
        long total = brandMapper.selectCount(wrapper);
        List<BrandEntity> page = brandMapper.selectList(wrapper.orderByAsc("name")
                .orderByAsc("id").last(limitSql(begin, step)));
        Map<String, MasterSourceBindingEntity> bindings = bindingMap(tenantId, "BRAND",
                ids(page, item -> item.id));
        List<BrandView> views = page.stream().map(item -> {
            MasterSourceBindingEntity binding = sourceBinding(bindings, item.id);
            return new BrandView(item.id, binding == null ? null : binding.sourceObjectId,
                    binding == null ? null : binding.sourceCode, item.brandCode, item.name,
                    item.sourceBrandNumber, item.sourceSortOrder,
                    item.sourceDescription, item.status, item.ownershipState,
                    instant(binding == null ? null : binding.syncedAt));
        }).toList();
        return new MasterDataPageView<>(total, begin, step, views);
    }

    @Override
    public MasterDataPageView<SpecificationView> specifications(String tenantId, int begin, int step,
                                                                String query, String status) {
        QueryWrapper<SpecificationEntity> wrapper = simpleWrapper(tenantId, query, status,
                "specification_code", "name");
        long total = specificationMapper.selectCount(wrapper);
        List<SpecificationEntity> page = specificationMapper.selectList(wrapper.orderByAsc("name")
                .orderByAsc("id").last(limitSql(begin, step)));
        Map<String, MasterSourceBindingEntity> bindings = bindingMap(tenantId, "SPECIFICATION",
                ids(page, item -> item.id));
        Set<String> specificationIds = ids(page, item -> item.id);
        Map<String, List<SpecificationValueView>> values = specificationValues(tenantId, specificationIds);
        List<SpecificationView> views = page.stream().map(item -> {
            MasterSourceBindingEntity binding = sourceBinding(bindings, item.id);
            List<SpecificationValueView> specificationValues = values.getOrDefault(item.id, List.of());
            int count = specificationValues.size();
            return new SpecificationView(item.id, binding == null ? null : binding.sourceObjectId,
                    item.specificationCode, item.name, item.sourceParentId, count, specificationValues,
                    item.status,
                    item.ownershipState, instant(binding == null ? null : binding.syncedAt));
        }).toList();
        return new MasterDataPageView<>(total, begin, step, views);
    }

    @Override
    public MasterDataPageView<TagView> tags(String tenantId, int begin, int step,
                                           String query, String status) {
        QueryWrapper<ProductTagEntity> wrapper = simpleWrapper(tenantId, query, status,
                "tag_code", "name");
        long total = productTagMapper.selectCount(wrapper);
        List<ProductTagEntity> page = productTagMapper.selectList(wrapper.orderByAsc("name")
                .orderByAsc("id").last(limitSql(begin, step)));
        Map<String, MasterSourceBindingEntity> bindings = bindingMap(tenantId, "TAG",
                ids(page, item -> item.id));
        List<TagView> views = page.stream().map(item -> {
            MasterSourceBindingEntity binding = sourceBinding(bindings, item.id);
            return new TagView(item.id, binding == null ? null : binding.sourceObjectId,
                    item.tagCode, item.name, item.sourceGroupId, item.sourceGroupName,
                    item.sourceSortOrder, item.sourceRelationCount, instant(item.sourceCreatedAt),
                    instant(item.sourceUpdatedAt), item.color, item.status, item.ownershipState,
                    instant(binding == null ? null : binding.syncedAt));
        }).toList();
        return new MasterDataPageView<>(total, begin, step, views);
    }

    @Override
    @Transactional
    public UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                         MasterDataObjectType objectType, int maxPages) {
        return startRun(tenantId, connectorId, actorId, objectType, maxPages, "MANUAL");
    }

    @Override
    @Transactional
    public UUID startScheduledRun(String tenantId, UUID connectorId, UUID actorId,
                                  MasterDataObjectType objectType, int maxPages) {
        return startRun(tenantId, connectorId, actorId, objectType, maxPages, "SCHEDULED");
    }

    private UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                          MasterDataObjectType objectType, int maxPages, String triggerType) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = now();
        acquireRunLock(tenantId, objectType.name(), id, now);
        MasterDataSyncRunEntity entity = new MasterDataSyncRunEntity();
        entity.id = id.toString();
        entity.tenantId = tenantId;
        entity.connectorId = connectorId == null ? null : connectorId.toString();
        entity.sourceSystem = SOURCE_SYSTEM;
        entity.objectType = objectType.name();
        entity.triggerType = triggerType;
        entity.status = "RUNNING";
        entity.maxPages = maxPages;
        entity.pageSize = 1000;
        entity.startedAt = now;
        entity.createdBy = text(actorId);
        entity.createdAt = now;
        entity.updatedAt = now;
        syncRunMapper.insert(entity);
        return id;
    }

    @Transactional
    @Override
    public ImportResult importCategory(String tenantId, UUID runId, Category category) {
        if (missing(category.sourceId()) || missing(category.name())) return ImportResult.rejected(1);
        BindingResult result = upsertCategory(tenantId, runId, category);
        CategoryEntity entity = categoryMapper.selectById(result.targetId());
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity != null && externalPrimary(entity.ownershipState)
                && (result.importResult().created() > 0 || result.importResult().changed() > 0)) {
            entity.sourceParentId = category.parentSourceId();
            entity.parentId = resolveTarget(tenantId, "CATEGORY", category.parentSourceId());
            entity.sourceCategoryNumber = category.categoryNumber();
            entity.sourceDefaultFlag = category.defaultCategory();
            entity.updatedAt = now();
            categoryMapper.updateById(entity);
        }
        return result.importResult();
    }

    @Transactional
    @Override
    public ImportResult importBrand(String tenantId, UUID runId, Brand brand) {
        if (missing(brand.sourceId()) || missing(brand.name())) return ImportResult.rejected(1);
        BindingResult result = upsertBrand(tenantId, runId, brand);
        BrandEntity entity = brandMapper.selectById(result.targetId());
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity != null && externalPrimary(entity.ownershipState)
                && (result.importResult().created() > 0 || result.importResult().changed() > 0)) {
            entity.sourceBrandNumber = brand.brandNumber();
            entity.sourceSortOrder = brand.sortOrder();
            entity.sourceDescription = brand.description();
            entity.updatedAt = now();
            brandMapper.updateById(entity);
        }
        return result.importResult();
    }

    @Transactional
    @Override
    public ImportResult importTag(String tenantId, UUID runId, Tag tag) {
        if (missing(tag.sourceId()) || missing(tag.name())) return ImportResult.rejected(1);
        upsertTagGroup(tenantId, runId, tag);
        BindingResult result = upsertTag(tenantId, runId, tag);
        ProductTagEntity entity = productTagMapper.selectById(result.targetId());
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity != null && externalPrimary(entity.ownershipState)
                && (result.importResult().created() > 0 || result.importResult().changed() > 0)) {
            entity.tagGroupId = resolveTarget(tenantId, "TAG_GROUP", tag.groupSourceId());
            entity.sourceGroupId = tag.groupSourceId();
            entity.sourceGroupName = tag.groupName();
            entity.sourceSortOrder = tag.sortOrder();
            entity.sourceRelationCount = tag.relationCount();
            entity.sourceCreatedAt = local(tag.createdAt());
            entity.sourceUpdatedAt = local(tag.updatedAt());
            entity.updatedAt = now();
            productTagMapper.updateById(entity);
        }
        return result.importResult();
    }

    @Transactional
    @Override
    public ImportResult importSpecification(String tenantId, UUID runId,
                                            Specification specification) {
        if (missing(specification.sourceId()) || missing(specification.name())) {
            return ImportResult.rejected(1);
        }
        BindingResult result = upsertSpecification(tenantId, runId, specification);
        SpecificationEntity entity = specificationMapper.selectById(result.targetId());
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity == null) return result.importResult().plus(
                ImportResult.rejected(specification.values().size()));
        if (externalPrimary(entity.ownershipState)
                && (result.importResult().created() > 0 || result.importResult().changed() > 0)) {
            entity.sourceParentId = specification.parentSourceId();
            entity.updatedAt = now();
            specificationMapper.updateById(entity);
        }
        ImportResult total = result.importResult();
        for (SpecificationValue value : specification.values()) {
            total = total.plus(importSpecificationValue(tenantId, runId, entity.id, value));
        }
        return total;
    }

    @Transactional
    @Override
    public ImportResult importProduct(String tenantId, UUID runId, Product product) {
        if (missing(product.sourceId()) || missing(product.name())) return ImportResult.rejected(1);
        MasterSourceBindingEntity existingBinding = binding(tenantId, "PRODUCT_SPU", product.sourceId());
        boolean payloadChanged = existingBinding == null
                || !Objects.equals(existingBinding.sourcePayloadHash, requiredHash(product.payloadHash()));
        String spuId = existingBinding == null ? UUID.randomUUID().toString() : existingBinding.targetId;
        ProductSpuEntity existing = existingBinding == null ? null : productSpuMapper.selectById(spuId);
        if (existing != null && !Objects.equals(tenantId, existing.tenantId)) existing = null;
        boolean productNeedsRepair = existing != null && !payloadChanged
                && externalPrimary(existing.ownershipState)
                && !productAuxiliaryComplete(tenantId, spuId, product);
        if (existing != null && !payloadChanged && !productNeedsRepair) {
            // 商品摘要未变化且本地商品辅助数据完整时，不写商品；仍继续检查 SKU，避免漏修复 SKU 辅助数据。
            ImportResult result = ImportResult.duplicate(1);
            for (Sku sku : product.skus()) result = result.plus(importSku(tenantId, runId, spuId, product, sku));
            return result;
        }
        LocalDateTime now = now();
        String brandId = resolveTarget(tenantId, "BRAND", product.brandSourceId());
        String categoryId = resolveTarget(tenantId, "CATEGORY", product.categorySourceId());
        boolean external = existing == null || externalPrimary(existing.ownershipState);
        ProductSpuEntity entity = existing == null ? new ProductSpuEntity() : existing;
        if (existing == null) {
            entity.id = spuId;
            entity.tenantId = tenantId;
            entity.spuCode = uniqueSpuCode(tenantId, product.code(), product.sourceId());
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.internalStatus = putawayStatus(product.putaway());
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.createdBy = null;
        }
        if (external && (existing == null || payloadChanged)) {
            entity.name = product.name();
            entity.model = product.model();
            entity.subtitle = product.subtitle();
            entity.keywords = product.keywords();
            entity.goodsAllocation = product.allocation();
            entity.mainImageKey = product.mainImageKey();
            entity.sourceMultiId = product.multiId();
            entity.sourceCategoryId = product.categorySourceId();
            entity.sourceBrandId = product.brandSourceId();
            entity.conversionBarcode = product.conversionBarcode();
            entity.brandId = brandId;
            entity.baseUnit = product.unit();
            entity.defaultBarcode = product.barcode();
            entity.minimumOrder = product.minimumOrder();
            entity.minimumOrderUnit = product.minimumOrderUnit();
            entity.internalStatus = putawayStatus(product.putaway());
            if (existing != null) entity.version = nextVersion(entity.version);
            entity.updatedAt = now;
        } else if (existing != null) {
            entity.updatedAt = now;
        }
        if (existing == null) productSpuMapper.insert(entity);
        else if (external && payloadChanged) productSpuMapper.updateById(entity);
        upsertBinding(tenantId, runId, "PRODUCT_SPU", product.sourceId(), "SPU", spuId,
                product.code(), product.name(), null, product.putaway(), product.payloadHash(), now);
        if (external && categoryId != null) assignPrimaryCategory(tenantId, spuId, categoryId, now);
        if (external && (existing == null || payloadChanged || productNeedsRepair)) {
            upsertProductAuxiliary(tenantId, spuId, product, now);
        }

        ImportResult result = existing == null ? ImportResult.created(1)
                : payloadChanged || productNeedsRepair ? ImportResult.changed(1) : ImportResult.duplicate(1);
        if (existing != null && payloadChanged) {
            log.info("ERP商品来源字段发生变化 tenantId={} runId={} sourceId={} targetId={} version={}",
                    tenantId, runId, product.sourceId(), spuId, entity.version);
        }
        if (productNeedsRepair) {
            log.info("ERP商品本地辅助数据不完整，执行同步修复 tenantId={} runId={} sourceId={} targetId={}",
                    tenantId, runId, product.sourceId(), spuId);
        }
        log.debug("ERP商品主数据幂等落库 tenantId={} runId={} sourceId={} targetId={} result={}",
                tenantId, runId, product.sourceId(), spuId,
                productNeedsRepair ? "REPAIRED" : importAction(existing, payloadChanged));
        for (Sku sku : product.skus()) result = result.plus(importSku(tenantId, runId, spuId, product, sku));
        return result;
    }

    @Override
    @Transactional
    public void completeRun(String tenantId, UUID runId, RunStatistics statistics) {
        finishRun(tenantId, runId, "SUCCEEDED", statistics, null, null);
    }

    @Override
    @Transactional
    public void failRun(String tenantId, UUID runId, RunStatistics statistics, RuntimeException error) {
        String message = error.getMessage();
        if (message != null && message.length() > 2000) message = message.substring(0, 2000);
        finishRun(tenantId, runId, "FAILED", statistics,
                error.getClass().getSimpleName(), message);
    }

    private BindingResult upsertCategory(String tenantId, UUID runId, Category value) {
        MasterSourceBindingEntity existing = binding(tenantId, "CATEGORY", value.sourceId());
        boolean changed = changed(existing, value.payloadHash());
        String id = targetId(existing);
        LocalDateTime now = now();
        CategoryEntity entity = existing == null ? new CategoryEntity() : categoryMapper.selectById(id);
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity == null) {
            entity = new CategoryEntity();
            existing = null;
        }
        if (existing == null) {
            entity.id = id;
            entity.tenantId = tenantId;
            entity.categoryCode = uniqueCategoryCode(tenantId, value.externalReferenceId(), value.sourceId());
            entity.categoryLevel = 1;
            entity.sortOrder = 0;
            entity.status = "ACTIVE";
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            entity.createdBy = null;
            entity.name = value.name();
            categoryMapper.insert(entity);
        } else if (changed && externalPrimary(entity.ownershipState)) {
            entity.name = value.name();
            entity.version = nextVersion(entity.version);
            entity.updatedAt = now;
            categoryMapper.updateById(entity);
        }
        upsertBinding(tenantId, runId, "CATEGORY", value.sourceId(), "CATEGORY", id,
                value.externalReferenceId(), value.name(), null, null, value.payloadHash(), now);
        return new BindingResult(id, result(existing, changed));
    }

    private BindingResult upsertBrand(String tenantId, UUID runId, Brand value) {
        MasterSourceBindingEntity existing = binding(tenantId, "BRAND", value.sourceId());
        boolean changed = changed(existing, value.payloadHash());
        String id = targetId(existing);
        LocalDateTime now = now();
        BrandEntity entity = existing == null ? new BrandEntity() : brandMapper.selectById(id);
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity == null) {
            entity = new BrandEntity();
            existing = null;
        }
        if (existing == null) {
            entity.id = id;
            entity.tenantId = tenantId;
            entity.brandCode = uniqueBrandCode(tenantId, value.externalReferenceId(), value.sourceId());
            entity.name = value.name();
            entity.status = "ACTIVE";
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            brandMapper.insert(entity);
        } else if (changed && externalPrimary(entity.ownershipState)) {
            entity.name = value.name();
            entity.version = nextVersion(entity.version);
            entity.updatedAt = now;
            brandMapper.updateById(entity);
        }
        upsertBinding(tenantId, runId, "BRAND", value.sourceId(), "BRAND", id,
                value.externalReferenceId(), value.name(), null, null, value.payloadHash(), now);
        return new BindingResult(id, result(existing, changed));
    }

    private BindingResult upsertTag(String tenantId, UUID runId, Tag value) {
        MasterSourceBindingEntity existing = binding(tenantId, "TAG", value.sourceId());
        boolean changed = changed(existing, value.payloadHash());
        String id = targetId(existing);
        LocalDateTime now = now();
        ProductTagEntity entity = existing == null ? new ProductTagEntity() : productTagMapper.selectById(id);
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity == null) {
            entity = new ProductTagEntity();
            existing = null;
        }
        if (existing == null) {
            entity.id = id;
            entity.tenantId = tenantId;
            entity.tagCode = missing(value.code()) ? uniqueTagCode(tenantId, value.sourceId()) : value.code();
            entity.name = value.name();
            entity.status = "ACTIVE";
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            productTagMapper.insert(entity);
        } else if (changed && externalPrimary(entity.ownershipState)) {
            entity.name = value.name();
            entity.version = nextVersion(entity.version);
            entity.updatedAt = now;
            productTagMapper.updateById(entity);
        }
        upsertBinding(tenantId, runId, "TAG", value.sourceId(), "TAG", id,
                value.code(), value.name(), null, null, value.payloadHash(), now);
        return new BindingResult(id, result(existing, changed));
    }

    private BindingResult upsertSpecification(String tenantId, UUID runId, Specification value) {
        MasterSourceBindingEntity existing = binding(tenantId, "SPECIFICATION", value.sourceId());
        boolean changed = changed(existing, value.payloadHash());
        String id = targetId(existing);
        LocalDateTime now = now();
        SpecificationEntity entity = existing == null ? new SpecificationEntity()
                : specificationMapper.selectById(id);
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity == null) {
            entity = new SpecificationEntity();
            existing = null;
        }
        if (existing == null) {
            entity.id = id;
            entity.tenantId = tenantId;
            entity.specificationCode = missing(value.code())
                    ? "DHB-SPEC-" + shortHash(value.sourceId()) : value.code();
            entity.name = value.name();
            entity.status = "ACTIVE";
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            specificationMapper.insert(entity);
        } else if (changed && externalPrimary(entity.ownershipState)) {
            entity.name = value.name();
            entity.version = nextVersion(entity.version);
            entity.updatedAt = now;
            specificationMapper.updateById(entity);
        }
        upsertBinding(tenantId, runId, "SPECIFICATION", value.sourceId(), "SPECIFICATION", id,
                value.code(), value.name(), null, null, value.payloadHash(), now);
        return new BindingResult(id, result(existing, changed));
    }

    private ImportResult importSpecificationValue(String tenantId, UUID runId,
                                                  String specificationId, SpecificationValue value) {
        if (missing(value.sourceId()) || missing(value.name())) return ImportResult.rejected(1);
        MasterSourceBindingEntity existing = binding(tenantId, "SPECIFICATION_VALUE", value.sourceId());
        boolean changed = changed(existing, value.payloadHash());
        String id = targetId(existing);
        LocalDateTime now = now();
        SpecificationValueEntity entity = existing == null ? new SpecificationValueEntity()
                : specificationValueMapper.selectById(id);
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity == null) {
            entity = new SpecificationValueEntity();
            existing = null;
        }
        if (existing == null) {
            entity.id = id;
            entity.tenantId = tenantId;
            entity.specificationId = specificationId;
            entity.sourceParentId = value.parentSourceId();
            entity.valueCode = uniqueValueCode(tenantId, specificationId, value.code(), value.sourceId());
            entity.valueName = value.name();
            entity.sortOrder = 0;
            entity.status = "ACTIVE";
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            specificationValueMapper.insert(entity);
        } else if (changed && externalPrimary(entity.ownershipState)) {
            entity.sourceParentId = value.parentSourceId();
            entity.valueName = value.name();
            entity.version = nextVersion(entity.version);
            entity.updatedAt = now;
            specificationValueMapper.updateById(entity);
        }
        upsertBinding(tenantId, runId, "SPECIFICATION_VALUE", value.sourceId(),
                "SPECIFICATION_VALUE", id, value.code(), value.name(), null, null,
                value.payloadHash(), now);
        return result(existing, changed);
    }

    private ImportResult importSku(String tenantId, UUID runId, String spuId,
                                   Product product, Sku value) {
        if (missing(value.sourceId()) || (missing(value.code()) && missing(value.barcode()))) {
            return ImportResult.rejected(1);
        }
        MasterSourceBindingEntity existingBinding = binding(tenantId, "PRODUCT_SKU", value.sourceId());
        LocalDateTime now = now();
        if (existingBinding == null) {
            String legacySourceId = legacySkuSourceId(product.sourceId(), value.sourceId());
            MasterSourceBindingEntity legacyBinding = legacySourceId == null
                    ? null : binding(tenantId, "PRODUCT_SKU", legacySourceId);
            ProductSkuEntity legacySku = legacyBinding == null
                    ? null : productSkuMapper.selectById(legacyBinding.targetId);
            if (legacySku != null && Objects.equals(tenantId, legacySku.tenantId)
                    && Objects.equals(spuId, legacySku.spuId)) {
                legacyBinding.sourceObjectId = value.sourceId();
                legacyBinding.updatedAt = now;
                bindingMapper.updateById(legacyBinding);
                existingBinding = legacyBinding;
                log.info("ERP SKU来源绑定升级 tenantId={} runId={} legacySourceId={} sourceId={} targetId={}",
                        tenantId, runId, legacySourceId, value.sourceId(), legacySku.id);
            }
        }
        boolean payloadChanged = existingBinding == null
                || !Objects.equals(existingBinding.sourcePayloadHash, requiredHash(value.payloadHash()));
        String id = targetId(existingBinding);
        ProductSkuEntity existing = existingBinding == null ? null : productSkuMapper.selectById(id);
        if (existing != null && !Objects.equals(tenantId, existing.tenantId)) existing = null;
        boolean skuNeedsRepair = existing != null && !payloadChanged
                && externalPrimary(existing.ownershipState)
                && (!skuCoreComplete(existing, spuId, product, value)
                || !skuAuxiliaryComplete(tenantId, spuId, id, product, value)
                || !skuSpecificationRelationsComplete(tenantId, id, value));
        if (existing != null && !payloadChanged && !skuNeedsRepair) {
            // 来源摘要未变化且本地 SKU 及其辅助数据完整时不触碰数据库。
            return ImportResult.duplicate(1);
        }
        ProductSkuEntity entity = existing == null ? new ProductSkuEntity() : existing;
        if (existing == null) {
            entity.id = id;
            entity.tenantId = tenantId;
            entity.spuId = spuId;
            entity.skuCode = uniqueSkuCode(tenantId, value.code(), value.sourceId());
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.internalStatus = putawayStatus(product.putaway());
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
        }
        boolean writeSku = existing == null || payloadChanged || skuNeedsRepair;
        if (externalPrimary(entity.ownershipState) && writeSku) {
            entity.sourceOptionsId = value.optionsId();
            entity.firstSpecificationValueSourceId = value.firstSpecificationValueSourceId();
            entity.secondSpecificationValueSourceId = value.secondSpecificationValueSourceId();
            entity.barcode = value.barcode();
            entity.middleBarcode = value.middleBarcode();
            entity.bigBarcode = value.bigBarcode();
            entity.unit = product.unit();
            entity.specificationSummary = value.specificationName();
            entity.internalStatus = putawayStatus(product.putaway());
            if (existing != null) entity.version = nextVersion(entity.version);
            entity.updatedAt = now;
        }
        if (existing == null) productSkuMapper.insert(entity);
        else if (writeSku && externalPrimary(entity.ownershipState)) {
            productSkuMapper.updateById(entity);
        }
        upsertBinding(tenantId, runId, "PRODUCT_SKU", value.sourceId(), "SKU", id,
                value.code(), value.specificationName(), null, product.putaway(), value.payloadHash(), now);
        if (externalPrimary(entity.ownershipState)) {
            linkSkuSpecificationValue(tenantId, spuId, id,
                    value.firstSpecificationValueSourceId(), 0, now);
            linkSkuSpecificationValue(tenantId, spuId, id,
                    value.secondSpecificationValueSourceId(), 1, now);
        }
        if (externalPrimary(entity.ownershipState) && writeSku) {
            upsertSkuAuxiliary(tenantId, spuId, id, value, product, now);
        }
        if (existing != null && payloadChanged) {
            log.info("ERP SKU来源字段发生变化 tenantId={} runId={} sourceId={} targetId={} spuId={} version={}",
                    tenantId, runId, value.sourceId(), id, spuId, entity.version);
        }
        log.debug("ERP SKU幂等落库 tenantId={} runId={} sourceId={} targetId={} result={}",
                tenantId, runId, value.sourceId(), id,
                skuNeedsRepair ? "REPAIRED" : importAction(existing, payloadChanged));
        return existing == null ? ImportResult.created(1)
                : payloadChanged || skuNeedsRepair ? ImportResult.changed(1) : ImportResult.duplicate(1);
    }

    private void upsertTagGroup(String tenantId, UUID runId, Tag value) {
        if (missing(value.groupSourceId()) || missing(value.groupName())) return;
        MasterSourceBindingEntity existing = binding(tenantId, "TAG_GROUP", value.groupSourceId());
        String id = targetId(existing);
        LocalDateTime now = now();
        TagGroupEntity entity = existing == null ? new TagGroupEntity() : tagGroupMapper.selectById(id);
        if (entity != null && !Objects.equals(tenantId, entity.tenantId)) entity = null;
        if (entity == null) {
            entity = new TagGroupEntity();
            existing = null;
        }
        if (existing == null) {
            entity.id = id;
            entity.tenantId = tenantId;
            entity.groupCode = value.groupSourceId();
            entity.name = value.groupName();
            entity.status = "ACTIVE";
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            tagGroupMapper.insert(entity);
        } else if (externalPrimary(entity.ownershipState)
                && !Objects.equals(entity.name, value.groupName())) {
            entity.name = value.groupName();
            entity.version = nextVersion(entity.version);
            entity.updatedAt = now;
            tagGroupMapper.updateById(entity);
        }
        upsertBinding(tenantId, runId, "TAG_GROUP", value.groupSourceId(), "TAG_GROUP", id,
                value.groupSourceId(), value.groupName(), null, null,
                shortHash(value.groupSourceId() + ":" + value.groupName()), now);
    }

    /**
     * 仅在来源摘要未变化的同步重放中校验外部主权辅助数据。
     *
     * <p>摘要负责判断来源内容是否变化，数量校验负责发现本地行被误删、漏写或多写；发现异常后复用同一套
     * 全量替换逻辑，避免只补一部分而留下脏数据。内部主权数据不参与此修复。</p>
     */
    private boolean productAuxiliaryComplete(String tenantId, String spuId, Product product) {
        long expectedImages = product.images().stream()
                .filter(image -> !missing(image.objectKey())).count();
        long expectedPrices = nonNullCount(product.orderPrice(), product.marketPrice(), product.purchasePrice(),
                product.price4(), product.middleOrderPrice(), product.bigOrderPrice());
        long expectedUnits = 1L
                + present(product.middleUnit(), product.middleBarcode(), product.baseToMiddleRate())
                + present(product.bigUnit(), product.bigBarcode(), product.baseToBigRate());
        long expectedPolicies = product.inventoryLower() != null || product.inventoryUpper() != null
                || product.safetyInventory() != null ? 1L : 0L;
        long expectedCustomFields = product.customFields().values().stream()
                .filter(value -> !missing(value)).count();

        return count(productImageMapper.selectCount(Wrappers.<ProductImageEntity>query()
                        .eq("tenant_id", tenantId).eq("spu_id", spuId)
                        .eq("ownership_state", EXTERNAL_PRIMARY))) == expectedImages
                && count(productPriceMapper.selectCount(Wrappers.<ProductPriceEntity>query()
                        .eq("tenant_id", tenantId).eq("target_type", "SPU").eq("target_id", spuId)
                        .eq("ownership_state", EXTERNAL_PRIMARY))) == expectedPrices
                && count(productUnitMapper.selectCount(Wrappers.<ProductUnitEntity>query()
                        .eq("tenant_id", tenantId).eq("target_type", "SPU").eq("target_id", spuId)
                        .eq("ownership_state", EXTERNAL_PRIMARY))) == expectedUnits
                && count(productInventoryPolicyMapper.selectCount(Wrappers.<ProductInventoryPolicyEntity>query()
                        .eq("tenant_id", tenantId).eq("spu_id", spuId)
                        .eq("ownership_state", EXTERNAL_PRIMARY))) == expectedPolicies
                && count(productCustomFieldMapper.selectCount(Wrappers.<ProductCustomFieldEntity>query()
                        .eq("tenant_id", tenantId).eq("target_type", "SPU").eq("target_id", spuId)
                        .eq("ownership_state", EXTERNAL_PRIMARY))) == expectedCustomFields;
    }

    private boolean skuCoreComplete(ProductSkuEntity existing, String spuId, Product product, Sku sku) {
        return Objects.equals(existing.spuId, spuId)
                && Objects.equals(existing.sourceOptionsId, sku.optionsId())
                && Objects.equals(existing.firstSpecificationValueSourceId,
                        sku.firstSpecificationValueSourceId())
                && Objects.equals(existing.secondSpecificationValueSourceId,
                        sku.secondSpecificationValueSourceId())
                && Objects.equals(existing.barcode, sku.barcode())
                && Objects.equals(existing.middleBarcode, sku.middleBarcode())
                && Objects.equals(existing.bigBarcode, sku.bigBarcode())
                && Objects.equals(existing.unit, product.unit())
                && Objects.equals(existing.specificationSummary, sku.specificationName())
                && Objects.equals(existing.internalStatus, putawayStatus(product.putaway()));
    }

    private boolean skuAuxiliaryComplete(String tenantId, String spuId, String skuId,
                                         Product product, Sku sku) {
        long expectedPrices = nonNullCount(sku.orderPrice(), sku.marketPrice(), sku.purchasePrice(),
                sku.middleOrderPrice(), sku.bigOrderPrice());
        long expectedUnits = 1L
                + present(product.middleUnit(), sku.middleBarcode(), product.baseToMiddleRate())
                + present(product.bigUnit(), sku.bigBarcode(), product.baseToBigRate());
        return count(productPriceMapper.selectCount(Wrappers.<ProductPriceEntity>query()
                        .eq("tenant_id", tenantId).eq("target_type", "SKU").eq("target_id", skuId)
                        .eq("ownership_state", EXTERNAL_PRIMARY))) == expectedPrices
                && count(productUnitMapper.selectCount(Wrappers.<ProductUnitEntity>query()
                        .eq("tenant_id", tenantId).eq("target_type", "SKU").eq("target_id", skuId)
                        .eq("ownership_state", EXTERNAL_PRIMARY))) == expectedUnits;
    }

    /** 只要求来源规格值已在本地建立的关系存在；来源规格尚未同步时不把它误判为 SKU 缺失。 */
    private boolean skuSpecificationRelationsComplete(String tenantId, String skuId, Sku sku) {
        Set<String> expectedValueIds = new java.util.HashSet<>();
        String[] sourceValueIds = {sku.firstSpecificationValueSourceId(), sku.secondSpecificationValueSourceId()};
        for (String sourceValueId : sourceValueIds) {
            String valueId = resolveTarget(tenantId, "SPECIFICATION_VALUE", sourceValueId);
            if (valueId == null) continue;
            SpecificationValueEntity value = specificationValueMapper.selectById(valueId);
            if (value != null && Objects.equals(value.tenantId, tenantId)) expectedValueIds.add(valueId);
        }
        if (expectedValueIds.isEmpty()) return true;
        Set<String> actualValueIds = productSkuSpecificationValueMapper.selectList(
                        Wrappers.<ProductSkuSpecificationValueEntity>query()
                                .eq("tenant_id", tenantId).eq("sku_id", skuId)
                                .in("value_id", expectedValueIds))
                .stream().map(item -> item.valueId).collect(Collectors.toSet());
        return actualValueIds.containsAll(expectedValueIds);
    }

    private static long nonNullCount(Object... values) {
        long count = 0;
        for (Object value : values) if (value != null) count++;
        return count;
    }

    private static long present(String unit, String barcode, BigDecimal conversion) {
        return missing(unit) && missing(barcode) && conversion == null ? 0L : 1L;
    }

    private static long count(Long value) {
        return value == null ? 0L : value;
    }

    private void upsertProductAuxiliary(String tenantId, String spuId, Product product,
                                        LocalDateTime now) {
        productImageMapper.delete(Wrappers.<ProductImageEntity>query().eq("tenant_id", tenantId)
                .eq("spu_id", spuId).eq("ownership_state", EXTERNAL_PRIMARY));
        int imageIndex = 0;
        for (ProductImage image : product.images()) {
            if (missing(image.objectKey())) continue;
            ProductImageEntity entity = new ProductImageEntity();
            entity.id = UUID.randomUUID().toString();
            entity.tenantId = tenantId;
            entity.spuId = spuId;
            entity.sourceResourceId = image.sourceResourceId();
            entity.sourceGoodsId = image.sourceGoodsId();
            entity.originalName = image.originalName();
            entity.sourceFileName = image.fileName();
            entity.objectKey = image.objectKey();
            entity.sortOrder = image.sortOrder() == null ? imageIndex : image.sortOrder();
            entity.isPrimary = imageIndex == 0;
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            productImageMapper.insert(entity);
            imageIndex++;
        }

        productPriceMapper.delete(Wrappers.<ProductPriceEntity>query().eq("tenant_id", tenantId)
                .eq("target_type", "SPU").eq("target_id", spuId)
                .eq("ownership_state", EXTERNAL_PRIMARY));
        insertPrice(tenantId, "SPU", spuId, spuId, null, "ORDER", "BASE", product.orderPrice(), "price1", now);
        insertPrice(tenantId, "SPU", spuId, spuId, null, "MARKET", "BASE", product.marketPrice(), "price2", now);
        insertPrice(tenantId, "SPU", spuId, spuId, null, "PURCHASE", "BASE", product.purchasePrice(), "price3", now);
        insertPrice(tenantId, "SPU", spuId, spuId, null, "OTHER", "BASE", product.price4(), "price4", now);
        insertPrice(tenantId, "SPU", spuId, spuId, null, "ORDER", "MIDDLE", product.middleOrderPrice(),
                "middle_unit_whole_price", now);
        insertPrice(tenantId, "SPU", spuId, spuId, null, "ORDER", "BIG", product.bigOrderPrice(),
                "big_unit_whole_price", now);

        productUnitMapper.delete(Wrappers.<ProductUnitEntity>query().eq("tenant_id", tenantId)
                .eq("target_type", "SPU").eq("target_id", spuId)
                .eq("ownership_state", EXTERNAL_PRIMARY));
        insertUnit(tenantId, "SPU", spuId, spuId, null, "BASE", product.unit(), product.barcode(),
                BigDecimal.ONE, "units", now);
        insertUnit(tenantId, "SPU", spuId, spuId, null, "MIDDLE", product.middleUnit(),
                product.middleBarcode(), product.baseToMiddleRate(), "middle_units", now);
        insertUnit(tenantId, "SPU", spuId, spuId, null, "BIG", product.bigUnit(), product.bigBarcode(),
                product.baseToBigRate(), "bigunits", now);

        productInventoryPolicyMapper.delete(Wrappers.<ProductInventoryPolicyEntity>query()
                .eq("tenant_id", tenantId).eq("spu_id", spuId)
                .eq("ownership_state", EXTERNAL_PRIMARY));
        if (product.inventoryLower() != null || product.inventoryUpper() != null
                || product.safetyInventory() != null) {
            ProductInventoryPolicyEntity entity = new ProductInventoryPolicyEntity();
            entity.id = UUID.randomUUID().toString();
            entity.tenantId = tenantId;
            entity.spuId = spuId;
            entity.lowerBound = product.inventoryLower();
            entity.upperBound = product.inventoryUpper();
            entity.safetyStock = product.safetyInventory();
            entity.sourceLowerField = "librarydown";
            entity.sourceUpperField = "libraryup";
            entity.sourceSafeField = "librarysafe";
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            productInventoryPolicyMapper.insert(entity);
        }

        productCustomFieldMapper.delete(Wrappers.<ProductCustomFieldEntity>query().eq("tenant_id", tenantId)
                .eq("target_type", "SPU").eq("target_id", spuId)
                .eq("ownership_state", EXTERNAL_PRIMARY));
        for (Map.Entry<String, String> field : product.customFields().entrySet()) {
            if (missing(field.getValue())) continue;
            ProductCustomFieldEntity entity = new ProductCustomFieldEntity();
            entity.id = UUID.randomUUID().toString();
            entity.tenantId = tenantId;
            entity.targetType = "SPU";
            entity.targetId = spuId;
            entity.spuId = spuId;
            entity.fieldKey = field.getKey();
            entity.fieldValue = field.getValue();
            entity.sourceField = field.getKey();
            entity.ownershipState = EXTERNAL_PRIMARY;
            entity.recordOrigin = "IMPORTED";
            entity.version = 0L;
            entity.createdAt = now;
            entity.updatedAt = now;
            productCustomFieldMapper.insert(entity);
        }
    }

    private void upsertSkuAuxiliary(String tenantId, String spuId, String skuId, Sku sku,
                                    Product product, LocalDateTime now) {
        productPriceMapper.delete(Wrappers.<ProductPriceEntity>query().eq("tenant_id", tenantId)
                .eq("target_type", "SKU").eq("target_id", skuId)
                .eq("ownership_state", EXTERNAL_PRIMARY));
        insertPrice(tenantId, "SKU", skuId, spuId, skuId, "ORDER", "BASE", sku.orderPrice(), "whole", now);
        insertPrice(tenantId, "SKU", skuId, spuId, skuId, "MARKET", "BASE", sku.marketPrice(), "selling", now);
        insertPrice(tenantId, "SKU", skuId, spuId, skuId, "PURCHASE", "BASE", sku.purchasePrice(), "purchase", now);
        insertPrice(tenantId, "SKU", skuId, spuId, skuId, "ORDER", "MIDDLE", sku.middleOrderPrice(),
                "middle_unit_whole_price", now);
        insertPrice(tenantId, "SKU", skuId, spuId, skuId, "ORDER", "BIG", sku.bigOrderPrice(),
                "big_unit_whole_price", now);
        productUnitMapper.delete(Wrappers.<ProductUnitEntity>query().eq("tenant_id", tenantId)
                .eq("target_type", "SKU").eq("target_id", skuId)
                .eq("ownership_state", EXTERNAL_PRIMARY));
        insertUnit(tenantId, "SKU", skuId, spuId, skuId, "BASE", product.unit(), sku.barcode(),
                BigDecimal.ONE, "units", now);
        insertUnit(tenantId, "SKU", skuId, spuId, skuId, "MIDDLE", product.middleUnit(), sku.middleBarcode(),
                product.baseToMiddleRate(), "middle_units", now);
        insertUnit(tenantId, "SKU", skuId, spuId, skuId, "BIG", product.bigUnit(), sku.bigBarcode(),
                product.baseToBigRate(), "bigunits", now);
    }

    private void insertPrice(String tenantId, String targetType, String targetId, String spuId,
                             String skuId, String priceType, String unitLevel, BigDecimal amount,
                             String sourceField, LocalDateTime now) {
        if (amount == null) return;
        ProductPriceEntity entity = new ProductPriceEntity();
        entity.id = UUID.randomUUID().toString();
        entity.tenantId = tenantId;
        entity.targetType = targetType;
        entity.targetId = targetId;
        entity.spuId = spuId;
        entity.skuId = skuId;
        entity.priceType = priceType;
        entity.unitLevel = unitLevel;
        entity.amount = amount;
        entity.sourceField = sourceField;
        entity.ownershipState = EXTERNAL_PRIMARY;
        entity.recordOrigin = "IMPORTED";
        entity.version = 0L;
        entity.createdAt = now;
        entity.updatedAt = now;
        productPriceMapper.insert(entity);
    }

    private void insertUnit(String tenantId, String targetType, String targetId, String spuId,
                            String skuId, String unitLevel, String unitName, String barcode,
                            BigDecimal conversion, String sourceField, LocalDateTime now) {
        if (missing(unitName) && missing(barcode) && conversion == null) return;
        ProductUnitEntity entity = new ProductUnitEntity();
        entity.id = UUID.randomUUID().toString();
        entity.tenantId = tenantId;
        entity.targetType = targetType;
        entity.targetId = targetId;
        entity.spuId = spuId;
        entity.skuId = skuId;
        entity.unitLevel = unitLevel;
        entity.unitName = unitName;
        entity.barcode = barcode;
        entity.conversionToBase = conversion;
        entity.sourceField = sourceField;
        entity.sortOrder = switch (unitLevel) { case "BASE" -> 0; case "MIDDLE" -> 1; default -> 2; };
        entity.ownershipState = EXTERNAL_PRIMARY;
        entity.recordOrigin = "IMPORTED";
        entity.version = 0L;
        entity.createdAt = now;
        entity.updatedAt = now;
        productUnitMapper.insert(entity);
    }

    private void linkSkuSpecificationValue(String tenantId, String spuId, String skuId,
                                           String sourceValueId, int sortOrder, LocalDateTime now) {
        String valueId = resolveTarget(tenantId, "SPECIFICATION_VALUE", sourceValueId);
        if (valueId == null) return;
        SpecificationValueEntity value = specificationValueMapper.selectById(valueId);
        if (value == null || !Objects.equals(value.tenantId, tenantId)) return;
        ProductSpuSpecificationEntity spuRelation = productSpuSpecificationMapper.selectOne(
                Wrappers.<ProductSpuSpecificationEntity>query().eq("tenant_id", tenantId)
                        .eq("spu_id", spuId).eq("specification_id", value.specificationId)
                        .last("LIMIT 1"));
        boolean newSpuRelation = spuRelation == null;
        if (spuRelation == null) {
            spuRelation = new ProductSpuSpecificationEntity();
            spuRelation.id = UUID.randomUUID().toString();
            spuRelation.tenantId = tenantId;
            spuRelation.spuId = spuId;
            spuRelation.specificationId = value.specificationId;
            spuRelation.createdAt = now;
        }
        if (newSpuRelation) {
            spuRelation.sortOrder = sortOrder;
            spuRelation.updatedAt = now;
            productSpuSpecificationMapper.insert(spuRelation);
        } else if (!Objects.equals(spuRelation.sortOrder, sortOrder)) {
            spuRelation.sortOrder = sortOrder;
            spuRelation.updatedAt = now;
            productSpuSpecificationMapper.updateById(spuRelation);
        }

        ProductSkuSpecificationValueEntity skuRelation = productSkuSpecificationValueMapper.selectOne(
                Wrappers.<ProductSkuSpecificationValueEntity>query().eq("tenant_id", tenantId)
                        .eq("sku_id", skuId).eq("specification_id", value.specificationId)
                        .last("LIMIT 1"));
        boolean newSkuRelation = skuRelation == null;
        if (skuRelation == null) {
            skuRelation = new ProductSkuSpecificationValueEntity();
            skuRelation.id = UUID.randomUUID().toString();
            skuRelation.tenantId = tenantId;
            skuRelation.skuId = skuId;
            skuRelation.specificationId = value.specificationId;
            skuRelation.createdAt = now;
        }
        if (newSkuRelation) {
            skuRelation.valueId = valueId;
            skuRelation.sortOrder = sortOrder;
            skuRelation.updatedAt = now;
            productSkuSpecificationValueMapper.insert(skuRelation);
        } else if (!Objects.equals(skuRelation.valueId, valueId)
                || !Objects.equals(skuRelation.sortOrder, sortOrder)) {
            skuRelation.valueId = valueId;
            skuRelation.sortOrder = sortOrder;
            skuRelation.updatedAt = now;
            productSkuSpecificationValueMapper.updateById(skuRelation);
        }
    }

    private void assignPrimaryCategory(String tenantId, String spuId, String categoryId,
                                       LocalDateTime now) {
        List<ProductSpuCategoryEntity> relations = productSpuCategoryMapper.selectList(
                Wrappers.<ProductSpuCategoryEntity>query().eq("tenant_id", tenantId)
                        .eq("spu_id", spuId));
        boolean alreadyPrimary = relations.stream().anyMatch(relation ->
                Boolean.TRUE.equals(relation.primaryFlag)
                        && Objects.equals(relation.categoryId, categoryId));
        if (alreadyPrimary) return;
        for (ProductSpuCategoryEntity relation : relations) {
            if (Boolean.TRUE.equals(relation.primaryFlag)) {
                relation.primaryFlag = false;
                relation.updatedAt = now;
                productSpuCategoryMapper.updateById(relation);
            }
        }
        ProductSpuCategoryEntity relation = relations.stream()
                .filter(item -> Objects.equals(item.categoryId, categoryId)).findFirst().orElse(null);
        boolean newRelation = relation == null;
        if (relation == null) {
            relation = new ProductSpuCategoryEntity();
            relation.id = UUID.randomUUID().toString();
            relation.tenantId = tenantId;
            relation.spuId = spuId;
            relation.categoryId = categoryId;
            relation.sortOrder = 0;
            relation.createdAt = now;
        }
        relation.primaryFlag = true;
        relation.updatedAt = now;
        if (newRelation) productSpuCategoryMapper.insert(relation);
        else productSpuCategoryMapper.updateById(relation);
    }

    private void upsertBinding(String tenantId, UUID runId, String sourceType, String sourceId,
                               String targetType, String targetId, String sourceCode, String sourceName,
                               String sourceStatus, String sourcePutaway, String payloadHash,
                               LocalDateTime now) {
        MasterSourceBindingEntity entity = binding(tenantId, sourceType, sourceId);
        String normalizedHash = requiredHash(payloadHash);
        if (entity != null && Objects.equals(entity.sourcePayloadHash, normalizedHash)) {
            // 来源快照未变化时不刷新版本、同步时间或最后同步批次，保持同步真正幂等。
            return;
        }
        if (entity == null) {
            entity = new MasterSourceBindingEntity();
            entity.id = UUID.randomUUID().toString();
            entity.tenantId = tenantId;
            entity.sourceSystem = SOURCE_SYSTEM;
            entity.sourceObjectType = sourceType;
            entity.sourceObjectId = sourceId;
            entity.targetType = targetType;
            entity.targetId = targetId;
            entity.version = 0L;
            entity.createdAt = now;
        } else {
            entity.version = nextVersion(entity.version);
        }
        entity.sourceCode = blank(sourceCode);
        entity.sourceName = blank(sourceName);
        entity.sourceStatus = blank(sourceStatus);
        entity.sourcePutaway = blank(sourcePutaway);
        entity.sourcePayloadHash = normalizedHash;
        entity.lastSyncRunId = text(runId);
        entity.syncedAt = now;
        entity.updatedAt = now;
        if (entity.createdAt == null) entity.createdAt = now;
        if (entity.id == null) bindingMapper.insert(entity);
        else if (bindingMapper.selectById(entity.id) == null) bindingMapper.insert(entity);
        else bindingMapper.updateById(entity);
    }

    private void finishRun(String tenantId, UUID runId, String status, RunStatistics value,
                           String errorCode, String errorMessage) {
        MasterDataSyncRunEntity entity = syncRunMapper.selectOne(Wrappers.<MasterDataSyncRunEntity>query()
                .eq("tenant_id", tenantId).eq("id", runId.toString()).eq("status", "RUNNING")
                .last("LIMIT 1"));
        if (entity == null) return;
        LocalDateTime now = now();
        entity.status = status;
        entity.fetchedCount = value.fetched();
        entity.createdCount = value.created();
        entity.changedCount = value.changed();
        entity.duplicateCount = value.duplicates();
        entity.rejectedCount = value.rejected();
        entity.errorCode = errorCode;
        entity.errorMessage = errorMessage;
        entity.finishedAt = now;
        entity.updatedAt = now;
        syncRunMapper.updateById(entity);
        syncLockMapper.delete(Wrappers.<MasterDataSyncLockEntity>query()
                .eq("tenant_id", tenantId).eq("run_id", runId.toString()));
    }

    private void acquireRunLock(String tenantId, String objectType, UUID runId, LocalDateTime now) {
        syncLockMapper.delete(Wrappers.<com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncLockEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("object_type", objectType).le("expires_at", now));
        MasterDataSyncLockEntity lock = new MasterDataSyncLockEntity();
        lock.id = UUID.randomUUID().toString();
        lock.tenantId = tenantId;
        lock.sourceSystem = SOURCE_SYSTEM;
        lock.objectType = objectType;
        lock.runId = runId.toString();
        lock.acquiredAt = now;
        lock.expiresAt = now.plus(Duration.ofHours(6));
        try {
            syncLockMapper.insert(lock);
        } catch (DuplicateKeyException exception) {
            throw new com.rigour.shared.core.exception.BusinessException(
                    com.rigour.shared.core.api.ErrorCode.CONFLICT,
                    "当前租户该类 ERP 数据已有同步任务运行中", java.util.List.of());
        }
    }

    private ProductView toProductView(ProductSpuEntity item,
                                      MasterSourceBindingEntity binding,
                                      Map<String, BrandEntity> brands,
                                      Map<String, CategoryEntity> categories,
                                      ProductViewDetails details) {
        PriceValues prices = details.prices().getOrDefault(item.id, PriceValues.empty());
        Map<String, ProductUnitEntity> units = details.units().getOrDefault(item.id, Map.of());
        ProductUnitEntity middle = units.get("MIDDLE");
        ProductUnitEntity big = units.get("BIG");
        ProductInventoryPolicyEntity policy = details.policies().get(item.id);
        int skuCount = details.skuCounts().getOrDefault(item.id, 0);
        return new ProductView(item.id, binding == null ? null : binding.sourceObjectId,
                item.sourceCategoryId, item.sourceBrandId, item.spuCode,
                item.name, item.brandId == null || brands.get(item.brandId) == null
                        ? null : brands.get(item.brandId).name,
                categories.get(item.id) == null ? null : categories.get(item.id).name,
                item.defaultBarcode, item.baseUnit, binding == null ? null : binding.sourcePutaway,
                item.internalStatus, item.ownershipState, skuCount,
                instant(binding == null ? null : binding.syncedAt), item.model, item.subtitle,
                item.keywords, item.goodsAllocation, item.sourceMultiId,
                prices.order, prices.market, prices.purchase,
                prices.other, middle == null ? null : middle.unitName, big == null ? null : big.unitName,
                middle == null ? null : middle.barcode, big == null ? null : big.barcode,
                item.conversionBarcode,
                middle == null ? null : middle.conversionToBase,
                big == null ? null : big.conversionToBase,
                item.minimumOrder, item.minimumOrderUnit,
                policy == null ? null : policy.lowerBound, policy == null ? null : policy.upperBound,
                policy == null ? null : policy.safetyStock, prices.middleOrder, prices.bigOrder,
                details.images().getOrDefault(item.id, List.of()),
                details.skuViews().getOrDefault(item.id, List.of()),
                details.customFields().getOrDefault(item.id, Map.of()));
    }

    private ProductViewDetails productViewDetails(String tenantId, Set<String> spuIds) {
        if (spuIds.isEmpty()) return ProductViewDetails.empty();
        Map<String, PriceValues> prices = pricesByTarget(tenantId, "SPU", spuIds);
        Map<String, Map<String, ProductUnitEntity>> units = productUnitMapper.selectList(
                        Wrappers.<ProductUnitEntity>query().eq("tenant_id", tenantId)
                                .eq("target_type", "SPU").in("target_id", spuIds))
                .stream().collect(Collectors.groupingBy(item -> item.targetId,
                        Collectors.toMap(item -> item.unitLevel, Function.identity(), (a, b) -> a)));
        Map<String, ProductInventoryPolicyEntity> policies = productInventoryPolicyMapper.selectList(
                        Wrappers.<ProductInventoryPolicyEntity>query().eq("tenant_id", tenantId)
                                .in("spu_id", spuIds))
                .stream().collect(Collectors.toMap(item -> item.spuId, Function.identity(), (a, b) -> a));
        QueryWrapper<ProductSkuEntity> skuQuery = Wrappers.<ProductSkuEntity>query()
                .eq("tenant_id", tenantId).in("spu_id", spuIds);
        skuQuery.select("id", "spu_id");
        Map<String, Integer> skuCounts = productSkuMapper.selectList(skuQuery).stream()
                .collect(Collectors.groupingBy(item -> item.spuId,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
        List<ProductSkuEntity> skuRows = productSkuMapper.selectList(Wrappers.<ProductSkuEntity>query()
                .eq("tenant_id", tenantId).in("spu_id", spuIds).orderByAsc("spu_id").orderByAsc("id"));
        Map<String, ProductSpuEntity> skuProducts = byId(productSpuMapper.selectList(
                Wrappers.<ProductSpuEntity>query().eq("tenant_id", tenantId).in("id", spuIds)),
                item -> item.id);
        Map<String, MasterSourceBindingEntity> nestedSkuBindings = bindingMap(tenantId, "PRODUCT_SKU",
                ids(skuRows, item -> item.id));
        Map<String, PriceValues> nestedSkuPrices = pricesByTarget(tenantId, "SKU", ids(skuRows, item -> item.id));
        Map<String, List<SkuView>> skuViews = new LinkedHashMap<>();
        for (ProductSkuEntity item : skuRows) {
            ProductSpuEntity product = skuProducts.get(item.spuId);
            if (product == null) continue;
            skuViews.computeIfAbsent(item.spuId, ignored -> new java.util.ArrayList<>())
                    .add(skuView(item, product, sourceBinding(nestedSkuBindings, item.id),
                            nestedSkuPrices.getOrDefault(item.id, PriceValues.empty())));
        }
        Map<String, List<ProductImageView>> images = productImageMapper.selectList(
                        Wrappers.<ProductImageEntity>query().eq("tenant_id", tenantId)
                                .in("spu_id", spuIds).orderByAsc("sort_order").orderByAsc("id"))
                .stream().collect(Collectors.groupingBy(item -> item.spuId, LinkedHashMap::new,
                        Collectors.mapping(item -> new ProductImageView(item.id, item.sourceResourceId,
                                item.sourceGoodsId, item.originalName, item.sourceFileName,
                                productMediaUrlResolver.temporaryUrl(tenantId, item.objectKey),
                                value(item.sortOrder, 0), Boolean.TRUE.equals(item.isPrimary)),
                                Collectors.toList())));
        Map<String, Map<String, String>> customFields = productCustomFieldMapper.selectList(
                        Wrappers.<ProductCustomFieldEntity>query().eq("tenant_id", tenantId)
                                .eq("target_type", "SPU").in("target_id", spuIds)
                                .orderByAsc("field_key"))
                .stream().collect(Collectors.groupingBy(item -> item.targetId, LinkedHashMap::new,
                        Collectors.toMap(item -> item.fieldKey, item -> item.fieldValue,
                                (first, ignored) -> first, LinkedHashMap::new)));
        return new ProductViewDetails(prices, units, policies, skuCounts, images, skuViews, customFields);
    }

    private static SkuView skuView(ProductSkuEntity item, ProductSpuEntity product,
                                   MasterSourceBindingEntity binding, PriceValues prices) {
        return new SkuView(item.id, binding == null ? null : binding.sourceObjectId, item.skuCode,
                product.spuCode, product.name, item.barcode, item.unit, item.specificationSummary,
                binding == null ? null : binding.sourcePutaway, item.internalStatus,
                item.ownershipState, instant(binding == null ? null : binding.syncedAt),
                item.sourceOptionsId, item.firstSpecificationValueSourceId,
                item.secondSpecificationValueSourceId, item.middleBarcode, item.bigBarcode,
                prices.order, prices.market, prices.purchase, prices.middleOrder, prices.bigOrder);
    }

    private Map<String, List<SpecificationValueView>> specificationValues(String tenantId,
                                                                            Set<String> specificationIds) {
        if (specificationIds.isEmpty()) return Map.of();
        List<SpecificationValueEntity> rows = specificationValueMapper.selectList(
                Wrappers.<SpecificationValueEntity>query().eq("tenant_id", tenantId)
                        .in("specification_id", specificationIds).orderByAsc("sort_order").orderByAsc("id"));
        Set<String> valueIds = ids(rows, item -> item.id);
        Map<String, MasterSourceBindingEntity> bindings = valueIds.isEmpty() ? Map.of() : bindingMapper.selectList(
                Wrappers.<MasterSourceBindingEntity>query().eq("tenant_id", tenantId)
                        .eq("source_system", SOURCE_SYSTEM).eq("source_object_type", "SPECIFICATION_VALUE")
                        .in("target_id", valueIds)).stream().collect(Collectors.toMap(item -> item.targetId,
                        Function.identity(), (first, ignored) -> first));
        return rows.stream().map(item -> new SpecificationValueView(item.id,
                        bindings.get(item.id) == null ? null : bindings.get(item.id).sourceObjectId,
                        item.sourceParentId, item.valueCode, item.valueName, item.sortOrder,
                        item.status, item.ownershipState))
                .collect(Collectors.groupingBy(item -> rows.stream()
                        .filter(row -> Objects.equals(row.id, item.id())).findFirst().orElseThrow().specificationId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private Map<String, PriceValues> pricesByTarget(String tenantId, String targetType,
                                                     Set<String> targetIds) {
        if (targetIds.isEmpty()) return Map.of();
        return productPriceMapper.selectList(Wrappers.<ProductPriceEntity>query()
                        .eq("tenant_id", tenantId).eq("target_type", targetType).in("target_id", targetIds))
                .stream().collect(Collectors.groupingBy(item -> item.targetId,
                        Collectors.collectingAndThen(Collectors.toList(), this::priceValues)));
    }

    private PriceValues priceValues(List<ProductPriceEntity> rows) {
        Map<String, BigDecimal> values = rows.stream().collect(Collectors.toMap(
                item -> item.priceType + ":" + item.unitLevel, item -> item.amount,
                (first, ignored) -> first));
        return new PriceValues(values.get("ORDER:BASE"), values.get("MARKET:BASE"),
                values.get("PURCHASE:BASE"), values.get("OTHER:BASE"),
                values.get("ORDER:MIDDLE"), values.get("ORDER:BIG"));
    }

    private Map<String, CategoryEntity> primaryCategories(String tenantId, List<ProductSpuEntity> products) {
        Set<String> spuIds = products.stream().map(item -> item.id).collect(Collectors.toSet());
        if (spuIds.isEmpty()) return Map.of();
        List<ProductSpuCategoryEntity> relations = productSpuCategoryMapper.selectList(
                Wrappers.<ProductSpuCategoryEntity>query().eq("tenant_id", tenantId)
                        .eq("is_primary", true).in("spu_id", spuIds));
        Map<String, String> categoryIds = relations.stream()
                .filter(item -> !missing(item.spuId) && !missing(item.categoryId))
                .collect(Collectors.toMap(item -> item.spuId,
                        item -> item.categoryId, (first, ignored) -> first));
        if (categoryIds.isEmpty()) return Map.of();
        Set<String> ids = Set.copyOf(categoryIds.values());
        Map<String, CategoryEntity> categories = byId(categoryMapper.selectList(Wrappers.<CategoryEntity>query()
                .eq("tenant_id", tenantId).in("id", ids)), item -> item.id);
        return categoryIds.entrySet().stream().filter(item -> categories.containsKey(item.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, item -> categories.get(item.getValue())));
    }

    private MasterSourceBindingEntity binding(String tenantId, String sourceType, String sourceId) {
        if (missing(sourceId)) return null;
        return bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType).eq("source_object_id", sourceId)
                .last("LIMIT 1"));
    }

    private Map<String, MasterSourceBindingEntity> bindingMap(String tenantId, String sourceType,
                                                              Set<String> targetIds) {
        if (targetIds.isEmpty()) return Map.of();
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", sourceType).in("target_id", targetIds))
                .stream().collect(Collectors.toMap(item -> item.targetId, Function.identity(), (a, b) -> a));
    }

    private static <T> void applySourcePutaway(QueryWrapper<T> wrapper, String tenantId,
                                               String sourceType, String sourcePutaway,
                                               String targetColumn) {
        if (missing(sourcePutaway)) return;
        wrapper.apply("EXISTS (SELECT 1 FROM erp_master_source_binding b "
                        + "WHERE b.tenant_id = {0} AND b.source_system = 'DINGHUOBAO' "
                        + "AND b.source_object_type = {1} AND b.target_id = " + targetColumn
                        + " AND b.source_putaway = {2})",
                tenantId, sourceType, sourcePutaway.strip());
    }

    private static String limitSql(int begin, int step) {
        return "LIMIT " + Math.max(0, begin) + "," + Math.max(1, step);
    }

    private String resolveTarget(String tenantId, String sourceType, String sourceIdOrCode) {
        if (missing(sourceIdOrCode)) return null;
        MasterSourceBindingEntity exact = binding(tenantId, sourceType, sourceIdOrCode);
        if (exact != null) return exact.targetId;
        MasterSourceBindingEntity byCode = bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType).eq("source_code", sourceIdOrCode)
                .last("LIMIT 1"));
        return byCode == null ? null : byCode.targetId;
    }

    private <T> QueryWrapper<T> simpleWrapper(String tenantId, String query, String status,
                                              String codeColumn, String nameColumn) {
        QueryWrapper<T> wrapper = Wrappers.query();
        wrapper.eq("tenant_id", tenantId);
        if (!missing(query)) {
            String like = "%" + query.strip() + "%";
            wrapper.and(w -> w.like(codeColumn, like).or().like(nameColumn, like));
        }
        if (!missing(status)) wrapper.eq("status", status.strip());
        return wrapper;
    }

    private static boolean externalPrimary(String ownershipState) {
        return Objects.equals(EXTERNAL_PRIMARY, ownershipState);
    }

    private long entityCount(String tableType, String tenantId, String codeColumn, String code) {
        return switch (tableType) {
            case "SPU" -> productSpuMapper.selectCount(Wrappers.<ProductSpuEntity>query()
                    .eq("tenant_id", tenantId).eq(codeColumn, code));
            case "SKU" -> productSkuMapper.selectCount(Wrappers.<ProductSkuEntity>query()
                    .eq("tenant_id", tenantId).eq(codeColumn, code));
            case "CATEGORY" -> categoryMapper.selectCount(Wrappers.<CategoryEntity>query()
                    .eq("tenant_id", tenantId).eq(codeColumn, code));
            case "BRAND" -> brandMapper.selectCount(Wrappers.<BrandEntity>query()
                    .eq("tenant_id", tenantId).eq(codeColumn, code));
            case "TAG" -> productTagMapper.selectCount(Wrappers.<ProductTagEntity>query()
                    .eq("tenant_id", tenantId).eq(codeColumn, code));
            case "SPECIFICATION" -> specificationMapper.selectCount(Wrappers.<SpecificationEntity>query()
                    .eq("tenant_id", tenantId).eq(codeColumn, code));
            default -> throw new IllegalArgumentException("不支持的商品编码表类型: " + tableType);
        };
    }

    private long valueCodeCount(String tenantId, String specificationId, String code) {
        return specificationValueMapper.selectCount(Wrappers.<SpecificationValueEntity>query()
                .eq("tenant_id", tenantId).eq("specification_id", specificationId)
                .eq("value_code", code));
    }

    private String uniqueSpuCode(String tenantId, String preferred, String sourceId) {
        return uniqueCode(tenantId, preferred, "DHB-SPU", sourceId, code -> entityCount("SPU", tenantId, "spu_code", code));
    }

    private String uniqueSkuCode(String tenantId, String preferred, String sourceId) {
        return uniqueCode(tenantId, preferred, "DHB-SKU", sourceId, code -> entityCount("SKU", tenantId, "sku_code", code));
    }

    private String uniqueCategoryCode(String tenantId, String preferred, String sourceId) {
        return uniqueCode(tenantId, preferred, "DHB-CATEGORY", sourceId,
                code -> entityCount("CATEGORY", tenantId, "category_code", code));
    }

    private String uniqueBrandCode(String tenantId, String preferred, String sourceId) {
        return uniqueCode(tenantId, preferred, "DHB-BRAND", sourceId,
                code -> entityCount("BRAND", tenantId, "brand_code", code));
    }

    private String uniqueTagCode(String tenantId, String sourceId) {
        return uniqueCode(tenantId, null, "DHB-TAG", sourceId,
                code -> entityCount("TAG", tenantId, "tag_code", code));
    }

    private String uniqueCode(String tenantId, String preferred, String prefix, String sourceId,
                              Function<String, Long> count) {
        String base = missing(preferred) ? prefix + "-" + shortHash(sourceId) : preferred.strip();
        if (base.length() <= 128 && count.apply(base) == 0) return base;
        for (int attempt = 1; attempt <= 100; attempt++) {
            String suffix = shortHash(sourceId) + (attempt == 1 ? "" : "-" + attempt);
            String candidate = clipped(base, 128 - suffix.length() - 1) + "-" + suffix;
            if (count.apply(candidate) == 0) return candidate;
        }
        throw new IllegalStateException("ERP商品编码生成失败，无法保证租户内唯一");
    }

    private String uniqueValueCode(String tenantId, String specificationId, String preferred,
                                   String sourceId) {
        String base = missing(preferred) ? "DHB-SPEC-VALUE-" + shortHash(sourceId) : preferred.strip();
        if (base.length() <= 128 && valueCodeCount(tenantId, specificationId, base) == 0) return base;
        for (int attempt = 1; attempt <= 100; attempt++) {
            String suffix = shortHash(sourceId) + (attempt == 1 ? "" : "-" + attempt);
            String candidate = clipped(base, 128 - suffix.length() - 1) + "-" + suffix;
            if (valueCodeCount(tenantId, specificationId, candidate) == 0) return candidate;
        }
        throw new IllegalStateException("ERP规格值编码生成失败，无法保证租户内唯一");
    }

    private static <T> Map<String, T> byId(List<T> values, Function<T, String> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
    }

    private static <T> Set<String> ids(List<T> values, Function<T, String> key) {
        return values.stream().map(key).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static MasterSourceBindingEntity sourceBinding(Map<String, MasterSourceBindingEntity> values,
                                                            String targetId) {
        return targetId == null ? null : values.get(targetId);
    }

    private static String targetId(MasterSourceBindingEntity binding) {
        return binding == null ? UUID.randomUUID().toString() : binding.targetId;
    }

    private static boolean changed(MasterSourceBindingEntity binding, String payloadHash) {
        return binding == null || !Objects.equals(binding.sourcePayloadHash, requiredHash(payloadHash));
    }

    private static String legacySkuSourceId(String productSourceId, String sourceId) {
        if (missing(productSourceId) || missing(sourceId)) return null;
        String prefix = productSourceId.strip() + "::";
        return sourceId.startsWith(prefix) && sourceId.length() > prefix.length()
                ? sourceId.substring(prefix.length()) : null;
    }

    private static ImportResult result(MasterSourceBindingEntity existing, boolean changed) {
        return existing == null ? ImportResult.created(1)
                : changed ? ImportResult.changed(1) : ImportResult.duplicate(1);
    }

    private static String importAction(Object existing, boolean changed) {
        return existing == null ? "CREATED" : changed ? "UPDATED" : "UNCHANGED";
    }

    private static boolean contains(String value, String text) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(text);
    }

    private static String putawayStatus(String value) {
        return "T".equalsIgnoreCase(value) ? "ACTIVE" : "INACTIVE";
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String text(UUID value) { return value == null ? null : value.toString(); }

    private static boolean missing(String value) { return value == null || value.isBlank(); }

    private static String blank(String value) { return missing(value) ? null : value.strip(); }

    private static long nextVersion(Long value) { return value == null ? 1 : value + 1; }

    private static int value(Integer value, int fallback) { return value == null ? fallback : value; }

    private static String clipped(String value, int size) { return value.length() <= size ? value : value.substring(0, size); }

    private static String requiredHash(String value) {
        return missing(value) ? shortHash("missing") + "0".repeat(48) : value;
    }

    private static String shortHash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private record BindingResult(String targetId, ImportResult importResult) {
    }

    private record PriceValues(BigDecimal order, BigDecimal market, BigDecimal purchase,
                               BigDecimal other, BigDecimal middleOrder, BigDecimal bigOrder) {
        private static PriceValues empty() {
            return new PriceValues(null, null, null, null, null, null);
        }
    }

    private record ProductViewDetails(Map<String, PriceValues> prices,
                                      Map<String, Map<String, ProductUnitEntity>> units,
                                      Map<String, ProductInventoryPolicyEntity> policies,
                                      Map<String, Integer> skuCounts,
                                      Map<String, List<ProductImageView>> images,
                                      Map<String, List<SkuView>> skuViews,
                                      Map<String, Map<String, String>> customFields) {
        private static ProductViewDetails empty() {
            return new ProductViewDetails(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }
}
