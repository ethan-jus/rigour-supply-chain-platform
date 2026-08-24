package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.erp.application.port.out.ProductMasterDataStore;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.erp.domain.model.product.Brand;
import com.rigour.erp.domain.model.product.Category;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.ProductImage;
import com.rigour.erp.domain.model.product.Sku;
import com.rigour.erp.domain.model.product.Specification;
import com.rigour.erp.domain.model.product.SpecificationValue;
import com.rigour.erp.domain.model.product.Tag;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductBrandEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductCategoryEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductSpecificationEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductSpecificationValueEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductTagEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncLockEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncRunEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterSourceBindingEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductBrandMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductCategoryMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductSpecificationMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductSpecificationValueMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductTagMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncLockMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * ERP 商品订货宝同步仓储。
 *
 * <p>该仓储只写 ERP 自研业务表和来源绑定表；旧的订货宝投影表不再参与同步链路。</p>
 */
@Repository
public class MybatisPlusProductMasterDataRepository implements ProductMasterDataStore {
    private static final String SOURCE_SYSTEM = "DINGHUOBAO";
    private static final String INTEGRATION_SOURCE_SYSTEM = "DHB";
    private static final String SYNC_ACTOR = "DHB_SYNC";
    private static final String TRIGGER_MANUAL = "MANUAL";
    private static final String TRIGGER_SCHEDULED = "SCHEDULED";
    private static final String PRESENT = "PRESENT";
    private static final String SOURCE_ABSENT = "SOURCE_ABSENT";
    private static final long RUN_LEASE_MINUTES = 30;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SOURCE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final InternalProductCategoryMapper categoryMapper;
    private final InternalProductBrandMapper brandMapper;
    private final InternalProductSpecificationMapper specificationMapper;
    private final InternalProductSpecificationValueMapper specificationValueMapper;
    private final InternalProductTagMapper tagMapper;
    private final InternalProductMapper productMapper;
    private final InternalProductVariantMapper variantMapper;
    private final MasterSourceBindingMapper bindingMapper;
    private final MasterDataSyncRunMapper syncRunMapper;
    private final MasterDataSyncLockMapper syncLockMapper;
    private final BusinessCodeGenerator codeGenerator = new BusinessCodeGenerator();
    private final Clock clock;

    public MybatisPlusProductMasterDataRepository(
            InternalProductCategoryMapper categoryMapper,
            InternalProductBrandMapper brandMapper,
            InternalProductSpecificationMapper specificationMapper,
            InternalProductSpecificationValueMapper specificationValueMapper,
            InternalProductTagMapper tagMapper,
            InternalProductMapper productMapper,
            InternalProductVariantMapper variantMapper,
            MasterSourceBindingMapper bindingMapper,
            MasterDataSyncRunMapper syncRunMapper,
            MasterDataSyncLockMapper syncLockMapper,
            Clock clock) {
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
        this.specificationMapper = specificationMapper;
        this.specificationValueMapper = specificationValueMapper;
        this.tagMapper = tagMapper;
        this.productMapper = productMapper;
        this.variantMapper = variantMapper;
        this.bindingMapper = bindingMapper;
        this.syncRunMapper = syncRunMapper;
        this.syncLockMapper = syncLockMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                         MasterDataObjectType objectType, int maxPages) {
        return startRun(tenantId, connectorId, actorId, objectType, maxPages, TRIGGER_MANUAL);
    }

    @Override
    @Transactional
    public UUID startScheduledRun(String tenantId, UUID connectorId, UUID actorId,
                                  MasterDataObjectType objectType, int maxPages) {
        return startRun(tenantId, connectorId, actorId, objectType, maxPages, TRIGGER_SCHEDULED);
    }

    @Override
    @Transactional
    public ImportResult importCategory(String tenantId, UUID runId, Category category) {
        if (missing(category.sourceId()) || missing(category.name())) return ImportResult.rejected(1);
        return upsertCategory(tenantId, runId, category).result();
    }

    @Override
    @Transactional
    public ImportResult importBrand(String tenantId, UUID runId, Brand brand) {
        if (missing(brand.sourceId()) || missing(brand.name())) return ImportResult.rejected(1);
        return upsertBrand(tenantId, runId, brand).result();
    }

    @Override
    @Transactional
    public ImportResult importTag(String tenantId, UUID runId, Tag tag) {
        if (missing(tag.sourceId()) || missing(tag.name())) return ImportResult.rejected(1);
        return upsertTag(tenantId, runId, tag).result();
    }

    @Override
    @Transactional
    public ImportResult importSpecification(String tenantId, UUID runId, Specification specification) {
        if (missing(specification.sourceId()) || missing(specification.name())) {
            return ImportResult.rejected(1);
        }
        UpsertResult spec = upsertSpecification(tenantId, runId, specification);
        ImportResult result = spec.result();
        for (SpecificationValue value : specification.values()) {
            if (missing(value.sourceId()) || missing(value.name())) {
                result = result.plus(ImportResult.rejected(1));
            } else {
                result = result.plus(upsertSpecificationValue(tenantId, runId, spec.id(), value).result());
            }
        }
        return result;
    }

    @Override
    @Transactional
    public ImportResult importProduct(String tenantId, UUID runId, Product product) {
        if (missing(product.sourceId()) || missing(product.name())) return ImportResult.rejected(1);
        UpsertResult upserted = upsertProduct(tenantId, runId, product);
        ImportResult result = upserted.result();
        if (product.skus().isEmpty()) {
            return result;
        }
        for (Sku sku : product.skus()) {
            if (missing(sku.sourceId()) || (missing(sku.code()) && missing(sku.barcode()))) {
                result = result.plus(ImportResult.rejected(1));
            } else {
                result = result.plus(upsertVariant(tenantId, runId, upserted.id(), product, sku).result());
            }
        }
        return result;
    }

    @Override
    @Transactional
    public void reconcileSourcePresence(String tenantId, UUID runId,
                                        Map<String, Set<String>> seenSourceIds) {
        LocalDateTime now = now();
        seenSourceIds.forEach((sourceType, values) -> {
            Set<String> seen = values == null ? Set.of() : values;
            List<MasterSourceBindingEntity> bindings = bindingMapper.selectList(
                    Wrappers.<MasterSourceBindingEntity>query()
                            .eq("tenant_id", tenantId)
                            .eq("source_system", SOURCE_SYSTEM)
                            .eq("source_object_type", sourceType));
            for (MasterSourceBindingEntity binding : bindings) {
                boolean present = seen.contains(binding.sourceObjectId);
                if (!present) {
                    softDeleteAbsentTarget(tenantId, binding, now);
                }
                String desired = present ? PRESENT : SOURCE_ABSENT;
                if (Objects.equals(binding.sourcePresence, desired)) continue;
                binding.sourcePresence = desired;
                binding.sourceAbsentAt = present ? null : now;
                binding.lastSyncRunId = text(runId);
                binding.version = nextVersion(binding.version);
                binding.updatedAt = now;
                bindingMapper.updateById(binding);
            }
        });
    }

    private void softDeleteAbsentTarget(String tenantId, MasterSourceBindingEntity binding,
                                        LocalDateTime now) {
        Long id = longTargetId(binding);
        if (id == null) return;
        if ("PRODUCT_SKU".equals(binding.sourceObjectType)) {
            InternalProductVariantEntity variant = variantMapper.selectById(id);
            if (valid(variant, tenantId) && sourceWritable(variant.getUpdatedBy())) {
                softDeleteVariant(variant, now);
            }
            return;
        }
        if (!"PRODUCT_SPU".equals(binding.sourceObjectType)) return;
        InternalProductEntity product = productMapper.selectById(id);
        if (!valid(product, tenantId) || !sourceWritable(product.getUpdatedBy())) return;
        for (InternalProductVariantEntity variant : variantMapper.selectList(
                Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .eq(InternalProductVariantEntity::getProductId, product.getId())
                        .eq(InternalProductVariantEntity::getDeleted, 0))) {
            if (sourceWritable(variant.getUpdatedBy())) {
                softDeleteVariant(variant, now);
            }
        }
        product.setDeleted(1);
        product.setUpdatedBy(SYNC_ACTOR);
        product.setUpdatedTime(now);
        product.setRevision(value(product.getRevision(), 1) + 1);
        productMapper.updateById(product);
    }

    private void softDeleteVariant(InternalProductVariantEntity variant, LocalDateTime now) {
        variant.setDeleted(1);
        variant.setUpdatedBy(SYNC_ACTOR);
        variant.setUpdatedTime(now);
        variant.setRevision(value(variant.getRevision(), 1) + 1);
        variantMapper.updateById(variant);
    }

    @Override
    @Transactional
    public void completeRunWithSourcePresence(String tenantId, UUID runId,
                                              Map<String, Set<String>> seenSourceIds,
                                              RunStatistics statistics) {
        requireRunningRunForUpdate(tenantId, runId);
        reconcileSourcePresence(tenantId, runId, seenSourceIds);
        completeRun(tenantId, runId, statistics);
    }

    @Override
    @Transactional
    public void completeRun(String tenantId, UUID runId, RunStatistics statistics) {
        finishRun(tenantId, runId, statistics.dictionaryAudit().unmapped() == 0
                ? "SUCCEEDED" : "SUCCEEDED_WITH_WARNINGS", statistics, null, null);
    }

    @Override
    @Transactional
    public void failRun(String tenantId, UUID runId, RunStatistics statistics, RuntimeException error) {
        String message = error.getMessage();
        if (message != null && message.length() > 2000) message = message.substring(0, 2000);
        finishRun(tenantId, runId, "FAILED", statistics,
                error.getClass().getSimpleName(), message);
    }

    @Override
    @Transactional
    public void heartbeatRun(String tenantId, UUID runId) {
        LocalDateTime now = now();
        syncRunMapper.update(null, Wrappers.<MasterDataSyncRunEntity>update()
                .eq("tenant_id", tenantId).eq("id", text(runId)).eq("status", "RUNNING")
                .set("updated_at", now));
        syncLockMapper.update(null, Wrappers.<MasterDataSyncLockEntity>update()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("run_id", text(runId))
                .set("expires_at", now.plus(Duration.ofMinutes(RUN_LEASE_MINUTES))));
    }

    @Override
    public List<ExternalObjectMappingCommand> externalObjectMappings(
            String tenantId, UUID connectorId, UUID runId, MasterDataObjectType objectType) {
        if (objectType != MasterDataObjectType.PRODUCT_SPU) return List.of();
        List<ExternalObjectMappingCommand> commands = new ArrayList<>();
        for (MasterSourceBindingEntity binding : bindings(tenantId, "PRODUCT_SPU")) {
            Long id = longTargetId(binding);
            InternalProductEntity product = id == null ? null : productMapper.selectById(id);
            if (product == null || !Objects.equals(tenantId, product.getTenantId())
                    || value(product.getDeleted(), 0) != 0) continue;
            commands.add(new ExternalObjectMappingCommand(connectorId, INTEGRATION_SOURCE_SYSTEM,
                    "PRODUCT_SPU", binding.sourceObjectId, binding.sourceCode,
                    "ERP", "PRODUCT", product.getId(), product.getProductCode(),
                    "ACTIVE", runId, instant(binding.syncedAt), null,
                    binding.sourcePayloadHash, null, "ERP商品同步映射"));
        }
        for (MasterSourceBindingEntity binding : bindings(tenantId, "PRODUCT_SKU")) {
            Long id = longTargetId(binding);
            InternalProductVariantEntity variant = id == null ? null : variantMapper.selectById(id);
            if (variant == null || !Objects.equals(tenantId, variant.getTenantId())
                    || value(variant.getDeleted(), 0) != 0) continue;
            commands.add(new ExternalObjectMappingCommand(connectorId, INTEGRATION_SOURCE_SYSTEM,
                    "PRODUCT_SKU", binding.sourceObjectId, binding.sourceCode,
                    "ERP", "PRODUCT_VARIANT", variant.getId(), variant.getVariantCode(),
                    "ACTIVE", runId, instant(binding.syncedAt), null,
                    binding.sourcePayloadHash, null, "ERP商品规格同步映射"));
        }
        return List.copyOf(commands);
    }

    private UpsertResult upsertCategory(String tenantId, UUID runId, Category value) {
        MasterSourceBindingEntity binding = binding(tenantId, "CATEGORY", value.sourceId());
        boolean changed = changed(binding, value.payloadHash());
        Long id = longTargetId(binding);
        InternalProductCategoryEntity entity = id == null ? null : categoryMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        Long parentId = internalTargetId(tenantId, "CATEGORY", value.parentSourceId());
        if (created) {
            entity = new InternalProductCategoryEntity();
            entity.setTenantId(tenantId);
            entity.setCategoryCode(uniqueCategoryCode(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || categoryNeedsRepair(entity, value, parentId)))) {
            entity.setParentId(parentId);
            entity.setCategoryName(value.name());
            entity.setCategoryLevel(parentLevel(tenantId, parentId));
            entity.setOrdinal(value.defaultCategory() != null && value.defaultCategory() ? -1 : 0);
            entity.setRemark(sourceRemark(value.sourceFields()));
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) categoryMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                categoryMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "CATEGORY", value.sourceId(), "PRODUCT_CATEGORY",
                entity.getId(), value.externalReferenceId(), value.name(), null, null,
                value.payloadHash(), now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult upsertBrand(String tenantId, UUID runId, Brand value) {
        MasterSourceBindingEntity binding = binding(tenantId, "BRAND", value.sourceId());
        boolean changed = changed(binding, value.payloadHash());
        Long id = longTargetId(binding);
        InternalProductBrandEntity entity = id == null ? null : brandMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        if (created) {
            entity = new InternalProductBrandEntity();
            entity.setTenantId(tenantId);
            entity.setBrandCode(uniqueBrandCode(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || !Objects.equals(entity.getBrandName(), value.name())))) {
            entity.setBrandName(value.name());
            entity.setRemark(firstText(value.description(), sourceRemark(value.sourceFields())));
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) brandMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                brandMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "BRAND", value.sourceId(), "PRODUCT_BRAND",
                entity.getId(), value.externalReferenceId(), value.name(), null, null,
                value.payloadHash(), now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult upsertTag(String tenantId, UUID runId, Tag value) {
        MasterSourceBindingEntity binding = binding(tenantId, "TAG", value.sourceId());
        boolean changed = changed(binding, value.payloadHash());
        Long id = longTargetId(binding);
        InternalProductTagEntity entity = id == null ? null : tagMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        if (created) {
            entity = new InternalProductTagEntity();
            entity.setTenantId(tenantId);
            entity.setTagCode(uniqueTagCode(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || !Objects.equals(entity.getTagName(), value.name())))) {
            entity.setTagName(value.name());
            entity.setTagTypeCode("RECOMMENDED");
            entity.setRemark(firstText(value.groupName(), sourceRemark(value.sourceFields())));
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) tagMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                tagMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "TAG", value.sourceId(), "PRODUCT_TAG",
                entity.getId(), value.code(), value.name(), null, null, value.payloadHash(), now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult upsertSpecification(String tenantId, UUID runId, Specification value) {
        MasterSourceBindingEntity binding = binding(tenantId, "SPECIFICATION", value.sourceId());
        boolean changed = changed(binding, value.payloadHash());
        Long id = longTargetId(binding);
        InternalProductSpecificationEntity entity = id == null ? null : specificationMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        if (created) {
            entity = new InternalProductSpecificationEntity();
            entity.setTenantId(tenantId);
            entity.setSpecificationCode(uniqueSpecificationCode(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || !Objects.equals(entity.getSpecificationName(), value.name())))) {
            entity.setSpecificationName(value.name());
            entity.setStatusCode("ACTIVE");
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) specificationMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                specificationMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "SPECIFICATION", value.sourceId(), "PRODUCT_SPECIFICATION",
                entity.getId(), value.code(), value.name(), null, null, value.payloadHash(), now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult upsertSpecificationValue(String tenantId, UUID runId, Long specificationId,
                                                  SpecificationValue value) {
        MasterSourceBindingEntity binding = binding(tenantId, "SPECIFICATION_VALUE", value.sourceId());
        boolean changed = changed(binding, value.payloadHash());
        Long id = longTargetId(binding);
        InternalProductSpecificationValueEntity entity = id == null ? null
                : specificationValueMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        if (created) {
            entity = new InternalProductSpecificationValueEntity();
            entity.setTenantId(tenantId);
            entity.setSpecificationId(specificationId);
            entity.setValueCode(uniqueSpecificationValueCode(tenantId, specificationId));
            entity.setOrdinal(0);
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || !Objects.equals(entity.getValueName(), value.name())
                || !Objects.equals(entity.getSpecificationId(), specificationId)))) {
            entity.setSpecificationId(specificationId);
            entity.setValueName(value.name());
            entity.setStatusCode("ACTIVE");
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) specificationValueMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                specificationValueMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "SPECIFICATION_VALUE", value.sourceId(),
                "PRODUCT_SPECIFICATION_VALUE", entity.getId(), value.code(), value.name(),
                null, null, value.payloadHash(), now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult upsertProduct(String tenantId, UUID runId, Product value) {
        MasterSourceBindingEntity binding = binding(tenantId, "PRODUCT_SPU", value.sourceId());
        Long id = longTargetId(binding);
        InternalProductEntity entity = id == null ? null : productMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        if (entity == null) {
            entity = productCandidate(tenantId, value);
        }
        boolean created = entity == null;
        boolean changed = changed(binding, value.payloadHash())
                || (binding != null && entity != null && !Objects.equals(id, entity.getId()));
        LocalDateTime now = now();
        Long categoryId = internalTargetId(tenantId, "CATEGORY", value.categorySourceId());
        Long brandId = internalTargetId(tenantId, "BRAND", value.brandSourceId());
        String imagesJson = imageKeysJson(value);
        String productCode = synchronizedProductCode(tenantId, value, entity);
        if (created) {
            entity = new InternalProductEntity();
            entity.setTenantId(tenantId);
            entity.setProductCode(productCode);
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || productNeedsRepair(entity, value, categoryId, brandId, imagesJson, productCode)))) {
            entity.setProductCode(productCode);
            entity.setProductName(value.name());
            entity.setCategoryId(categoryId);
            entity.setBrandId(brandId);
            entity.setProductSpecification(value.model());
            entity.setUnitCode(internalUnitCode(value.unit()));
            entity.setMinOrderQuantity(value.minimumOrder());
            entity.setOrderMultipleFlag(false);
            entity.setOrderMultipleQuantity(null);
            entity.setSaleTypeCode("SPOT");
            entity.setShelfStatusCode(internalShelfStatus(value.putaway()));
            entity.setTagCodesJson(json(List.of()));
            entity.setLimitQuantity(null);
            entity.setImageKeysJson(imagesJson);
            entity.setRecommendProductIdsJson(json(List.of()));
            entity.setSubmitStatusCode("SUBMITTED");
            entity.setRemark(firstText(value.subtitle(), sourceRemark(value.sourceFields())));
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) productMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                productMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "PRODUCT_SPU", value.sourceId(), "PRODUCT",
                entity.getId(), value.code(), value.name(), null, value.putaway(),
                value.payloadHash(), now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult upsertVariant(String tenantId, UUID runId, Long productId,
                                       Product product, Sku value) {
        MasterSourceBindingEntity binding = binding(tenantId, "PRODUCT_SKU", value.sourceId());
        Long id = longTargetId(binding);
        InternalProductVariantEntity entity = id == null ? null : variantMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        if (entity == null) {
            entity = variantCandidate(tenantId, productId, value);
        }
        boolean created = entity == null;
        boolean changed = changed(binding, value.payloadHash())
                || (binding != null && entity != null && !Objects.equals(id, entity.getId()));
        LocalDateTime now = now();
        String variantCode = synchronizedVariantCode(tenantId, product, value, entity);
        if (created) {
            entity = new InternalProductVariantEntity();
            entity.setTenantId(tenantId);
            entity.setVariantCode(variantCode);
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
            entity.setDefaultFlag(variantMapper.selectCount(Wrappers.<InternalProductVariantEntity>lambdaQuery()
                    .eq(InternalProductVariantEntity::getTenantId, tenantId)
                    .eq(InternalProductVariantEntity::getProductId, productId)
                    .eq(InternalProductVariantEntity::getDeleted, 0)) == 0);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || variantNeedsRepair(entity, productId, product, value, variantCode)))) {
            entity.setProductId(productId);
            entity.setVariantCode(variantCode);
            entity.setSpecificationSnapshot(value.specificationName());
            entity.setUnitCode(internalUnitCode(product.unit()));
            entity.setSalePrice(firstAmount(value.orderPrice(), product.orderPrice()));
            entity.setMarketPrice(firstAmount(value.marketPrice(), product.marketPrice()));
            entity.setPurchasePrice(firstAmount(value.purchasePrice(), product.purchasePrice()));
            entity.setMinOrderQuantity(product.minimumOrder());
            entity.setOrderMultipleQuantity(null);
            entity.setLimitQuantity(null);
            entity.setRemark(null);
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) variantMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                variantMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "PRODUCT_SKU", value.sourceId(), "PRODUCT_VARIANT",
                entity.getId(), value.code(), value.specificationName(), null, product.putaway(),
                value.payloadHash(), now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                          MasterDataObjectType objectType, int maxPages, String triggerType) {
        LocalDateTime now = now();
        UUID runId = UUID.randomUUID();
        acquireLock(tenantId, objectType, runId, now);
        MasterDataSyncRunEntity run = new MasterDataSyncRunEntity();
        run.id = text(runId);
        run.tenantId = tenantId;
        run.connectorId = text(connectorId);
        run.sourceSystem = SOURCE_SYSTEM;
        run.objectType = objectType.name();
        run.triggerType = triggerType;
        run.status = "RUNNING";
        run.maxPages = maxPages;
        run.pageSize = 200;
        run.createdBy = actorId == null ? SYNC_ACTOR : text(actorId);
        run.startedAt = now;
        run.createdAt = now;
        run.updatedAt = now;
        syncRunMapper.insert(run);
        return runId;
    }

    private void acquireLock(String tenantId, MasterDataObjectType objectType, UUID runId,
                             LocalDateTime now) {
        MasterDataSyncLockEntity lock = new MasterDataSyncLockEntity();
        lock.id = stableLockId(tenantId, objectType.name());
        lock.tenantId = tenantId;
        lock.sourceSystem = SOURCE_SYSTEM;
        lock.objectType = objectType.name();
        lock.runId = text(runId);
        lock.acquiredAt = now;
        lock.expiresAt = now.plus(Duration.ofMinutes(RUN_LEASE_MINUTES));
        int updated = syncLockMapper.update(null, Wrappers.<MasterDataSyncLockEntity>update()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("object_type", objectType.name())
                .le("expires_at", now)
                .set("run_id", text(runId))
                .set("acquired_at", now)
                .set("expires_at", lock.expiresAt));
        if (updated == 0) {
            try {
                syncLockMapper.insert(lock);
            } catch (RuntimeException error) {
                throw new IllegalStateException("ERP商品同步正在运行: objectType=" + objectType.name(), error);
            }
        }
    }

    private void requireRunningRunForUpdate(String tenantId, UUID runId) {
        MasterDataSyncRunEntity run = syncRunMapper.selectOne(
                Wrappers.<MasterDataSyncRunEntity>query()
                        .eq("tenant_id", tenantId).eq("id", text(runId))
                        .eq("status", "RUNNING").last("FOR UPDATE"));
        if (run == null) throw new IllegalStateException("ERP同步run不存在或已终止");
    }

    private void finishRun(String tenantId, UUID runId, String status,
                           RunStatistics statistics, String errorCode, String errorMessage) {
        LocalDateTime now = now();
        syncRunMapper.update(null, Wrappers.<MasterDataSyncRunEntity>update()
                .eq("tenant_id", tenantId).eq("id", text(runId)).eq("status", "RUNNING")
                .set("status", status)
                .set("fetched_count", statistics.fetched())
                .set("created_count", statistics.created())
                .set("changed_count", statistics.changed())
                .set("duplicate_count", statistics.duplicates())
                .set("rejected_count", statistics.rejected())
                .set("unmapped_count", statistics.dictionaryAudit().unmapped())
                .set("dict_snapshot_json", json(statistics.dictionaryAudit().revisions()))
                .set("mapping_issues_json", json(statistics.dictionaryAudit().issues()))
                .set("error_code", errorCode)
                .set("error_message", errorMessage)
                .set("finished_at", now)
                .set("updated_at", now));
        syncLockMapper.delete(Wrappers.<MasterDataSyncLockEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("run_id", text(runId)));
    }

    private MasterSourceBindingEntity binding(String tenantId, String sourceType, String sourceId) {
        if (missing(sourceId)) return null;
        return bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType)
                .eq("source_object_id", sourceId)
                .last("LIMIT 1"));
    }

    private List<MasterSourceBindingEntity> bindings(String tenantId, String sourceType) {
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType)
                .eq("source_presence", PRESENT));
    }

    private void upsertBinding(String tenantId, UUID runId, String sourceType, String sourceId,
                               String targetType, Long targetId, String sourceCode,
                               String sourceName, String sourceStatus, String sourcePutaway,
                               String payloadHash, LocalDateTime now) {
        MasterSourceBindingEntity binding = binding(tenantId, sourceType, sourceId);
        if (binding == null) {
            binding = new MasterSourceBindingEntity();
            binding.id = UUID.randomUUID().toString();
            binding.tenantId = tenantId;
            binding.sourceSystem = SOURCE_SYSTEM;
            binding.sourceObjectType = sourceType;
            binding.sourceObjectId = sourceId;
            binding.createdAt = now;
            binding.version = 0L;
        } else {
            binding.version = nextVersion(binding.version);
        }
        binding.targetType = targetType;
        binding.targetId = targetId == null ? null : targetId.toString();
        binding.sourceCode = blank(sourceCode);
        binding.sourceName = blank(sourceName);
        binding.sourceStatus = blank(sourceStatus);
        binding.sourcePutaway = blank(sourcePutaway);
        binding.sourcePayloadHash = requiredHash(payloadHash);
        binding.sourcePresence = PRESENT;
        binding.sourceAbsentAt = null;
        binding.lastSyncRunId = text(runId);
        binding.syncedAt = now;
        binding.updatedAt = now;
        if (binding.createdAt == now) bindingMapper.insert(binding);
        else bindingMapper.updateById(binding);
    }

    private Long internalTargetId(String tenantId, String sourceType, String sourceId) {
        MasterSourceBindingEntity binding = binding(tenantId, sourceType, sourceId);
        return longTargetId(binding);
    }

    private InternalProductEntity productCandidate(String tenantId, Product value) {
        InternalProductEntity bySourceCode = productFromBindingSourceCode(tenantId, value.code());
        if (bySourceCode != null) return bySourceCode;
        if (!missing(value.code())) {
            InternalProductEntity byLegacyCode = uniqueProduct(tenantId,
                    Wrappers.<InternalProductEntity>lambdaQuery()
                            .eq(InternalProductEntity::getTenantId, tenantId)
                            .eq(InternalProductEntity::getProductCode, value.code().strip())
                            .eq(InternalProductEntity::getDeleted, 0));
            if (byLegacyCode != null && sourceWritable(byLegacyCode.getUpdatedBy())) {
                return byLegacyCode;
            }
        }
        if (missing(value.code()) && !missing(value.name())) {
            InternalProductEntity byName = uniqueProduct(tenantId,
                    Wrappers.<InternalProductEntity>lambdaQuery()
                            .eq(InternalProductEntity::getTenantId, tenantId)
                            .eq(InternalProductEntity::getProductName, value.name().strip())
                            .eq(InternalProductEntity::getDeleted, 0));
            if (byName != null && sourceWritable(byName.getUpdatedBy())) {
                return byName;
            }
        }
        return null;
    }

    private InternalProductVariantEntity variantCandidate(String tenantId, Long productId, Sku value) {
        if (productId == null) return null;
        InternalProductVariantEntity bySourceCode = variantFromBindingSourceCode(tenantId, productId, value.code());
        if (bySourceCode != null) return bySourceCode;
        if (!missing(value.code())) {
            InternalProductVariantEntity byLegacyCode = uniqueVariant(tenantId,
                    Wrappers.<InternalProductVariantEntity>lambdaQuery()
                            .eq(InternalProductVariantEntity::getTenantId, tenantId)
                            .eq(InternalProductVariantEntity::getProductId, productId)
                            .eq(InternalProductVariantEntity::getVariantCode, value.code().strip())
                            .eq(InternalProductVariantEntity::getDeleted, 0));
            if (byLegacyCode != null && sourceWritable(byLegacyCode.getUpdatedBy())) {
                return byLegacyCode;
            }
        }
        if (!missing(value.specificationName())) {
            InternalProductVariantEntity bySpecification = uniqueVariant(tenantId,
                    Wrappers.<InternalProductVariantEntity>lambdaQuery()
                            .eq(InternalProductVariantEntity::getTenantId, tenantId)
                            .eq(InternalProductVariantEntity::getProductId, productId)
                            .eq(InternalProductVariantEntity::getSpecificationSnapshot,
                                    value.specificationName().strip())
                            .eq(InternalProductVariantEntity::getDeleted, 0));
            if (bySpecification != null && sourceWritable(bySpecification.getUpdatedBy())) {
                return bySpecification;
            }
        }
        return null;
    }

    private InternalProductEntity productFromBindingSourceCode(String tenantId, String sourceCode) {
        if (missing(sourceCode)) return null;
        java.util.LinkedHashSet<Long> targetIds = new java.util.LinkedHashSet<>();
        for (MasterSourceBindingEntity item : bindingsBySourceCode(tenantId, "PRODUCT_SPU", sourceCode)) {
            Long targetId = longTargetId(item);
            if (targetId == null) continue;
            InternalProductEntity entity = productMapper.selectById(targetId);
            if (valid(entity, tenantId)) targetIds.add(targetId);
        }
        return targetIds.size() == 1 ? productMapper.selectById(targetIds.iterator().next()) : null;
    }

    private InternalProductVariantEntity variantFromBindingSourceCode(String tenantId, Long productId,
                                                                     String sourceCode) {
        if (missing(sourceCode)) return null;
        java.util.LinkedHashSet<Long> targetIds = new java.util.LinkedHashSet<>();
        for (MasterSourceBindingEntity item : bindingsBySourceCode(tenantId, "PRODUCT_SKU", sourceCode)) {
            Long targetId = longTargetId(item);
            if (targetId == null) continue;
            InternalProductVariantEntity entity = variantMapper.selectById(targetId);
            if (valid(entity, tenantId) && Objects.equals(entity.getProductId(), productId)) {
                targetIds.add(targetId);
            }
        }
        return targetIds.size() == 1 ? variantMapper.selectById(targetIds.iterator().next()) : null;
    }

    private List<MasterSourceBindingEntity> bindingsBySourceCode(String tenantId, String sourceType,
                                                                 String sourceCode) {
        if (missing(sourceCode)) return List.of();
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType)
                .eq("source_code", sourceCode.strip())
                .eq("source_presence", PRESENT)
                .last("LIMIT 2"));
    }

    private InternalProductEntity uniqueProduct(String tenantId,
                                                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                                        InternalProductEntity> query) {
        List<InternalProductEntity> values = productMapper.selectList(query.last("LIMIT 2"));
        values = values.stream().filter(item -> valid(item, tenantId)).toList();
        return values.size() == 1 ? values.getFirst() : null;
    }

    private InternalProductVariantEntity uniqueVariant(String tenantId,
                                                       com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                                               InternalProductVariantEntity> query) {
        List<InternalProductVariantEntity> values = variantMapper.selectList(query.last("LIMIT 2"));
        values = values.stream().filter(item -> valid(item, tenantId)).toList();
        return values.size() == 1 ? values.getFirst() : null;
    }

    private int parentLevel(String tenantId, Long parentId) {
        if (parentId == null) return 1;
        InternalProductCategoryEntity parent = categoryMapper.selectById(parentId);
        if (parent == null || !Objects.equals(parent.getTenantId(), tenantId)) return 1;
        return value(parent.getCategoryLevel(), 1) + 1;
    }

    private String uniqueProductCode(String tenantId) {
        return uniqueProductCode(tenantId, null);
    }

    private String uniqueProductCode(String tenantId, Instant businessTime) {
        return uniqueCode(ErpBusinessCodeRules.PRODUCT, code -> productMapper.selectCount(
                Wrappers.<InternalProductEntity>lambdaQuery()
                        .eq(InternalProductEntity::getTenantId, tenantId)
                        .eq(InternalProductEntity::getProductCode, code)), businessTime);
    }

    private String uniqueVariantCode(String tenantId) {
        return uniqueVariantCode(tenantId, null);
    }

    private String uniqueVariantCode(String tenantId, Instant businessTime) {
        return uniqueCode(ErpBusinessCodeRules.SKU, code -> variantMapper.selectCount(
                Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .eq(InternalProductVariantEntity::getVariantCode, code)), businessTime);
    }

    private String synchronizedProductCode(String tenantId, Product value, InternalProductEntity entity) {
        if (entity == null || sourceCodeEquals(entity.getProductCode(), value.code())) {
            return uniqueProductCode(tenantId, sourceCreatedAt(value.sourceFields()));
        }
        return entity.getProductCode();
    }

    private String synchronizedVariantCode(String tenantId, Product product, Sku value,
                                           InternalProductVariantEntity entity) {
        if (entity == null || sourceCodeEquals(entity.getVariantCode(), value.code())) {
            Instant businessTime = sourceCreatedAt(value.sourceFields());
            if (businessTime == null) {
                businessTime = sourceCreatedAt(product.sourceFields());
            }
            return uniqueVariantCode(tenantId, businessTime);
        }
        return entity.getVariantCode();
    }

    private String uniqueCategoryCode(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.CATEGORY, code -> categoryMapper.selectCount(
                Wrappers.<InternalProductCategoryEntity>lambdaQuery()
                        .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                        .eq(InternalProductCategoryEntity::getCategoryCode, code)));
    }

    private String uniqueBrandCode(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.BRAND, code -> brandMapper.selectCount(
                Wrappers.<InternalProductBrandEntity>lambdaQuery()
                        .eq(InternalProductBrandEntity::getTenantId, tenantId)
                        .eq(InternalProductBrandEntity::getBrandCode, code)));
    }

    private String uniqueTagCode(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.TAG, code -> tagMapper.selectCount(
                Wrappers.<InternalProductTagEntity>lambdaQuery()
                        .eq(InternalProductTagEntity::getTenantId, tenantId)
                        .eq(InternalProductTagEntity::getTagCode, code)));
    }

    private String uniqueSpecificationCode(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.SPECIFICATION, code -> specificationMapper.selectCount(
                Wrappers.<InternalProductSpecificationEntity>lambdaQuery()
                        .eq(InternalProductSpecificationEntity::getTenantId, tenantId)
                        .eq(InternalProductSpecificationEntity::getSpecificationCode, code)));
    }

    private String uniqueSpecificationValueCode(String tenantId, Long specificationId) {
        return uniqueCode(ErpBusinessCodeRules.SPECIFICATION_VALUE, code ->
                specificationValueMapper.selectCount(Wrappers.<InternalProductSpecificationValueEntity>lambdaQuery()
                        .eq(InternalProductSpecificationValueEntity::getTenantId, tenantId)
                        .eq(InternalProductSpecificationValueEntity::getSpecificationId, specificationId)
                        .eq(InternalProductSpecificationValueEntity::getValueCode, code)));
    }

    private String uniqueCode(BusinessCodeRule rule, Function<String, Long> count) {
        return codeGenerator.generateUnique(rule, code -> count.apply(code) == 0);
    }

    private String uniqueCode(BusinessCodeRule rule, Function<String, Long> count, Instant businessTime) {
        return codeGenerator.generateUnique(rule, businessTime, code -> count.apply(code) == 0);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private static boolean changed(MasterSourceBindingEntity binding, String payloadHash) {
        return binding == null || !Objects.equals(binding.sourcePayloadHash, requiredHash(payloadHash))
                || SOURCE_ABSENT.equals(binding.sourcePresence) || longTargetId(binding) == null;
    }

    private static ImportResult importResult(MasterSourceBindingEntity binding,
                                             boolean created, boolean changed) {
        if (created) return ImportResult.created(1);
        return binding == null || changed ? ImportResult.changed(1) : ImportResult.duplicate(1);
    }

    private static boolean productNeedsRepair(InternalProductEntity entity, Product value,
                                              Long categoryId, Long brandId, String imagesJson,
                                              String productCode) {
        return !Objects.equals(entity.getProductCode(), productCode)
                || !Objects.equals(entity.getProductName(), value.name())
                || !Objects.equals(entity.getCategoryId(), categoryId)
                || !Objects.equals(entity.getBrandId(), brandId)
                || !Objects.equals(entity.getShelfStatusCode(), internalShelfStatus(value.putaway()))
                || !Objects.equals(entity.getImageKeysJson(), imagesJson);
    }

    private static boolean variantNeedsRepair(InternalProductVariantEntity entity, Long productId,
                                              Product product, Sku value, String variantCode) {
        return !Objects.equals(entity.getVariantCode(), variantCode)
                || !Objects.equals(entity.getProductId(), productId)
                || !Objects.equals(entity.getSpecificationSnapshot(), value.specificationName())
                || !Objects.equals(entity.getUnitCode(), internalUnitCode(product.unit()))
                || !Objects.equals(entity.getSalePrice(), firstAmount(value.orderPrice(), product.orderPrice()));
    }

    private static boolean categoryNeedsRepair(InternalProductCategoryEntity entity, Category value,
                                               Long parentId) {
        return !Objects.equals(entity.getCategoryName(), value.name())
                || !Objects.equals(entity.getParentId(), parentId);
    }

    private static boolean sourceWritable(String updatedBy) {
        return missing(updatedBy) || SYNC_ACTOR.equals(updatedBy);
    }

    private static boolean valid(InternalProductCategoryEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductBrandEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductSpecificationEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductSpecificationValueEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductTagEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductVariantEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static Long longTargetId(MasterSourceBindingEntity binding) {
        if (binding == null || missing(binding.targetId)) return null;
        try {
            return Long.valueOf(binding.targetId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long nextVersion(Long value) {
        return value == null ? 1L : value + 1;
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static boolean sourceCodeEquals(String internalCode, String sourceCode) {
        return !missing(internalCode) && !missing(sourceCode)
                && Objects.equals(internalCode.strip(), sourceCode.strip());
    }

    private static Instant sourceCreatedAt(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return null;
        return firstInstant(fields, "create_date", "created_at", "createdAt", "created_time");
    }

    private static Instant firstInstant(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            Instant value = instant(fields.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        String text = String.valueOf(value);
        if (missing(text)) return null;
        text = text.strip();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // 订货宝商品接口常见格式为 yyyy-MM-dd HH:mm:ss，按业务时区解释。
        }
        try {
            return LocalDateTime.parse(text, SOURCE_DATE_TIME).atZone(BUSINESS_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String blank(String value) {
        return missing(value) ? null : value.strip();
    }

    private static String firstText(String first, String second) {
        return !missing(first) ? first.strip() : blank(second);
    }

    private static java.math.BigDecimal firstAmount(java.math.BigDecimal first, java.math.BigDecimal second) {
        return first == null ? second : first;
    }

    private static String sourceRemark(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return null;
        Object remark = fields.get("remark");
        return remark == null ? null : blank(String.valueOf(remark));
    }

    private static String internalShelfStatus(String value) {
        return "T".equalsIgnoreCase(blank(value)) ? "ON_SHELF" : "OFF_SHELF";
    }

    private static String internalUnitCode(String value) {
        if (missing(value)) return "PIECE";
        return switch (value.strip()) {
            case "箱" -> "BOX";
            case "桶" -> "BUCKET";
            case "份" -> "PORTION";
            case "套" -> "SET";
            case "床" -> "BED";
            default -> "PIECE";
        };
    }

    private static String imageKeysJson(Product product) {
        List<ProductImageJson> images = new ArrayList<>();
        String mainImageKey = missing(product.mainImageKey()) ? null : product.mainImageKey().strip();
        if (mainImageKey != null) {
            images.add(new ProductImageJson(mainImageKey, "MAIN", 0));
        }
        List<ProductImage> sourceImages = product.images().stream()
                .filter(image -> image != null && !missing(image.objectKey()))
                .sorted(Comparator.comparing(ProductImage::sortOrder, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        for (ProductImage image : sourceImages) {
            String key = image.objectKey().strip();
            if (containsImageKey(images, key)) continue;
            boolean main = mainImageKey == null && images.isEmpty();
            images.add(new ProductImageJson(key, main ? "MAIN" : "DETAIL", images.size()));
        }
        return json(images);
    }

    private static boolean containsImageKey(List<ProductImageJson> images, String imageKey) {
        return images.stream().anyMatch(image -> Objects.equals(image.imageKey(), imageKey));
    }

    private static String requiredHash(String payloadHash) {
        return missing(payloadHash) ? sha256Hex("EMPTY") : payloadHash.strip();
    }

    private static String stableLockId(String tenantId, String objectType) {
        return UUID.nameUUIDFromBytes((tenantId + "|" + SOURCE_SYSTEM + "|" + objectType)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前JDK不支持SHA-256", error);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception error) {
            throw new IllegalStateException("ERP商品同步JSON序列化失败", error);
        }
    }

    private record ProductImageJson(String imageKey, String imageTypeCode, Integer ordinal) {
    }

    private record UpsertResult(Long id, ImportResult result) { }
}
