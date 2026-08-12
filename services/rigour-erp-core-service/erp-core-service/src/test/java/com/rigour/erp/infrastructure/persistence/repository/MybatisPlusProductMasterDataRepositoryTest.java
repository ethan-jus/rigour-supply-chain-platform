package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rigour.erp.application.port.out.ProductMasterDataStore.ImportResult;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.Sku;
import com.rigour.erp.domain.model.product.Specification;
import com.rigour.erp.domain.model.product.SpecificationValue;
import com.rigour.erp.infrastructure.persistence.entity.MasterSourceBindingEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductImageEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductPriceEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductSkuEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductSpuEntity;
import com.rigour.erp.infrastructure.persistence.entity.ProductUnitEntity;
import com.rigour.erp.infrastructure.persistence.entity.SpecificationEntity;
import com.rigour.erp.infrastructure.persistence.entity.SpecificationValueEntity;
import com.rigour.erp.infrastructure.persistence.mapper.CategoryMapper;
import com.rigour.erp.infrastructure.persistence.mapper.BrandMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncLockMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductCustomFieldMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductImageMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductInventoryPolicyMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductPriceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSkuMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSkuSpecificationValueMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSpuMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSpuCategoryMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductSpuSpecificationMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductTagMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ProductUnitMapper;
import com.rigour.erp.infrastructure.persistence.mapper.SpecificationMapper;
import com.rigour.erp.infrastructure.persistence.mapper.SpecificationValueMapper;
import com.rigour.erp.infrastructure.persistence.mapper.TagGroupMapper;
import com.rigour.erp.application.port.out.ProductMediaUrlResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 商品主数据查询仓储的空关联回归测试。 */
@ExtendWith(MockitoExtension.class)
class MybatisPlusProductMasterDataRepositoryTest {
    @Mock private CategoryMapper categoryMapper;
    @Mock private BrandMapper brandMapper;
    @Mock private ProductTagMapper productTagMapper;
    @Mock private TagGroupMapper tagGroupMapper;
    @Mock private ProductSpuCategoryMapper productSpuCategoryMapper;
    @Mock private ProductSpuSpecificationMapper productSpuSpecificationMapper;
    @Mock private ProductSkuMapper productSkuMapper;
    @Mock private ProductSpuMapper productSpuMapper;
    @Mock private ProductImageMapper productImageMapper;
    @Mock private ProductPriceMapper productPriceMapper;
    @Mock private ProductUnitMapper productUnitMapper;
    @Mock private ProductInventoryPolicyMapper productInventoryPolicyMapper;
    @Mock private ProductCustomFieldMapper productCustomFieldMapper;
    @Mock private ProductSkuSpecificationValueMapper productSkuSpecificationValueMapper;
    @Mock private SpecificationMapper specificationMapper;
    @Mock private SpecificationValueMapper specificationValueMapper;
    @Mock private MasterSourceBindingMapper bindingMapper;
    @Mock private MasterDataSyncRunMapper syncRunMapper;
    @Mock private MasterDataSyncLockMapper syncLockMapper;
    @Mock private ProductMediaUrlResolver productMediaUrlResolver;
    @Mock private Clock clock;
    @InjectMocks private MybatisPlusProductMasterDataRepository repository;

    @Test
    void skipsCategoryQueryWhenProductsHaveNoPrimaryCategoryRelation() {
        ProductSpuEntity product = new ProductSpuEntity();
        product.id = "spu-without-category";

        Map<String, ?> result = ReflectionTestUtils.invokeMethod(
                repository, "primaryCategories", "tenant-id", List.of(product));

        assertThat(result).isEmpty();
        verify(productSpuCategoryMapper).selectList(any());
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void skipsSkuAndAuxiliaryUpdatesWhenSourcePayloadHasNotChanged() {
        String hash = "a".repeat(64);
        MasterSourceBindingEntity binding = binding(hash);
        ProductSkuEntity entity = skuEntity();
        stubExistingSku(binding, entity);
        stubCompleteSkuAuxiliary();

        ImportResult result = importSku(hash, "红色,L");

        assertThat(result).isEqualTo(ImportResult.duplicate(1));
        verify(productSkuMapper, never()).updateById(any(ProductSkuEntity.class));
        verify(productPriceMapper, never()).delete(any());
        verify(productPriceMapper, never()).insert(any(ProductPriceEntity.class));
        verify(productUnitMapper, never()).delete(any());
        verify(productUnitMapper, never()).insert(any(ProductUnitEntity.class));
        verify(bindingMapper, never()).updateById(any(MasterSourceBindingEntity.class));
    }

    @Test
    void skipsProductAndItsAuxiliaryWritesWhenSourcePayloadHasNotChanged() {
        String hash = "a".repeat(64);
        MasterSourceBindingEntity binding = new MasterSourceBindingEntity();
        binding.id = "product-binding-id";
        binding.tenantId = "tenant-id";
        binding.sourceSystem = "DINGHUOBAO";
        binding.sourceObjectType = "PRODUCT_SPU";
        binding.sourceObjectId = "product-source";
        binding.targetType = "SPU";
        binding.targetId = "spu-id";
        binding.sourcePayloadHash = hash;
        binding.version = 1L;
        ProductSpuEntity entity = new ProductSpuEntity();
        entity.id = "spu-id";
        entity.tenantId = "tenant-id";
        entity.ownershipState = "EXTERNAL_PRIMARY";

        when(bindingMapper.selectOne(any())).thenReturn(binding);
        when(productSpuMapper.selectById("spu-id")).thenReturn(entity);
        stubCompleteProductAuxiliary();

        Product product = new Product("product-source", "SPU-1", "商品一", "T", null,
                "件", null, null, List.of(), hash);

        ImportResult result = repository.importProduct("tenant-id",
                UUID.fromString("019fb100-0000-7000-8000-000000000005"), product);

        assertThat(result).isEqualTo(ImportResult.duplicate(1));
        verify(productSpuMapper, never()).updateById(any(ProductSpuEntity.class));
        verify(bindingMapper, never()).updateById(any(MasterSourceBindingEntity.class));
        verifyNoInteractions(productSpuCategoryMapper, productSkuMapper);
        verify(productImageMapper, never()).delete(any());
        verify(productImageMapper, never()).insert(any(ProductImageEntity.class));
        verify(productPriceMapper, never()).delete(any());
        verify(productPriceMapper, never()).insert(any(ProductPriceEntity.class));
        verify(productUnitMapper, never()).delete(any());
        verify(productUnitMapper, never()).insert(any(ProductUnitEntity.class));
    }

    @Test
    void repairsMissingProductAuxiliaryRowsWhenSourcePayloadHasNotChanged() {
        String hash = "a".repeat(64);
        MasterSourceBindingEntity binding = new MasterSourceBindingEntity();
        binding.id = "product-binding-id";
        binding.tenantId = "tenant-id";
        binding.sourceSystem = "DINGHUOBAO";
        binding.sourceObjectType = "PRODUCT_SPU";
        binding.sourceObjectId = "product-source";
        binding.targetType = "SPU";
        binding.targetId = "spu-id";
        binding.sourcePayloadHash = hash;
        binding.version = 1L;
        ProductSpuEntity entity = new ProductSpuEntity();
        entity.id = "spu-id";
        entity.tenantId = "tenant-id";
        entity.ownershipState = "EXTERNAL_PRIMARY";

        when(bindingMapper.selectOne(any())).thenReturn(binding);
        when(productSpuMapper.selectById("spu-id")).thenReturn(entity);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-11T02:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(productImageMapper.selectCount(any())).thenReturn(0L);
        when(productPriceMapper.selectCount(any())).thenReturn(0L);
        when(productUnitMapper.selectCount(any())).thenReturn(0L);

        Product product = new Product("product-source", "SPU-1", "商品一", "T", null,
                "件", null, null, List.of(), hash);

        ImportResult result = repository.importProduct("tenant-id",
                UUID.fromString("019fb100-0000-7000-8000-000000000005"), product);

        assertThat(result).isEqualTo(ImportResult.changed(1));
        verify(productSpuMapper, never()).updateById(any(ProductSpuEntity.class));
        verify(productUnitMapper).delete(any());
        verify(productUnitMapper).insert(any(ProductUnitEntity.class));
        verify(bindingMapper, never()).updateById(any(MasterSourceBindingEntity.class));
    }

    @Test
    void repairsMissingSkuAuxiliaryRowsWhenSourcePayloadHasNotChanged() {
        String hash = "a".repeat(64);
        MasterSourceBindingEntity binding = binding(hash);
        ProductSkuEntity entity = skuEntity();
        stubExistingSku(binding, entity);
        when(productPriceMapper.selectCount(any())).thenReturn(0L);
        when(productUnitMapper.selectCount(any())).thenReturn(0L);

        ImportResult result = importSku(hash, "红色,L");

        assertThat(result).isEqualTo(ImportResult.changed(1));
        verify(productSkuMapper).updateById(entity);
        verify(productPriceMapper).delete(any());
        verify(productUnitMapper).delete(any());
        verify(productUnitMapper).insert(any(ProductUnitEntity.class));
        verify(bindingMapper, never()).updateById(any(MasterSourceBindingEntity.class));
    }

    @Test
    void updatesExistingSkuWithPlainTextSpecificationSummaryWhenPayloadChanges() {
        MasterSourceBindingEntity binding = binding("a".repeat(64));
        ProductSkuEntity entity = skuEntity();
        stubExistingSku(binding, entity);

        ImportResult result = importSku("b".repeat(64), "红色,L");

        assertThat(result).isEqualTo(ImportResult.changed(1));
        assertThat(entity.specificationSummary).isEqualTo("红色,L");
        assertThat(entity.firstSpecificationValueSourceId).isEqualTo("spec-value-1");
        assertThat(entity.secondSpecificationValueSourceId).isEqualTo("spec-value-2");
        assertThat(entity.internalStatus).isEqualTo("ACTIVE");
        assertThat(entity.version).isEqualTo(2L);
        verify(productSkuMapper).updateById(entity);
        verify(productPriceMapper).delete(any());
        verify(productUnitMapper).delete(any());
    }

    @Test
    void upgradesLegacySkuBindingToProductQualifiedSourceIdWithoutRecreatingSku() {
        String hash = "a".repeat(64);
        MasterSourceBindingEntity binding = binding(hash);
        ProductSkuEntity entity = skuEntity();
        when(clock.instant()).thenReturn(Instant.parse("2026-08-11T02:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(bindingMapper.selectOne(any())).thenReturn(null, binding, binding);
        when(productSkuMapper.selectById(entity.id)).thenReturn(entity);
        stubCompleteSkuAuxiliary();

        ImportResult result = importSku(hash, "红色,L", "product-source::sku-source");

        assertThat(result).isEqualTo(ImportResult.duplicate(1));
        assertThat(binding.sourceObjectId).isEqualTo("product-source::sku-source");
        verify(bindingMapper).updateById(binding);
        verify(productSkuMapper, never()).insert(any(ProductSkuEntity.class));
    }

    @Test
    void setsUpdatedAtWhenImportingNewSpecificationValue() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 11, 2, 0);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-11T02:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        SpecificationValue specificationValue = new SpecificationValue("value-source", "RED", "红色",
                "spec-source", "v".repeat(64));

        ImportResult result = ReflectionTestUtils.invokeMethod(repository, "importSpecificationValue",
                "tenant-id", UUID.fromString("019fb100-0000-7000-8000-000000000005"),
                "specification-id", specificationValue);

        assertThat(result.created() + result.changed() + result.duplicates() + result.rejected())
                .isPositive();
        var value = org.mockito.ArgumentCaptor.forClass(SpecificationValueEntity.class);
        verify(specificationValueMapper).insert(value.capture());
        assertThat(value.getValue().createdAt).isEqualTo(now);
        assertThat(value.getValue().updatedAt).isEqualTo(now);
    }

    @Test
    void setsUpdatedAtWhenImportingNewSpecification() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 11, 2, 0);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-11T02:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        Specification specification = new Specification("spec-source", "COLOR", "颜色", List.of(),
                "s".repeat(64));

        ReflectionTestUtils.invokeMethod(repository, "upsertSpecification", "tenant-id",
                UUID.fromString("019fb100-0000-7000-8000-000000000005"), specification);

        var value = org.mockito.ArgumentCaptor.forClass(SpecificationEntity.class);
        verify(specificationMapper).insert(value.capture());
        assertThat(value.getValue().createdAt).isEqualTo(now);
        assertThat(value.getValue().updatedAt).isEqualTo(now);
    }

    private ImportResult importSku(String hash, String specificationName) {
        return importSku(hash, specificationName, "sku-source");
    }

    private ImportResult importSku(String hash, String specificationName, String sourceId) {
        Product product = new Product("product-source", "SPU-1", "商品一", "T", null, "件",
                null, null, List.of(), "p".repeat(64));
        Sku sku = new Sku(sourceId, "SKU-1", "6900000000001",
                "spec-value-1", "spec-value-2", specificationName, hash);
        return ReflectionTestUtils.invokeMethod(repository, "importSku", "tenant-id",
                UUID.fromString("019fb100-0000-7000-8000-000000000005"), "spu-id", product, sku);
    }

    private void stubExistingSku(MasterSourceBindingEntity binding, ProductSkuEntity entity) {
        when(clock.instant()).thenReturn(Instant.parse("2026-08-11T02:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(bindingMapper.selectOne(any())).thenReturn(binding);
        when(productSkuMapper.selectById(entity.id)).thenReturn(entity);
    }

    private void stubCompleteSkuAuxiliary() {
        when(productPriceMapper.selectCount(any())).thenReturn(0L);
        when(productUnitMapper.selectCount(any())).thenReturn(1L);
    }

    private void stubCompleteProductAuxiliary() {
        when(productImageMapper.selectCount(any())).thenReturn(0L);
        when(productPriceMapper.selectCount(any())).thenReturn(0L);
        when(productUnitMapper.selectCount(any())).thenReturn(1L);
        when(productInventoryPolicyMapper.selectCount(any())).thenReturn(0L);
        when(productCustomFieldMapper.selectCount(any())).thenReturn(0L);
    }

    private static MasterSourceBindingEntity binding(String hash) {
        MasterSourceBindingEntity binding = new MasterSourceBindingEntity();
        binding.id = "binding-id";
        binding.tenantId = "tenant-id";
        binding.sourceObjectType = "PRODUCT_SKU";
        binding.sourceObjectId = "sku-source";
        binding.targetType = "SKU";
        binding.targetId = "sku-id";
        binding.sourcePayloadHash = hash;
        binding.version = 1L;
        return binding;
    }

    private static ProductSkuEntity skuEntity() {
        ProductSkuEntity entity = new ProductSkuEntity();
        entity.id = "sku-id";
        entity.tenantId = "tenant-id";
        entity.spuId = "spu-id";
        entity.skuCode = "SKU-1";
        entity.firstSpecificationValueSourceId = "spec-value-1";
        entity.secondSpecificationValueSourceId = "spec-value-2";
        entity.barcode = "6900000000001";
        entity.unit = "件";
        entity.specificationSummary = "红色,L";
        entity.internalStatus = "ACTIVE";
        entity.ownershipState = "EXTERNAL_PRIMARY";
        entity.version = 1L;
        return entity;
    }
}
