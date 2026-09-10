package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.ExternalProductResolveRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.erp.api.v1.model.ExternalProductRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncResult;
import com.rigour.erp.api.v1.model.ExternalProductSyncRowResult;
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
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductBrandEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductCategoryEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductTagEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterSourceBindingEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductBrandMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductCategoryMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductTagMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private static final TypeReference<Object> SOURCE_FIELDS_TYPE = new TypeReference<>() {
    };
    private static final String SOURCE_PRESENT = "PRESENT";
    private static final Pattern SPEC_CODE = Pattern.compile("[A-Z]+\\d+[A-Z]*|\\d+[A-Z]+");

    private final InternalProductVariantMapper variantMapper;
    private final MasterSourceBindingMapper bindingMapper;
    private final InternalProductCategoryMapper categoryMapper;
    private final InternalProductBrandMapper brandMapper;
    private final InternalProductTagMapper tagMapper;
    private final InternalInventoryWarehouseMapper warehouseMapper;
    private final ProductMediaUrlResolver productMediaUrlResolver;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MybatisPlusProductManagementRepository(
            InternalProductMapper mapper,
            InternalProductVariantMapper variantMapper,
            MasterSourceBindingMapper bindingMapper,
            InternalProductCategoryMapper categoryMapper,
            InternalProductBrandMapper brandMapper,
            InternalProductTagMapper tagMapper,
            InternalInventoryWarehouseMapper warehouseMapper,
            ProductMediaUrlResolver productMediaUrlResolver,
            PlatformTransactionManager transactionManager,
            Clock erpClock) {
        this.baseMapper = mapper;
        this.variantMapper = Objects.requireNonNull(variantMapper, "variantMapper");
        this.bindingMapper = Objects.requireNonNull(bindingMapper, "bindingMapper");
        this.categoryMapper = Objects.requireNonNull(categoryMapper, "categoryMapper");
        this.brandMapper = Objects.requireNonNull(brandMapper, "brandMapper");
        this.tagMapper = Objects.requireNonNull(tagMapper, "tagMapper");
        this.warehouseMapper = Objects.requireNonNull(warehouseMapper, "warehouseMapper");
        this.productMediaUrlResolver = Objects.requireNonNull(productMediaUrlResolver, "productMediaUrlResolver");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
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

    @Override
    public ExternalProductSyncResult syncExternalProducts(String tenantId, String sourceSystem,
                                                          List<ExternalProductRowCommand> rows,
                                                          String actorId,
                                                          BusinessCodeGenerator codeGenerator) {
        Objects.requireNonNull(codeGenerator, "codeGenerator");
        List<ExternalProductSyncRowResult> rowResults = new ArrayList<>();
        List<String> failureMessages = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (ExternalProductRowCommand row : rows == null ? List.<ExternalProductRowCommand>of() : rows) {
            try {
                ProductSyncOutcome outcome = transaction.execute(status ->
                        syncExternalProductRow(tenantId, sourceSystem, row, actorId, codeGenerator));
                if (outcome == null) {
                    outcome = new ProductSyncOutcome(row.sourceProductId(), null, null,
                            null, null, null, "FAILED", "商品同步未返回结果");
                }
                rowResults.add(new ExternalProductSyncRowResult(outcome.sourceProductId(),
                        outcome.productId(), outcome.productVariantId(), outcome.productCode(),
                        outcome.variantCode(), outcome.unitCode(), outcome.status(), outcome.message()));
                switch (outcome.status()) {
                    case "CREATED" -> created++;
                    case "UPDATED" -> updated++;
                    case "UNCHANGED" -> unchanged++;
                    default -> {
                        failed++;
                        failureMessages.add(outcome.message());
                    }
                }
            } catch (RuntimeException exception) {
                failed++;
                String message = "ERP商品同步失败: " + clean(exception.getMessage(), 240);
                failureMessages.add(message);
                rowResults.add(new ExternalProductSyncRowResult(
                        row == null ? null : row.sourceProductId(), null, null, null, null,
                        null, "FAILED", message));
            }
        }
        return new ExternalProductSyncResult(rowResults.size(), created, updated, unchanged,
                failed, rowResults, failureMessages);
    }

    @Override
    public List<ExternalProductResolvedView> resolveExternalProducts(
            String tenantId, String preferredSourceSystem, List<ExternalProductResolveRowCommand> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<String, ExternalProductResolvedView> cache = new HashMap<>();
        return rows.stream()
                .map(row -> resolveExternalProduct(tenantId, preferredSourceSystem, row, cache))
                .toList();
    }

    private ExternalProductResolvedView resolveExternalProduct(
            String tenantId, String preferredSourceSystem, ExternalProductResolveRowCommand row,
            Map<String, ExternalProductResolvedView> cache) {
        String key = resolveCacheKey(row);
        if (key == null) return resolveExternalProduct(tenantId, preferredSourceSystem, row);
        ExternalProductResolvedView cached = cache.get(key);
        if (cached != null) return withReferenceId(cached, row.referenceId());
        ExternalProductResolvedView resolved = resolveExternalProduct(tenantId, preferredSourceSystem, row);
        cache.put(key, withReferenceId(resolved, null));
        return resolved;
    }

    private ExternalProductResolvedView resolveExternalProduct(
            String tenantId, String preferredSourceSystem, ExternalProductResolveRowCommand row) {
        if (row == null || !StringUtils.hasText(row.referenceId())) {
            return unresolved(row, "INVALID", "商品引用行缺少referenceId");
        }
        if (!StringUtils.hasText(row.productCode())
                && !StringUtils.hasText(row.variantCode())
                && !StringUtils.hasText(row.productName())) {
            return unresolved(row, "INVALID", "商品引用缺少编码、SKU编码或商品名称");
        }
        List<ProductResolveCandidate> candidates = new ArrayList<>();
        candidates.addAll(sourceVariantCandidates(tenantId, preferredSourceSystem,
                row.variantCode(), "SOURCE_SKU_CODE_EXACT", 120));
        candidates.addAll(sourceVariantCandidates(tenantId, preferredSourceSystem,
                row.productCode(), "SOURCE_SKU_OR_PRODUCT_CODE_EXACT", 116));
        candidates.addAll(variantCodeCandidates(tenantId, preferredSourceSystem,
                row.variantCode(), "VARIANT_CODE_EXACT", 110));
        candidates.addAll(sourceProductCandidatesByCode(tenantId, preferredSourceSystem,
                row.productCode(), row.specification(), "SOURCE_PRODUCT_CODE_EXACT", 105));
        candidates.addAll(productCodeCandidates(tenantId, preferredSourceSystem,
                row.productCode(), row.specification(), "PRODUCT_CODE_EXACT", 100));
        candidates.addAll(sourceProductCandidatesByName(tenantId, preferredSourceSystem,
                row.productName(), row.specification(), "SOURCE_PRODUCT_NAME_EXACT", 95));
        candidates.addAll(productNameCandidates(tenantId, preferredSourceSystem,
                row.productName(), row.specification(), "PRODUCT_NAME_EXACT", 90));
        candidates.addAll(productNameFuzzyCandidates(tenantId, preferredSourceSystem,
                row.productName(), "PRODUCT_NAME_FUZZY_UNIQUE", 70));
        List<ProductResolveCandidate> unique = uniqueCandidates(candidates);
        if (unique.isEmpty()) {
            return unresolved(row, "NOT_FOUND", "未在ERP商品库中找到可唯一匹配的商品规格");
        }
        int topScore = unique.get(0).score();
        List<ProductResolveCandidate> top = unique.stream()
                .filter(item -> item.score() == topScore)
                .toList();
        if (top.size() > 1) {
            return unresolved(row, "AMBIGUOUS", "匹配到多个ERP商品规格，请补充商品编码或规格后重试");
        }
        ProductResolveCandidate matched = top.get(0);
        return new ExternalProductResolvedView(row.referenceId(),
                matched.product().getId(), matched.variant().getId(),
                matched.product().getProductCode(), matched.variant().getVariantCode(),
                matched.product().getProductName(), matched.variant().getSpecificationSnapshot(),
                first(matched.variant().getUnitCode(), matched.product().getUnitCode()),
                matched.matchedSourceSystem(), matched.strategy(), matched.score(),
                "MATCHED", "已自动匹配ERP商品规格");
    }

    private List<ProductResolveCandidate> sourceVariantCandidates(
            String tenantId, String preferredSourceSystem, String sourceCode, String strategy, int baseScore) {
        if (!StringUtils.hasText(sourceCode)) return List.of();
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq(StringUtils.hasText(preferredSourceSystem), "source_system", preferredSourceSystem)
                        .eq("source_object_type", "PRODUCT_SKU")
                        .eq("source_code", sourceCode.strip())
                        .eq("source_presence", SOURCE_PRESENT)
                        .eq("deleted", 0)
                        .last("LIMIT 20"))
                .stream()
                .map(binding -> variantCandidateFromBinding(tenantId, preferredSourceSystem,
                        binding, strategy, baseScore))
                .flatMap(Optional::stream)
                .toList();
    }

    private List<ProductResolveCandidate> sourceProductCandidatesByCode(
            String tenantId, String preferredSourceSystem, String sourceCode, String specification,
            String strategy, int baseScore) {
        return sourceProductCandidates(tenantId, preferredSourceSystem, "source_code", sourceCode,
                specification, strategy, baseScore);
    }

    private List<ProductResolveCandidate> sourceProductCandidatesByName(
            String tenantId, String preferredSourceSystem, String sourceName, String specification,
            String strategy, int baseScore) {
        return sourceProductCandidates(tenantId, preferredSourceSystem, "source_name", sourceName,
                specification, strategy, baseScore);
    }

    private List<ProductResolveCandidate> sourceProductCandidates(
            String tenantId, String preferredSourceSystem, String sourceColumn, String sourceValue,
            String specification, String strategy, int baseScore) {
        if (!StringUtils.hasText(sourceValue)) return List.of();
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq(StringUtils.hasText(preferredSourceSystem), "source_system", preferredSourceSystem)
                        .eq("source_object_type", "PRODUCT_SPU")
                        .eq(sourceColumn, sourceValue.strip())
                        .eq("source_presence", SOURCE_PRESENT)
                        .eq("deleted", 0)
                        .last("LIMIT 20"))
                .stream()
                .flatMap(binding -> productCandidatesFromBinding(tenantId, preferredSourceSystem,
                        binding, specification, strategy, baseScore).stream())
                .toList();
    }

    private List<ProductResolveCandidate> variantCodeCandidates(
            String tenantId, String preferredSourceSystem, String variantCode, String strategy, int baseScore) {
        if (!StringUtils.hasText(variantCode)) return List.of();
        return variantMapper.selectList(Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .eq(InternalProductVariantEntity::getVariantCode, variantCode.strip())
                        .eq(InternalProductVariantEntity::getDeleted, 0)
                        .last("LIMIT 20"))
                .stream()
                .flatMap(variant -> productCandidateFromVariant(tenantId, preferredSourceSystem,
                        variant, strategy, baseScore).stream())
                .toList();
    }

    private List<ProductResolveCandidate> productCodeCandidates(
            String tenantId, String preferredSourceSystem, String productCode, String specification,
            String strategy, int baseScore) {
        if (!StringUtils.hasText(productCode)) return List.of();
        List<InternalProductEntity> products = getBaseMapper().selectList(
                Wrappers.<InternalProductEntity>lambdaQuery()
                        .eq(InternalProductEntity::getTenantId, tenantId)
                        .eq(InternalProductEntity::getProductCode, productCode.strip())
                        .eq(InternalProductEntity::getDeleted, 0)
                        .last("LIMIT 20"));
        return productCandidates(tenantId, preferredSourceSystem, products, specification, strategy, baseScore);
    }

    private List<ProductResolveCandidate> productNameCandidates(
            String tenantId, String preferredSourceSystem, String productName, String specification,
            String strategy, int baseScore) {
        if (!StringUtils.hasText(productName)) return List.of();
        List<InternalProductEntity> products = getBaseMapper().selectList(
                Wrappers.<InternalProductEntity>lambdaQuery()
                        .eq(InternalProductEntity::getTenantId, tenantId)
                        .eq(InternalProductEntity::getProductName, productName.strip())
                        .eq(InternalProductEntity::getDeleted, 0)
                        .last("LIMIT 20"));
        return productCandidates(tenantId, preferredSourceSystem, products, specification, strategy, baseScore);
    }

    private List<ProductResolveCandidate> productNameFuzzyCandidates(
            String tenantId, String preferredSourceSystem, String productName,
            String strategy, int baseScore) {
        if (!StringUtils.hasText(productName)) return List.of();
        LinkedHashMap<Long, InternalProductEntity> products = new LinkedHashMap<>();
        for (String token : fuzzyProductNameTokens(productName)) {
            getBaseMapper().selectList(Wrappers.<InternalProductEntity>lambdaQuery()
                            .eq(InternalProductEntity::getTenantId, tenantId)
                            .like(InternalProductEntity::getProductName, token)
                            .eq(InternalProductEntity::getDeleted, 0)
                            .last("LIMIT 50"))
                    .forEach(product -> products.putIfAbsent(product.getId(), product));
        }
        if (products.isEmpty()) {
            getBaseMapper().selectList(Wrappers.<InternalProductEntity>lambdaQuery()
                            .eq(InternalProductEntity::getTenantId, tenantId)
                            .eq(InternalProductEntity::getDeleted, 0)
                            .last("LIMIT 300"))
                    .stream()
                    .filter(product -> fuzzyProductNameMatches(product.getProductName(), productName))
                    .forEach(product -> products.putIfAbsent(product.getId(), product));
        }
        if (products.isEmpty()) return List.of();
        List<InternalProductEntity> matched = products.values().stream()
                .filter(product -> fuzzyProductNameMatches(product.getProductName(), productName))
                .toList();
        return productCandidates(tenantId, preferredSourceSystem, matched, null, strategy, baseScore);
    }

    private Optional<ProductResolveCandidate> variantCandidateFromBinding(
            String tenantId, String preferredSourceSystem, MasterSourceBindingEntity binding,
            String strategy, int baseScore) {
        Long variantId = targetId(binding);
        if (variantId == null || !"PRODUCT_VARIANT".equals(binding.targetType)) return Optional.empty();
        InternalProductVariantEntity variant = variantMapper.selectById(variantId);
        if (!valid(variant, tenantId)) return Optional.empty();
        InternalProductEntity product = getBaseMapper().selectById(variant.getProductId());
        if (!valid(product, tenantId)) return Optional.empty();
        String sourceSystem = first(binding.sourceSystem,
                matchedSourceSystem(tenantId, preferredSourceSystem, product.getId(), variant.getId()));
        return Optional.of(new ProductResolveCandidate(product, variant, sourceSystem, strategy,
                sourceScore(preferredSourceSystem, sourceSystem, baseScore)));
    }

    private List<ProductResolveCandidate> productCandidatesFromBinding(
            String tenantId, String preferredSourceSystem, MasterSourceBindingEntity binding,
            String specification, String strategy, int baseScore) {
        Long productId = targetId(binding);
        if (productId == null || !"PRODUCT".equals(binding.targetType)) return List.of();
        InternalProductEntity product = getBaseMapper().selectById(productId);
        if (!valid(product, tenantId)) return List.of();
        return variantCandidatesForProduct(tenantId, preferredSourceSystem, product, specification,
                strategy, baseScore);
    }

    private Optional<ProductResolveCandidate> productCandidateFromVariant(
            String tenantId, String preferredSourceSystem, InternalProductVariantEntity variant,
            String strategy, int baseScore) {
        if (!valid(variant, tenantId)) return Optional.empty();
        InternalProductEntity product = getBaseMapper().selectById(variant.getProductId());
        if (!valid(product, tenantId)) return Optional.empty();
        String sourceSystem = matchedSourceSystem(tenantId, preferredSourceSystem, product.getId(), variant.getId());
        return Optional.of(new ProductResolveCandidate(product, variant, sourceSystem, strategy,
                sourceScore(preferredSourceSystem, sourceSystem, baseScore)));
    }

    private List<ProductResolveCandidate> productCandidates(
            String tenantId, String preferredSourceSystem, List<InternalProductEntity> products,
            String specification, String strategy, int baseScore) {
        if (products == null || products.isEmpty()) return List.of();
        List<ProductResolveCandidate> candidates = new ArrayList<>();
        for (InternalProductEntity product : products) {
            if (!valid(product, tenantId)) continue;
            candidates.addAll(variantCandidatesForProduct(
                    tenantId, preferredSourceSystem, product, specification, strategy, baseScore));
        }
        return candidates;
    }

    private List<ProductResolveCandidate> variantCandidatesForProduct(
            String tenantId, String preferredSourceSystem, InternalProductEntity product,
            String specification, String strategy, int baseScore) {
        List<InternalProductVariantEntity> variants = variantsByProduct(tenantId, Set.of(product.getId()))
                .getOrDefault(product.getId(), List.of());
        if (variants.isEmpty()) return List.of();
        List<InternalProductVariantEntity> selected = List.of();
        if (StringUtils.hasText(specification)) {
            selected = variants.stream()
                    .filter(variant -> sameReference(variant.getSpecificationSnapshot(), specification))
                    .toList();
        }
        if (selected.isEmpty() && StringUtils.hasText(specification)) {
            selected = variants.stream()
                    .filter(variant -> fuzzySpecificationMatches(variant.getSpecificationSnapshot(), specification))
                    .toList();
            if (!selected.isEmpty()) strategy = strategy + "_SPEC_FUZZY";
        }
        if (selected.isEmpty()) {
            selected = variants.stream()
                    .filter(variant -> Boolean.TRUE.equals(variant.getDefaultFlag()))
                    .toList();
        }
        if (selected.isEmpty() && variants.size() == 1) {
            selected = variants;
        }
        if (selected.isEmpty() && allowNameOnlyVariantFallback(strategy)) {
            selected = variants.stream().limit(1).toList();
            strategy = strategy + "_FIRST_VARIANT";
        }
        if (selected.isEmpty()) return List.of();
        List<ProductResolveCandidate> candidates = new ArrayList<>();
        for (InternalProductVariantEntity variant : selected) {
            String sourceSystem = matchedSourceSystem(tenantId, preferredSourceSystem,
                    product.getId(), variant.getId());
            candidates.add(new ProductResolveCandidate(product, variant, sourceSystem, strategy,
                    sourceScore(preferredSourceSystem, sourceSystem, baseScore)));
        }
        return candidates;
    }

    private static boolean allowNameOnlyVariantFallback(String strategy) {
        return strategy != null && strategy.contains("PRODUCT_NAME_FUZZY");
    }

    private String matchedSourceSystem(String tenantId, String preferredSourceSystem,
                                       Long productId, Long variantId) {
        String variantSource = targetSourceSystem(tenantId, preferredSourceSystem,
                "PRODUCT_VARIANT", variantId);
        if (variantSource != null) return variantSource;
        String productSource = targetSourceSystem(tenantId, preferredSourceSystem, "PRODUCT", productId);
        if (productSource != null) return productSource;
        InternalProductEntity product = productId == null ? null : getBaseMapper().selectById(productId);
        return product == null ? null : product.getSourceSystemCode();
    }

    private String targetSourceSystem(String tenantId, String preferredSourceSystem,
                                      String targetType, Long targetId) {
        if (targetId == null) return null;
        List<MasterSourceBindingEntity> bindings = bindingMapper.selectList(
                Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq("target_type", targetType)
                        .eq("target_id", String.valueOf(targetId))
                        .eq("source_presence", SOURCE_PRESENT)
                        .eq("deleted", 0)
                        .last("LIMIT 20"));
        if (bindings.isEmpty()) return null;
        return bindings.stream()
                .map(binding -> binding.sourceSystem)
                .filter(sourceSystem -> Objects.equals(preferredSourceSystem, sourceSystem))
                .findFirst()
                .orElse(bindings.get(0).sourceSystem);
    }

    private static List<ProductResolveCandidate> uniqueCandidates(List<ProductResolveCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Map<String, ProductResolveCandidate> unique = new LinkedHashMap<>();
        for (ProductResolveCandidate candidate : candidates) {
            if (candidate == null || candidate.product() == null || candidate.variant() == null) continue;
            String key = candidate.product().getId() + "::" + candidate.variant().getId();
            ProductResolveCandidate current = unique.get(key);
            if (current == null || candidate.score() > current.score()) {
                unique.put(key, candidate);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(ProductResolveCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.product().getId())
                        .thenComparing(candidate -> candidate.variant().getId()))
                .toList();
    }

    private static ExternalProductResolvedView unresolved(
            ExternalProductResolveRowCommand row, String status, String message) {
        return new ExternalProductResolvedView(row == null ? null : row.referenceId(),
                null, null, null, null, null, null, null, null, null, 0, status, message);
    }

    private static int sourceScore(String preferredSourceSystem, String matchedSourceSystem, int baseScore) {
        return StringUtils.hasText(preferredSourceSystem)
                && Objects.equals(preferredSourceSystem, matchedSourceSystem)
                ? baseScore + 5
                : baseScore;
    }

    private static Long targetId(MasterSourceBindingEntity binding) {
        if (binding == null || !StringUtils.hasText(binding.targetId)) return null;
        try {
            long value = Long.parseLong(binding.targetId);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean valid(InternalProductEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductVariantEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean sameReference(String left, String right) {
        String a = normalizeReference(left);
        String b = normalizeReference(right);
        return a != null && a.equals(b);
    }

    private static String normalizeReference(String value) {
        String text = clean(value, 500);
        if (text == null) return null;
        String normalized = text.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、（）【】《》“”‘’￥¥]+", "");
        return normalized.isBlank() ? null : normalized;
    }

    private static boolean fuzzySpecificationMatches(String left, String right) {
        String a = normalizeReference(left);
        String b = normalizeReference(right);
        if (a == null || b == null) return false;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        Matcher leftCode = SPEC_CODE.matcher(a);
        while (leftCode.find()) {
            String code = leftCode.group();
            if (code.length() >= 2 && b.contains(code)) return true;
        }
        Matcher rightCode = SPEC_CODE.matcher(b);
        while (rightCode.find()) {
            String code = rightCode.group();
            if (code.length() >= 2 && a.contains(code)) return true;
        }
        return false;
    }

    private static boolean fuzzyProductNameMatches(String productName, String sourceName) {
        String product = normalizeReference(productName);
        String source = normalizeReference(sourceName);
        if (product == null || source == null) return false;
        if (product.equals(source) || product.contains(source) || source.contains(product)) return true;
        return fuzzyProductNameTokens(sourceName).stream()
                .map(MybatisPlusProductManagementRepository::normalizeReference)
                .filter(Objects::nonNull)
                .anyMatch(token -> token.length() >= 2 && product.contains(token));
    }

    private static List<String> fuzzyProductNameTokens(String value) {
        String text = clean(value, 200);
        if (text == null) return List.of();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        tokens.add(text);
        for (String part : text.split("\\s*[-－–—,，/]+\\s*")) {
            String token = clean(part, 200);
            if (token == null || token.length() < 2) continue;
            if (token.matches(".*\\d.*") && (token.contains("箱") || token.contains("桶") || token.contains("袋"))) {
                continue;
            }
            tokens.add(token);
            addProductNameSlices(tokens, token);
        }
        return List.copyOf(tokens);
    }

    private static void addProductNameSlices(Set<String> tokens, String value) {
        String text = clean(value, 200);
        if (text == null || text.length() < 4) return;
        int max = Math.min(text.length(), 8);
        for (int length = max; length >= 4; length--) {
            for (int start = 0; start + length <= text.length(); start++) {
                String token = text.substring(start, start + length);
                if (!token.matches(".*[\\p{IsHan}A-Za-z].*")) continue;
                if (token.matches(".*\\d.*") && (token.contains("箱") || token.contains("桶") || token.contains("袋"))) {
                    continue;
                }
                tokens.add(token);
            }
        }
    }

    private static String resolveCacheKey(ExternalProductResolveRowCommand row) {
        if (row == null) return null;
        String key = String.join("|",
                Objects.toString(normalizeReference(row.productCode()), ""),
                Objects.toString(normalizeReference(row.variantCode()), ""),
                Objects.toString(normalizeReference(row.productName()), ""),
                Objects.toString(normalizeReference(row.specification()), ""));
        return key.replace("|", "").isBlank() ? null : key;
    }

    private static ExternalProductResolvedView withReferenceId(ExternalProductResolvedView view, String referenceId) {
        if (view == null) return null;
        return new ExternalProductResolvedView(referenceId, view.productId(), view.productVariantId(),
                view.productCode(), view.variantCode(), view.productName(), view.specification(),
                view.unitCode(), view.matchedSourceSystem(), view.matchStrategy(), view.matchScore(),
                view.status(), view.message());
    }

    private ProductSyncOutcome syncExternalProductRow(String tenantId, String sourceSystem,
                                                      ExternalProductRowCommand row,
                                                      String actorId,
                                                      BusinessCodeGenerator codeGenerator) {
        LocalDateTime now = now();
        String sourceTenantKey = defaultText(row.sourceTenantKey(), "DEFAULT", 128);
        InternalProductEntity existing = productBySource(tenantId, sourceSystem,
                sourceTenantKey, row.sourceProductId());
        if (existing == null) {
            String productCode = codeGenerator.generateUnique(ErpBusinessCodeRules.PRODUCT,
                    row.sourceCreatedAt(), candidate -> !existsByCode(tenantId, candidate));
            InternalProductEntity entity = new InternalProductEntity();
            copyExternalProductFields(entity, tenantId, productCode, sourceSystem, sourceTenantKey,
                    row, actorId, now);
            getBaseMapper().insert(entity);
            ProductVariantProjection variant = upsertExternalDefaultVariant(
                    tenantId, entity.getId(), row, actorId, now, codeGenerator);
            return new ProductSyncOutcome(row.sourceProductId(), entity.getId(), entity.getProductCode(),
                    variant.id(), variant.variantCode(), variant.unitCode(), "CREATED", "ERP商品已创建");
        }
        if (sameHash(existing.getSourcePayloadHash(), row.sourcePayloadHash())) {
            ProductVariantProjection variant = defaultVariantProjection(tenantId, existing.getId());
            if (variant == null) {
                variant = upsertExternalDefaultVariant(tenantId, existing.getId(), row, actorId, now,
                        codeGenerator);
                return new ProductSyncOutcome(row.sourceProductId(), existing.getId(), existing.getProductCode(),
                        variant.id(), variant.variantCode(), variant.unitCode(), "UPDATED",
                        "ERP商品已补齐默认规格");
            }
            return new ProductSyncOutcome(row.sourceProductId(), existing.getId(), existing.getProductCode(),
                    variant.id(), variant.variantCode(), variant.unitCode(), "UNCHANGED", "ERP商品无变化");
        }
        copyExternalProductFields(existing, tenantId, existing.getProductCode(), sourceSystem,
                sourceTenantKey, row, actorId, now);
        int nextRevision = existing.getRevision() == null ? 2 : existing.getRevision() + 1;
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductEntity>lambdaUpdate()
                .set(InternalProductEntity::getProductName, existing.getProductName())
                .set(InternalProductEntity::getBusinessLineName, existing.getBusinessLineName())
                .set(InternalProductEntity::getCategoryNameSnapshot, existing.getCategoryNameSnapshot())
                .set(InternalProductEntity::getBrandNameSnapshot, existing.getBrandNameSnapshot())
                .set(InternalProductEntity::getIndustryName, existing.getIndustryName())
                .set(InternalProductEntity::getProductSpecification, existing.getProductSpecification())
                .set(InternalProductEntity::getUnitCode, existing.getUnitCode())
                .set(InternalProductEntity::getSaleTypeCode, existing.getSaleTypeCode())
                .set(InternalProductEntity::getShelfStatusCode, existing.getShelfStatusCode())
                .set(InternalProductEntity::getSourceStatusName, existing.getSourceStatusName())
                .set(InternalProductEntity::getSubmitStatusCode, existing.getSubmitStatusCode())
                .set(InternalProductEntity::getRemark, existing.getRemark())
                .set(InternalProductEntity::getSourceSystemCode, existing.getSourceSystemCode())
                .set(InternalProductEntity::getSourceTenantKey, existing.getSourceTenantKey())
                .set(InternalProductEntity::getSourceProductId, existing.getSourceProductId())
                .set(InternalProductEntity::getSourceDocumentNo, existing.getSourceDocumentNo())
                .set(InternalProductEntity::getSourceCreatedAt, existing.getSourceCreatedAt())
                .set(InternalProductEntity::getSourceUpdatedAt, existing.getSourceUpdatedAt())
                .set(InternalProductEntity::getSourcePayloadHash, existing.getSourcePayloadHash())
                .set(InternalProductEntity::getSourcePayloadJson, existing.getSourcePayloadJson())
                .set(InternalProductEntity::getRevision, nextRevision)
                .set(InternalProductEntity::getUpdatedBy, actorId)
                .set(InternalProductEntity::getUpdatedTime, now)
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getId, existing.getId())
                .eq(InternalProductEntity::getDeleted, 0));
        if (updated != 1) throw conflict("ERP商品已被其他流程修改，请重试");
        ProductVariantProjection variant = upsertExternalDefaultVariant(
                tenantId, existing.getId(), row, actorId, now, codeGenerator);
        return new ProductSyncOutcome(row.sourceProductId(), existing.getId(), existing.getProductCode(),
                variant.id(), variant.variantCode(), variant.unitCode(), "UPDATED", "ERP商品已更新");
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

    private InternalProductEntity productBySource(String tenantId, String sourceSystem,
                                                  String sourceTenantKey, String sourceProductId) {
        return getBaseMapper().selectOne(Wrappers.<InternalProductEntity>lambdaQuery()
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getSourceSystemCode, sourceSystem)
                .eq(InternalProductEntity::getSourceTenantKey, sourceTenantKey)
                .eq(InternalProductEntity::getSourceProductId, sourceProductId)
                .eq(InternalProductEntity::getDeleted, 0)
                .last("LIMIT 1"));
    }

    private static void copyExternalProductFields(InternalProductEntity entity, String tenantId,
                                                  String productCode, String sourceSystem,
                                                  String sourceTenantKey, ExternalProductRowCommand row,
                                                  String actorId, LocalDateTime now) {
        entity.setTenantId(tenantId);
        entity.setProductCode(productCode);
        entity.setProductName(clean(row.productName(), 200));
        entity.setBusinessLineName(clean(row.businessLineName(), 120));
        entity.setCategoryNameSnapshot(clean(row.categoryName(), 120));
        entity.setBrandNameSnapshot(clean(row.brandName(), 120));
        entity.setIndustryName(clean(row.industryName(), 120));
        entity.setProductSpecification(clean(row.specification(), 500));
        entity.setUnitCode(clean(row.unitCode(), 64));
        entity.setSaleTypeCode(sourceStopped(row.statusName()) ? "STOP_SALE" : "SPOT");
        entity.setShelfStatusCode(sourceStopped(row.statusName()) ? "OFF_SHELF" : "ON_SHELF");
        entity.setSourceStatusName(clean(row.statusName(), 80));
        entity.setSubmitStatusCode("DRAFT");
        entity.setRemark("外部来源：" + sourceSystem);
        entity.setSourceSystemCode(sourceSystem);
        entity.setSourceTenantKey(sourceTenantKey);
        entity.setSourceProductId(clean(row.sourceProductId(), 128));
        entity.setSourceDocumentNo(clean(row.sourceDocumentNo(), 128));
        entity.setSourceCreatedAt(time(row.sourceCreatedAt()));
        entity.setSourceUpdatedAt(time(row.sourceUpdatedAt()));
        entity.setSourcePayloadHash(clean(row.sourcePayloadHash(), 64));
        entity.setSourcePayloadJson(clean(row.sourcePayloadJson(), 20_000));
        if (entity.getCreatedBy() == null) {
            entity.setRevision(1);
            entity.setCreatedBy(actorId);
            entity.setCreatedTime(now);
            entity.setImageKeysJson(json(List.of()));
            entity.setRecommendProductIdsJson(json(List.of()));
            entity.setTagCodesJson(json(List.of()));
            entity.setOrderMultipleFlag(false);
            entity.setDeleted(0);
        }
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
    }

    private ProductVariantProjection upsertExternalDefaultVariant(String tenantId, Long productId,
                                                                  ExternalProductRowCommand row,
                                                                  String actorId, LocalDateTime now,
                                                                  BusinessCodeGenerator codeGenerator) {
        ProductVariantWrite write = externalVariant(tenantId, row, codeGenerator);
        List<InternalProductVariantEntity> variants = variantsByProduct(tenantId, Set.of(productId))
                .getOrDefault(productId, List.of());
        if (variants.isEmpty()) {
            InternalProductVariantEntity created = variantEntity(tenantId, productId, write, actorId, now);
            variantMapper.insert(created);
            return variantProjection(created);
        }
        InternalProductVariantEntity existing = defaultVariant(variants);
        int nextRevision = existing.getRevision() == null ? 2 : existing.getRevision() + 1;
        int updated = variantMapper.update(null, Wrappers.<InternalProductVariantEntity>lambdaUpdate()
                .set(InternalProductVariantEntity::getSpecificationSnapshot, write.specificationSnapshot())
                .set(InternalProductVariantEntity::getUnitCode, write.unitCode())
                .set(InternalProductVariantEntity::getSalePrice, write.salePrice())
                .set(InternalProductVariantEntity::getMarketPrice, write.marketPrice())
                .set(InternalProductVariantEntity::getPurchasePrice, write.purchasePrice())
                .set(InternalProductVariantEntity::getDefaultFlag, true)
                .set(InternalProductVariantEntity::getRemark, write.remark())
                .set(InternalProductVariantEntity::getRevision, nextRevision)
                .set(InternalProductVariantEntity::getUpdatedBy, actorId)
                .set(InternalProductVariantEntity::getUpdatedTime, now)
                .eq(InternalProductVariantEntity::getTenantId, tenantId)
                .eq(InternalProductVariantEntity::getProductId, productId)
                .eq(InternalProductVariantEntity::getId, existing.getId())
                .eq(InternalProductVariantEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品规格已被其他流程修改，请重试");
        return new ProductVariantProjection(existing.getId(), existing.getVariantCode(), write.unitCode());
    }

    private ProductVariantProjection defaultVariantProjection(String tenantId, Long productId) {
        InternalProductVariantEntity variant = defaultVariant(variantsByProduct(tenantId, Set.of(productId))
                .getOrDefault(productId, List.of()));
        return variantProjection(variant);
    }

    private static ProductVariantProjection variantProjection(InternalProductVariantEntity variant) {
        if (variant == null) return null;
        return new ProductVariantProjection(variant.getId(), variant.getVariantCode(), variant.getUnitCode());
    }

    private ProductVariantWrite externalVariant(String tenantId, ExternalProductRowCommand row,
                                                BusinessCodeGenerator codeGenerator) {
        String variantCode = codeGenerator.generateUnique(ErpBusinessCodeRules.SKU,
                row.sourceCreatedAt(), candidate -> !existsVariantByCode(tenantId, candidate));
        return new ProductVariantWrite(null, variantCode,
                first(clean(row.specification(), 500), clean(row.productName(), 200)),
                clean(row.unitCode(), 64), row.salePrice(), row.marketPrice(), row.purchasePrice(),
                null, null, null, true, "飞书默认规格");
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
                entity.getBusinessLineName(), entity.getCategoryId(),
                first(value(categories, entity.getCategoryId()), entity.getCategoryNameSnapshot()),
                entity.getCategoryNameSnapshot(), entity.getBrandId(),
                first(value(brands, entity.getBrandId()), entity.getBrandNameSnapshot()),
                entity.getBrandNameSnapshot(), entity.getIndustryName(),
                entity.getUnitCode(), entity.getSaleTypeCode(),
                entity.getShelfStatusCode(), entity.getSubmitStatusCode(),
                entity.getSourceSystemCode(), entity.getSourceDocumentNo(),
                instant(entity.getSourceCreatedAt()), instant(entity.getSourceUpdatedAt()),
                entity.getDefaultWarehouseId(),
                value(warehouses, entity.getDefaultWarehouseId()),
                defaultVariant == null ? null : defaultVariant.getSalePrice(),
                mainImageKey, temporaryUrl(tenantId, mainImageKey), variants.size(), entity.getRevision(),
                instant(entity.getUpdatedTime()));
    }

    private ProductManagementDetailView detail(String tenantId, InternalProductEntity entity,
                                               List<InternalProductVariantEntity> variants) {
        return new ProductManagementDetailView(entity.getId(), entity.getProductCode(), entity.getProductName(),
                entity.getBusinessLineName(), entity.getCategoryId(),
                first(categoryName(tenantId, entity.getCategoryId()), entity.getCategoryNameSnapshot()),
                entity.getCategoryNameSnapshot(), entity.getBrandId(),
                first(brandName(tenantId, entity.getBrandId()), entity.getBrandNameSnapshot()),
                entity.getBrandNameSnapshot(), entity.getIndustryName(), entity.getProductSpecification(),
                entity.getUnitCode(), entity.getMinOrderQuantity(), entity.getOrderMultipleFlag(),
                entity.getOrderMultipleQuantity(), entity.getSaleTypeCode(), entity.getShelfStatusCode(),
                entity.getSourceStatusName(), parseStrings(entity.getTagCodesJson()),
                entity.getLimitQuantity(), entity.getDefaultWarehouseId(),
                warehouseName(tenantId, entity.getDefaultWarehouseId()), imageViews(tenantId, entity.getImageKeysJson()),
                variants.stream().map(MybatisPlusProductManagementRepository::variantView).toList(),
                parseLongs(entity.getRecommendProductIdsJson()), entity.getSubmitStatusCode(), entity.getRemark(),
                entity.getSourceSystemCode(), entity.getSourceDocumentNo(),
                instant(entity.getSourceCreatedAt()), instant(entity.getSourceUpdatedAt()),
                parseSourceFields(entity.getSourcePayloadJson()), entity.getRevision(),
                entity.getCreatedBy(), instant(entity.getCreatedTime()),
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

    private static LocalDateTime time(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static boolean sameHash(String current, String incoming) {
        return current != null && incoming != null && current.equals(incoming);
    }

    private static boolean sourceStopped(String sourceStatusName) {
        String value = clean(sourceStatusName, 80);
        return value != null && (value.contains("停") || value.contains("下架")
                || value.contains("失效") || value.contains("作废"));
    }

    private static String defaultText(String value, String defaultValue, int max) {
        String cleaned = clean(value, max);
        return cleaned == null ? defaultValue : cleaned;
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value instanceof String text && text.isBlank()) continue;
            if (value != null) return value;
        }
        return null;
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

    private static Map<String, Object> parseSourceFields(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            Object values = JSON_MAPPER.readValue(json, SOURCE_FIELDS_TYPE);
            if (!(values instanceof Map<?, ?> map)) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) result.put(String.valueOf(key), value);
            });
            return result;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ERP商品来源字段JSON反序列化失败", exception);
        }
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static <K, V> V value(Map<K, V> values, K key) {
        return key == null ? null : values.get(key);
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private record ProductImageJson(String imageKey, String imageTypeCode, Integer ordinal) {
    }

    private record ProductVariantProjection(Long id, String variantCode, String unitCode) {
    }

    private record ProductSyncOutcome(String sourceProductId, Long productId, String productCode,
                                      Long productVariantId, String variantCode, String unitCode,
                                      String status, String message) {
    }

    private record ProductResolveCandidate(InternalProductEntity product,
                                           InternalProductVariantEntity variant,
                                           String matchedSourceSystem,
                                           String strategy,
                                           int score) {
    }
}
