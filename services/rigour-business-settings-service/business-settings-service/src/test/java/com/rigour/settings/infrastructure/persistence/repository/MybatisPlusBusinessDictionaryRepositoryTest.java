package com.rigour.settings.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.infrastructure.persistence.entity.DictEntity;
import com.rigour.settings.infrastructure.persistence.entity.DictItemEntity;
import com.rigour.settings.application.port.out.BusinessDictionaryStore.SyncItem;
import com.rigour.settings.infrastructure.persistence.mapper.DictItemMapper;
import com.rigour.settings.infrastructure.persistence.mapper.DictMapper;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
        when(dictMapper.update(any(DictEntity.class), any())).thenReturn(1);
        repository = new MybatisPlusBusinessDictionaryRepository(dictMapper, itemMapper,
                Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void calculatesChildLevelFromParent() {
        UUID dictId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        DictItemEntity parent = item(parentId, dictId, null, 2);
        when(itemMapper.selectById(parentId.toString())).thenReturn(parent);
        when(itemMapper.insert(any(DictItemEntity.class))).thenReturn(1);

        DictItemView created = repository.createItem(dictId,
                new DictItemCommand(dictId, parentId, "BOX", "箱", null,
                        10, "ACTIVE", null, 0), UUID.randomUUID().toString());

        ArgumentCaptor<DictItemEntity> inserted = ArgumentCaptor.forClass(DictItemEntity.class);
        org.mockito.Mockito.verify(itemMapper).insert(inserted.capture());
        assertThat(inserted.getValue().levelNo).isEqualTo(3);
        assertThat(created.levelNo()).isEqualTo(3);
        assertThat(created.parentId()).isEqualTo(parentId);
    }

    @Test
    void rejectsParentFromAnotherDictionary() {
        UUID dictId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(itemMapper.selectById(parentId.toString()))
                .thenReturn(item(parentId, UUID.randomUUID(), null, 1));

        assertThatThrownBy(() -> repository.createItem(dictId,
                new DictItemCommand(dictId, parentId, "BOX", "箱", null,
                        10, "ACTIVE", null, 0), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("父级字典项必须属于同一本字典");
    }

    @Test
    void syncAddsOnlyMissingValuesAndTouchesDictionaryOnce() {
        UUID dictId = UUID.randomUUID();
        DictItemEntity active = item(UUID.randomUUID(), dictId, null, 1);
        active.value = "BOX"; active.status = "ACTIVE"; active.sortNo = 2;
        DictItemEntity disabled = item(UUID.randomUUID(), dictId, null, 1);
        disabled.value = "OLD"; disabled.status = "DISABLED"; disabled.sortNo = 3;
        when(itemMapper.selectList(any())).thenReturn(List.of(active, disabled));
        when(itemMapper.insert(any(DictItemEntity.class))).thenReturn(1);

        var result = repository.syncMissingItems(dictId, List.of(
                new SyncItem("AUTO_BOX", "箱", "BOX"),
                new SyncItem("AUTO_OLD", "旧单位", "OLD"),
                new SyncItem("AUTO_PAIL", "桶", "PAIL")), "service-id");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.existing()).isEqualTo(1);
        assertThat(result.blocked()).isEqualTo(1);
        ArgumentCaptor<DictItemEntity> inserted = ArgumentCaptor.forClass(DictItemEntity.class);
        verify(itemMapper).insert(inserted.capture());
        assertThat(inserted.getValue().value).isEqualTo("PAIL");
        assertThat(inserted.getValue().status).isEqualTo("ACTIVE");
        verify(dictMapper).update(any(DictEntity.class), any());
    }

    @Test
    void syncEnrichesOnlyPlaceholderDisplayName() {
        UUID dictId = UUID.randomUUID();
        DictItemEntity placeholder = item(UUID.randomUUID(), dictId, null, 1);
        placeholder.value = "T"; placeholder.name = "T"; placeholder.status = "ACTIVE";
        placeholder.sortNo = 1; placeholder.version = 2L;
        when(itemMapper.selectList(any())).thenReturn(List.of(placeholder));
        when(itemMapper.update(any(DictItemEntity.class), any())).thenReturn(1);

        var result = repository.syncMissingItems(dictId,
                List.of(new SyncItem("AUTO_T", "上架", "T")), "service-id");

        assertThat(result.existing()).isEqualTo(1);
        assertThat(result.enriched()).isEqualTo(1);
        ArgumentCaptor<DictItemEntity> updated = ArgumentCaptor.forClass(DictItemEntity.class);
        verify(itemMapper).update(updated.capture(), any());
        assertThat(updated.getValue().name).isEqualTo("上架");
        assertThat(updated.getValue().version).isEqualTo(3L);
        verify(dictMapper).update(any(DictEntity.class), any());
    }

    private static DictItemEntity item(UUID id, UUID dictId, UUID parentId, int level) {
        DictItemEntity entity = new DictItemEntity();
        entity.id = id.toString(); entity.dictId = dictId.toString();
        entity.parentId = parentId == null ? null : parentId.toString(); entity.levelNo = level;
        entity.code = "PARENT"; entity.name = "父级"; entity.sortNo = 0;
        entity.status = "ACTIVE"; entity.version = 0L;
        return entity;
    }
}
