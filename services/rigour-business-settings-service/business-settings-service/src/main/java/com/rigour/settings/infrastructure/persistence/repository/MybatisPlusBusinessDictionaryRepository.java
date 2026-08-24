package com.rigour.settings.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictView;
import com.rigour.settings.application.port.out.BusinessDictionaryStore;
import com.rigour.settings.application.port.out.BusinessDictionaryStore.SyncItem;
import com.rigour.settings.application.port.out.BusinessDictionaryStore.SyncStats;
import com.rigour.settings.infrastructure.persistence.entity.DictEntity;
import com.rigour.settings.infrastructure.persistence.entity.DictItemEntity;
import com.rigour.settings.infrastructure.persistence.mapper.DictItemMapper;
import com.rigour.settings.infrastructure.persistence.mapper.DictMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 字典仓储；新模型按 dictionaryCode 直接维护，不承载第三方来源字段。 */
@Repository
public class MybatisPlusBusinessDictionaryRepository implements BusinessDictionaryStore {
    private final DictMapper dictMapper;
    private final DictItemMapper itemMapper;
    private final Clock clock;

    public MybatisPlusBusinessDictionaryRepository(DictMapper dictMapper, DictItemMapper itemMapper, Clock clock) {
        this.dictMapper = dictMapper;
        this.itemMapper = itemMapper;
        this.clock = clock;
    }

    @Override
    public List<DictView> list(String dictionaryType, String dictionaryCode) {
        var query = Wrappers.<DictEntity>query().eq("deleted", 0);
        if (dictionaryType != null) query.eq("dictionary_type", dictionaryType);
        if (dictionaryCode != null) query.eq("dictionary_code", dictionaryCode);
        query.orderByAsc("dictionary_type", "dictionary_code");
        return dictMapper.selectList(query).stream()
                .map(MybatisPlusBusinessDictionaryRepository::view)
                .toList();
    }

    @Override
    public Optional<DictView> find(Long dictionaryId) {
        DictEntity entity = dictionaryId == null ? null : dictMapper.selectById(dictionaryId);
        if (entity == null || deleted(entity.deleted)) return Optional.empty();
        return Optional.of(view(entity));
    }

    @Override
    public Optional<DictView> findByCode(String dictionaryCode) {
        return Optional.ofNullable(dictMapper.selectOne(Wrappers.<DictEntity>query()
                        .eq("dictionary_code", dictionaryCode)
                        .eq("deleted", 0)
                        .last("LIMIT 1")))
                .map(MybatisPlusBusinessDictionaryRepository::view);
    }

    @Override
    public List<DictItemView> items(String dictionaryCode) {
        return itemMapper.selectList(Wrappers.<DictItemEntity>query()
                        .eq("dictionary_code", dictionaryCode)
                        .eq("deleted", 0)
                        .orderByAsc("dictionary_item_level", "ordinal", "dictionary_item_code"))
                .stream()
                .map(MybatisPlusBusinessDictionaryRepository::view)
                .toList();
    }

    @Override
    @Transactional
    public DictView create(DictCommand command, String actorId) {
        LocalDateTime now = now();
        DictEntity entity = new DictEntity();
        entity.dictionaryCode = command.dictionaryCode();
        entity.dictionaryName = command.dictionaryName();
        entity.dictionaryType = command.dictionaryType();
        entity.remark = command.remark();
        entity.revision = 1;
        entity.createdBy = actorId;
        entity.updatedBy = actorId;
        entity.createdTime = now;
        entity.updatedTime = now;
        entity.deleted = 0;
        try {
            dictMapper.insert(entity);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("字典编码已存在");
        }
        return find(entity.id).orElseThrow(() -> notFound("字典不存在"));
    }

    @Override
    @Transactional
    public DictView update(Long dictionaryId, DictCommand command, String actorId) {
        DictEntity existing = requireDictionaryEntity(dictionaryId);
        if (!existing.dictionaryCode.equals(command.dictionaryCode())) {
            throw conflict("字典编码创建后不可修改");
        }
        DictEntity changes = new DictEntity();
        changes.dictionaryName = command.dictionaryName();
        changes.dictionaryType = command.dictionaryType();
        changes.remark = command.remark();
        changes.revision = command.revision() + 1;
        changes.updatedBy = actorId;
        changes.updatedTime = now();
        int updated = dictMapper.update(changes, Wrappers.<DictEntity>update()
                .eq("id", dictionaryId)
                .eq("revision", command.revision())
                .eq("deleted", 0));
        if (updated != 1) throw conflict("字典已被其他人修改，请刷新后重试");
        return find(dictionaryId).orElseThrow(() -> notFound("字典不存在"));
    }

    @Override
    @Transactional
    public DictItemView createItem(Long dictionaryId, DictItemCommand command, String actorId) {
        DictEntity dictionary = requireDictionaryEntity(dictionaryId);
        if (!dictionary.dictionaryCode.equals(command.dictionaryCode())) {
            throw conflict("字典项必须属于请求路径中的字典");
        }
        DictItemEntity parent = parent(command.dictionaryCode(), command.parentDictionaryItemCode());
        LocalDateTime now = now();
        DictItemEntity entity = new DictItemEntity();
        entity.dictionaryCode = command.dictionaryCode();
        entity.parentDictionaryItemCode = parent == null ? null : parent.dictionaryItemCode;
        entity.dictionaryItemLevel = parent == null ? 1 : parent.dictionaryItemLevel + 1;
        entity.dictionaryItemCode = command.dictionaryItemCode();
        entity.dictionaryItemName = command.dictionaryItemName();
        entity.remark = command.remark();
        entity.ordinal = command.ordinal();
        entity.revision = 1;
        entity.createdBy = actorId;
        entity.updatedBy = actorId;
        entity.createdTime = now;
        entity.updatedTime = now;
        entity.deleted = 0;
        try {
            itemMapper.insert(entity);
            touchDictionary(entity.dictionaryCode, actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前字典中已存在相同编码的字典项");
        }
        return view(entity);
    }

    @Override
    @Transactional
    public DictItemView updateItem(Long itemId, DictItemCommand command, String actorId) {
        DictItemEntity existing = requireItemEntity(itemId);
        if (!existing.dictionaryCode.equals(command.dictionaryCode())) {
            throw conflict("字典项不能移动到其他字典");
        }
        if (!existing.dictionaryItemCode.equals(command.dictionaryItemCode())) {
            throw conflict("字典项编码创建后不可修改");
        }
        DictItemEntity parent = parent(command.dictionaryCode(), command.parentDictionaryItemCode());
        ensureNoCycle(existing, parent);
        int newLevel = parent == null ? 1 : parent.dictionaryItemLevel + 1;
        DictItemEntity changes = new DictItemEntity();
        changes.parentDictionaryItemCode = parent == null ? null : parent.dictionaryItemCode;
        changes.dictionaryItemLevel = newLevel;
        changes.dictionaryItemName = command.dictionaryItemName();
        changes.remark = command.remark();
        changes.ordinal = command.ordinal();
        changes.revision = command.revision() + 1;
        changes.updatedBy = actorId;
        changes.updatedTime = now();
        try {
            int updated = itemMapper.update(changes, Wrappers.<DictItemEntity>update()
                    .eq("id", itemId)
                    .eq("dictionary_code", command.dictionaryCode())
                    .eq("revision", command.revision())
                    .eq("deleted", 0));
            if (updated != 1) throw conflict("字典项已被其他人修改，请刷新后重试");
            if (newLevel != existing.dictionaryItemLevel) {
                updateDescendantLevels(existing.dictionaryCode, existing.dictionaryItemCode, newLevel, actorId);
            }
            touchDictionary(existing.dictionaryCode, actorId, changes.updatedTime);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前字典中已存在相同编码的字典项");
        }
        DictItemEntity saved = itemMapper.selectById(itemId);
        if (saved == null || deleted(saved.deleted)) throw notFound("字典项不存在");
        return view(saved);
    }

    @Override
    @Transactional
    public SyncStats syncMissingItems(String dictionaryCode, List<SyncItem> items, String actorId) {
        if (items == null || items.isEmpty()) return new SyncStats(0, 0, 0, 0);
        requireDictionaryCode(dictionaryCode);
        List<DictItemEntity> stored = itemMapper.selectList(Wrappers.<DictItemEntity>query()
                .eq("dictionary_code", dictionaryCode));
        Map<String, DictItemEntity> byCode = new LinkedHashMap<>();
        int nextOrdinal = 0;
        for (DictItemEntity item : stored) {
            nextOrdinal = Math.max(nextOrdinal, item.ordinal == null ? 0 : item.ordinal + 1);
            byCode.putIfAbsent(item.dictionaryItemCode, item);
        }
        LocalDateTime now = now();
        int created = 0;
        int existing = 0;
        int blocked = 0;
        int enriched = 0;
        List<DictItemEntity> missingItems = new java.util.ArrayList<>();
        for (SyncItem item : items) {
            DictItemEntity present = byCode.get(item.dictionaryItemCode());
            if (present != null) {
                if (!deleted(present.deleted)) {
                    existing++;
                    if (present.dictionaryItemName != null
                            && present.dictionaryItemName.equals(present.dictionaryItemCode)
                            && !item.dictionaryItemName().equals(item.dictionaryItemCode())) {
                        DictItemEntity changes = new DictItemEntity();
                        changes.dictionaryItemName = item.dictionaryItemName();
                        changes.revision = present.revision + 1;
                        changes.updatedBy = actorId;
                        changes.updatedTime = now;
                        int updated = itemMapper.update(changes, Wrappers.<DictItemEntity>update()
                                .eq("id", present.id)
                                .eq("revision", present.revision));
                        if (updated != 1) throw conflict("字典项显示名称已被其他任务修改，请重试");
                        present.dictionaryItemName = item.dictionaryItemName();
                        present.revision = changes.revision;
                        enriched++;
                    }
                } else {
                    blocked++;
                }
                continue;
            }
            DictItemEntity entity = new DictItemEntity();
            entity.dictionaryCode = dictionaryCode;
            entity.parentDictionaryItemCode = null;
            entity.dictionaryItemLevel = 1;
            entity.dictionaryItemCode = item.dictionaryItemCode();
            entity.dictionaryItemName = item.dictionaryItemName();
            entity.remark = item.remark();
            entity.ordinal = nextOrdinal++;
            entity.revision = 1;
            entity.createdBy = actorId;
            entity.updatedBy = actorId;
            entity.createdTime = now;
            entity.updatedTime = now;
            entity.deleted = 0;
            byCode.put(entity.dictionaryItemCode, entity);
            missingItems.add(entity);
            created++;
        }
        if (!missingItems.isEmpty()) {
            try {
                itemMapper.insertBatch(missingItems);
            } catch (DataIntegrityViolationException exception) {
                throw conflict("自动生成的字典项编码发生冲突");
            }
        }
        if (created > 0 || enriched > 0) touchDictionary(dictionaryCode, actorId, now);
        return new SyncStats(created, existing, blocked, enriched);
    }

    private DictEntity requireDictionaryEntity(Long dictionaryId) {
        DictEntity entity = dictionaryId == null ? null : dictMapper.selectById(dictionaryId);
        if (entity == null || deleted(entity.deleted)) throw notFound("字典不存在");
        return entity;
    }

    private void requireDictionaryCode(String dictionaryCode) {
        if (findByCode(dictionaryCode).isEmpty()) throw notFound("字典不存在");
    }

    private DictItemEntity requireItemEntity(Long itemId) {
        DictItemEntity entity = itemId == null ? null : itemMapper.selectById(itemId);
        if (entity == null || deleted(entity.deleted)) throw notFound("字典项不存在");
        return entity;
    }

    private DictItemEntity parent(String dictionaryCode, String parentDictionaryItemCode) {
        if (parentDictionaryItemCode == null) return null;
        DictItemEntity parent = itemMapper.selectOne(Wrappers.<DictItemEntity>query()
                .eq("dictionary_code", dictionaryCode)
                .eq("dictionary_item_code", parentDictionaryItemCode)
                .eq("deleted", 0)
                .last("LIMIT 1"));
        if (parent == null) throw conflict("父级字典项必须属于同一本字典");
        return parent;
    }

    private void ensureNoCycle(DictItemEntity current, DictItemEntity parent) {
        DictItemEntity cursor = parent;
        while (cursor != null) {
            if (current.dictionaryItemCode.equals(cursor.dictionaryItemCode)) {
                throw conflict("字典项父子关系不能形成循环");
            }
            cursor = parent(current.dictionaryCode, cursor.parentDictionaryItemCode);
        }
    }

    private void updateDescendantLevels(String dictionaryCode, String parentItemCode,
                                        int parentLevel, String actorId) {
        List<DictItemEntity> children = itemMapper.selectList(Wrappers.<DictItemEntity>query()
                .eq("dictionary_code", dictionaryCode)
                .eq("parent_dictionary_item_code", parentItemCode)
                .eq("deleted", 0));
        for (DictItemEntity child : children) {
            int level = parentLevel + 1;
            DictItemEntity changes = new DictItemEntity();
            changes.dictionaryItemLevel = level;
            changes.revision = child.revision + 1;
            changes.updatedBy = actorId;
            changes.updatedTime = now();
            int changed = itemMapper.update(changes, Wrappers.<DictItemEntity>update()
                    .eq("id", child.id)
                    .eq("revision", child.revision));
            if (changed != 1) throw conflict("字典项层级已被修改，请刷新后重试");
            updateDescendantLevels(dictionaryCode, child.dictionaryItemCode, level, actorId);
        }
    }

    private void touchDictionary(String dictionaryCode, String actorId, LocalDateTime changedAt) {
        DictEntity dictionary = dictMapper.selectOne(Wrappers.<DictEntity>query()
                .eq("dictionary_code", dictionaryCode)
                .eq("deleted", 0)
                .last("LIMIT 1"));
        if (dictionary == null) throw notFound("字典不存在");
        DictEntity changes = new DictEntity();
        changes.revision = dictionary.revision + 1;
        changes.updatedBy = actorId;
        changes.updatedTime = changedAt;
        int updated = dictMapper.update(changes, Wrappers.<DictEntity>update()
                .eq("id", dictionary.id)
                .eq("revision", dictionary.revision)
                .eq("deleted", 0));
        if (updated != 1) throw conflict("字典已被其他人修改，请刷新后重试");
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static DictView view(DictEntity entity) {
        return new DictView(entity.id, entity.dictionaryCode, entity.dictionaryName,
                entity.dictionaryType, entity.remark, entity.revision);
    }

    private static DictItemView view(DictItemEntity entity) {
        return new DictItemView(entity.id, entity.dictionaryCode, entity.dictionaryItemLevel,
                entity.parentDictionaryItemCode, entity.dictionaryItemCode, entity.dictionaryItemName,
                entity.remark, entity.ordinal, entity.revision);
    }

    private static boolean deleted(Integer deleted) {
        return deleted != null && deleted != 0;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
