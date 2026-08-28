package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.application.port.out.ProductMasterDataStore.ImportResult;
import com.rigour.erp.domain.model.product.Category;
import com.rigour.erp.domain.model.product.Tag;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductCategoryEntity;
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
}
