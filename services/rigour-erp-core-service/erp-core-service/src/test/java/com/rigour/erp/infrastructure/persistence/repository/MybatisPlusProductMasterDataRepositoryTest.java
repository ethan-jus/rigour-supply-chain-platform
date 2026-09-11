package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.application.port.out.ProductMasterDataStore.ImportResult;
import com.rigour.erp.domain.model.product.Category;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.Tag;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductCategoryEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductTagEntity;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisPlusProductMasterDataRepositoryTest {
    private static final String TENANT_ID = "019fb100-0000-7000-8000-000000000011";
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb100-0000-7000-8000-000000000012");
    private static final UUID RUN_ID = UUID.fromString("019fb100-0000-7000-8000-000000000013");

    private final InternalProductCategoryMapper categoryMapper = mock(InternalProductCategoryMapper.class);
    private final InternalProductBrandMapper brandMapper = mock(InternalProductBrandMapper.class);
    private final InternalProductSpecificationMapper specificationMapper = mock(InternalProductSpecificationMapper.class);
    private final InternalProductSpecificationValueMapper specificationValueMapper =
            mock(InternalProductSpecificationValueMapper.class);
    private final InternalProductTagMapper tagMapper = mock(InternalProductTagMapper.class);
    private final InternalProductMapper productMapper = mock(InternalProductMapper.class);
    private final InternalProductVariantMapper variantMapper = mock(InternalProductVariantMapper.class);
    private final MasterSourceBindingMapper bindingMapper = mock(MasterSourceBindingMapper.class);
    private final MasterDataSyncRunMapper syncRunMapper = mock(MasterDataSyncRunMapper.class);
    private final MasterDataSyncLockMapper syncLockMapper = mock(MasterDataSyncLockMapper.class);
    private final MybatisPlusProductMasterDataRepository repository = repository();

    @Test
    void categorySyncCodeUsesSourceCreatedTimeFromRawFields() {
        givenRunConnector();
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalProductCategoryEntity entity = invocation.getArgument(0);
            entity.setId(7L);
            return 1;
        }).when(categoryMapper).insert(any(InternalProductCategoryEntity.class));

        ImportResult result = repository.importCategory(TENANT_ID, RUN_ID,
                new Category("CAT-1", null, "球杆", null, null, null,
                        Map.of("create_date", "2026-08-21 09:20:00"), "hash-category"));

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<InternalProductCategoryEntity> inserted =
                ArgumentCaptor.forClass(InternalProductCategoryEntity.class);
        verify(categoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getCategoryCode()).startsWith("CAT20260821");
    }

    @Test
    void tagSyncCodeUsesNormalizedSourceCreatedAt() {
        givenRunConnector();
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(tagMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalProductTagEntity entity = invocation.getArgument(0);
            entity.setId(9L);
            return 1;
        }).when(tagMapper).insert(any(InternalProductTagEntity.class));

        ImportResult result = repository.importTag(TENANT_ID, RUN_ID,
                new Tag("TAG-1", "NEW", "新品", null, null,
                        Instant.parse("2026-08-21T01:20:00Z"), null, null,
                        null, Map.of(), "hash-tag"));

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<InternalProductTagEntity> inserted =
                ArgumentCaptor.forClass(InternalProductTagEntity.class);
        verify(tagMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTagCode()).startsWith("TAG20260821");
    }

    @Test
    void productSyncPersistsDhbSourceFieldsForDetailProjection() {
        givenRunConnector();
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalProductEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        }).when(productMapper).insert(any(InternalProductEntity.class));

        Map<String, Object> source = Map.of(
                "guid", "P-1",
                "coding", "100026",
                "name", "黄金礼包-礼袋",
                "barcode", "BAR-1",
                "units", "个",
                "price1", "58.00",
                "librarysafe", "0.0000",
                "related_goods", List.of(Map.of(
                        "goods_id", "R-1",
                        "name", "专业款皮头",
                        "price1", "58.00",
                        "units", "个")));

        ImportResult result = repository.importProduct(TENANT_ID, RUN_ID,
                new Product("P-1", "100026", "黄金礼包-礼袋", "T",
                        "BAR-1", "个", null, null, List.of(), source, "hash-product"));

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<InternalProductEntity> inserted =
                ArgumentCaptor.forClass(InternalProductEntity.class);
        verify(productMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(inserted.getValue().getSourceProductId()).isEqualTo("P-1");
        assertThat(inserted.getValue().getSourceDocumentNo()).isEqualTo("100026");
        assertThat(inserted.getValue().getSourcePayloadHash()).isEqualTo("hash-product");
        assertThat(inserted.getValue().getSourcePayloadJson())
                .contains("\"barcode\":\"BAR-1\"")
                .contains("\"related_goods\"");
    }

    @Test
    void productSyncRefreshesRelatedGoodsIntoRecommendProductIdsAfterAllProductsAreBound() {
        givenRunConnector();
        MasterSourceBindingEntity currentBinding = binding("P-1", "11");
        MasterSourceBindingEntity relatedBinding = binding("R-1", "22");
        InternalProductEntity current = product(11L, "黄金礼包-礼袋");
        InternalProductEntity related = product(22L, "专业款皮头");
        when(bindingMapper.selectOne(any())).thenReturn(currentBinding, relatedBinding);
        when(productMapper.selectById(11L)).thenReturn(current);
        when(productMapper.selectById(22L)).thenReturn(related);

        ImportResult result = repository.refreshProductRecommendations(TENANT_ID, RUN_ID, List.of(
                new Product("P-1", "100026", "黄金礼包-礼袋", "T",
                        "BAR-1", "个", null, null, List.of(),
                        Map.of("related_goods", List.of(Map.of(
                                "goods_id", "R-1",
                                "name", "专业款皮头",
                                "price1", "58.00"))),
                        "hash-product")));

        assertThat(result.changed()).isEqualTo(1);
        ArgumentCaptor<InternalProductEntity> updated =
                ArgumentCaptor.forClass(InternalProductEntity.class);
        verify(productMapper).updateById(updated.capture());
        assertThat(updated.getValue().getRecommendProductIdsJson()).isEqualTo("[22]");
    }

    private MybatisPlusProductMasterDataRepository repository() {
        return new MybatisPlusProductMasterDataRepository(
                categoryMapper, brandMapper, specificationMapper, specificationValueMapper,
                tagMapper, productMapper, variantMapper, bindingMapper, syncRunMapper,
                syncLockMapper, Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));
    }

    private void givenRunConnector() {
        MasterDataSyncRunEntity run = new MasterDataSyncRunEntity();
        run.id = RUN_ID.toString();
        run.tenantId = TENANT_ID;
        run.connectorId = CONNECTOR_ID.toString();
        when(syncRunMapper.selectOne(any())).thenReturn(run);
    }

    private static MasterSourceBindingEntity binding(String sourceId, String targetId) {
        MasterSourceBindingEntity binding = new MasterSourceBindingEntity();
        binding.sourceObjectId = sourceId;
        binding.targetId = targetId;
        binding.sourcePresence = "PRESENT";
        return binding;
    }

    private static InternalProductEntity product(Long id, String name) {
        InternalProductEntity product = new InternalProductEntity();
        product.setId(id);
        product.setTenantId(TENANT_ID);
        product.setProductName(name);
        product.setRecommendProductIdsJson("[]");
        product.setUpdatedBy("SYSTEM");
        product.setRevision(1);
        product.setDeleted(0);
        return product;
    }
}
