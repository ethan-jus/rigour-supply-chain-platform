package com.rigour.settings.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.application.port.out.BusinessDictionaryStore.SyncItem;
import com.rigour.settings.infrastructure.persistence.entity.DictEntity;
import com.rigour.settings.infrastructure.persistence.entity.DictItemEntity;
import com.rigour.settings.infrastructure.persistence.mapper.DictItemMapper;
import com.rigour.settings.infrastructure.persistence.mapper.DictMapper;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证字典项层级由服务端计算，并拒绝跨字典父节点。 */
class MybatisPlusBusinessDictionaryRepositoryTest {
    private DictItemMapper itemMapper;
    private DictMapper dictMapper;
    private MybatisPlusBusinessDictionaryRepository repository;

    @BeforeEach
    void setUp() {
        dictMapper = mock(DictMapper.class);
        itemMapper = mock(DictItemMapper.class);
        when(dictMapper.selectOne(any())).thenReturn(dictionary());
        when(dictMapper.update(any(DictEntity.class), any())).thenReturn(1);
        repository = new MybatisPlusBusinessDictionaryRepository(dictMapper, itemMapper,
                Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void calculatesChildLevelFromParentCode() {
        DictEntity dictionary = dictionary();
        DictItemEntity parent = item(10L, "PRODUCT_UNIT", null, 2);
        when(dictMapper.selectById(1L)).thenReturn(dictionary);
        when(itemMapper.selectOne(any())).thenReturn(parent);
        when(itemMapper.insert(any(DictItemEntity.class))).thenReturn(1);

        DictItemView created = repository.createItem(1L,
                new DictItemCommand("PRODUCT_UNIT", "PARENT", "BOX", "箱", null, 10, 0),
                "actor-id");

        ArgumentCaptor<DictItemEntity> inserted = ArgumentCaptor.forClass(DictItemEntity.class);
        verify(itemMapper).insert(inserted.capture());
        assertThat(inserted.getValue().dictionaryItemLevel).isEqualTo(3);
        assertThat(created.dictionaryItemLevel()).isEqualTo(3);
        assertThat(created.parentDictionaryItemCode()).isEqualTo("PARENT");
    }

    @Test
    void rejectsMissingParentCode() {
        when(dictMapper.selectById(1L)).thenReturn(dictionary());
        when(itemMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> repository.createItem(1L,
                new DictItemCommand("PRODUCT_UNIT", "UNKNOWN", "BOX", "箱", null, 10, 0),
                "actor-id"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("父级字典项必须属于同一本字典");
    }

    @Test
    void syncAddsOnlyMissingCodesAndTouchesDictionaryOnce() {
        DictItemEntity active = item(10L, "PRODUCT_UNIT", null, 1);
        active.dictionaryItemCode = "BOX";
        active.dictionaryItemName = "箱";
        active.deleted = 0;
        active.ordinal = 2;
        DictItemEntity deleted = item(11L, "PRODUCT_UNIT", null, 1);
        deleted.dictionaryItemCode = "OLD";
        deleted.deleted = 1;
        deleted.ordinal = 3;
        when(dictMapper.selectOne(any())).thenReturn(dictionary());
        when(itemMapper.selectList(any())).thenReturn(List.of(active, deleted));
        when(itemMapper.insert(any(DictItemEntity.class))).thenReturn(1);

        var result = repository.syncMissingItems("PRODUCT_UNIT", List.of(
                new SyncItem("BOX", "箱", null),
                new SyncItem("OLD", "旧单位", null),
                new SyncItem("PAIL", "桶", null)), "service-id");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.existing()).isEqualTo(1);
        assertThat(result.blocked()).isEqualTo(1);
        ArgumentCaptor<List<DictItemEntity>> inserted = ArgumentCaptor.forClass(List.class);
        verify(itemMapper).insertBatch(inserted.capture());
        assertThat(inserted.getValue()).hasSize(1);
        assertThat(inserted.getValue().getFirst().dictionaryItemCode).isEqualTo("PAIL");
        assertThat(inserted.getValue().getFirst().dictionaryItemName).isEqualTo("桶");
        verify(dictMapper).update(any(DictEntity.class), any());
    }

    @Test
    void syncEnrichesOnlyPlaceholderDisplayName() {
        DictItemEntity placeholder = item(10L, "PRODUCT_UNIT", null, 1);
        placeholder.dictionaryItemCode = "BOX";
        placeholder.dictionaryItemName = "BOX";
        placeholder.ordinal = 1;
        placeholder.revision = 2;
        when(dictMapper.selectOne(any())).thenReturn(dictionary());
        when(itemMapper.selectList(any())).thenReturn(List.of(placeholder));
        when(itemMapper.update(any(DictItemEntity.class), any())).thenReturn(1);

        var result = repository.syncMissingItems("PRODUCT_UNIT",
                List.of(new SyncItem("BOX", "箱", null)), "service-id");

        assertThat(result.existing()).isEqualTo(1);
        assertThat(result.enriched()).isEqualTo(1);
        ArgumentCaptor<DictItemEntity> updated = ArgumentCaptor.forClass(DictItemEntity.class);
        verify(itemMapper).update(updated.capture(), any());
        assertThat(updated.getValue().dictionaryItemName).isEqualTo("箱");
        assertThat(updated.getValue().revision).isEqualTo(3);
        verify(dictMapper).update(any(DictEntity.class), any());
    }

    private static DictEntity dictionary() {
        DictEntity entity = new DictEntity();
        entity.id = 1L;
        entity.dictionaryCode = "PRODUCT_UNIT";
        entity.dictionaryName = "商品单位";
        entity.dictionaryType = "COMMON";
        entity.revision = 1;
        entity.deleted = 0;
        return entity;
    }

    private static DictItemEntity item(Long id, String dictionaryCode, String parentItemCode, int level) {
        DictItemEntity entity = new DictItemEntity();
        entity.id = id;
        entity.dictionaryCode = dictionaryCode;
        entity.parentDictionaryItemCode = parentItemCode;
        entity.dictionaryItemLevel = level;
        entity.dictionaryItemCode = "PARENT";
        entity.dictionaryItemName = "父级";
        entity.ordinal = 0;
        entity.revision = 1;
        entity.deleted = 0;
        return entity;
    }
}
