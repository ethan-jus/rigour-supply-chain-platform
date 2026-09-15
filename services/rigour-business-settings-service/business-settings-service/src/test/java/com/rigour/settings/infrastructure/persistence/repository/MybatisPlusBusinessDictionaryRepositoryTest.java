package com.rigour.settings.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
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
                .hasMessageContaining("父级字典项必须是同一本字典的有效标准项");
    }

    @Test
    void mergePreviewRejectsDifferentParentsAndChildReferences() {
        DictItemEntity source=item(1L,"PRODUCT_UNIT",null,1), target=item(2L,"PRODUCT_UNIT","OTHER",2);
        source.dictionaryItemCode="SOURCE"; target.dictionaryItemCode="TARGET";
        when(itemMapper.selectById(1L)).thenReturn(source);
        when(itemMapper.selectById(2L)).thenReturn(target);
        when(itemMapper.selectCount(any())).thenReturn(2L,3L);
        var preview=repository.previewMerge(1L,2L);
        assertThat(preview.childReferences()).isEqualTo(2);
        assertThat(preview.aliasReferences()).isEqualTo(3);
        assertThat(preview.blockers()).contains("仅允许合并同父级条目","存在下级条目，需要先迁移下级关系");
    }

    @Test
    void mergeRejectsStalePreviewBeforeChangingRows() {
        DictItemEntity source=item(1L,"PRODUCT_UNIT",null,1), target=item(2L,"PRODUCT_UNIT",null,1);
        source.dictionaryItemCode="SOURCE"; target.dictionaryItemCode="TARGET";
        when(itemMapper.selectById(1L)).thenReturn(source);
        when(itemMapper.selectById(2L)).thenReturn(target);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(()->repository.merge(1L,new com.rigour.settings.api.v1.model.DictMergeCommand(2L,9,1,"重复"),"actor","tenant"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("重新预览");
        org.mockito.Mockito.verify(itemMapper,org.mockito.Mockito.never()).update(any(),any());
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
