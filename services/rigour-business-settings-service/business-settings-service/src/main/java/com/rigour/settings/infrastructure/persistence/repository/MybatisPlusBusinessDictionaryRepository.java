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
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 字典仓储；复制租户字典时在同一事务内复制完整树。 */
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
    public List<DictView> list(String principalScope, String currentTenantId, String moduleCode,
                               String scopeType, String requestedTenantId, String status) {
        var query = Wrappers.<DictEntity>query();
        if (!"PLATFORM".equals(principalScope)) {
            query.and(scope -> scope.in("scope_type", List.of("SYSTEM", "MODULE"))
                    .or(tenant -> tenant.eq("scope_type", "TENANT")
                            .eq("tenant_id", currentTenantId)));
        } else if (requestedTenantId != null) {
            query.eq("tenant_id", requestedTenantId);
        }
        if (moduleCode != null) query.eq("module_code", moduleCode);
        if (scopeType != null) query.eq("scope_type", scopeType);
        if (status != null) query.eq("status", status);
        query.orderByAsc("module_code", "sort_no", "code");
        return dictMapper.selectList(query).stream().map(MybatisPlusBusinessDictionaryRepository::view).toList();
    }

    @Override
    public Optional<DictView> find(UUID dictId) {
        return Optional.ofNullable(dictMapper.selectById(dictId.toString())).map(MybatisPlusBusinessDictionaryRepository::view);
    }

    @Override
    public Optional<DictView> findActive(String scopeType, String scopeId, String moduleCode, String code) {
        return Optional.ofNullable(dictMapper.selectOne(Wrappers.<DictEntity>query()
                        .eq("scope_type", scopeType)
                        .eq("scope_id", scopeId)
                        .eq("module_code", moduleCode)
                        .eq("code", code)
                        .eq("status", "ACTIVE")
                        .last("LIMIT 1")))
                .map(MybatisPlusBusinessDictionaryRepository::view);
    }

    @Override
    public List<DictItemView> items(UUID dictId) {
        return itemMapper.selectList(Wrappers.<DictItemEntity>query()
                        .eq("dict_id", dictId.toString())
                        .orderByAsc("level_no", "sort_no", "code"))
                .stream().map(MybatisPlusBusinessDictionaryRepository::view).toList();
    }

    @Override
    @Transactional
    public DictView create(DictCommand command, String scopeId, String tenantId, String actorId) {
        LocalDateTime now = now();
        DictEntity entity = new DictEntity();
        entity.id = UUID.randomUUID().toString();
        entity.code = command.code(); entity.name = command.name();
        entity.scopeType = command.scopeType(); entity.scopeId = scopeId;
        entity.moduleCode = command.moduleCode(); entity.tenantId = tenantId;
        entity.baseDictId = id(command.baseDictId()); entity.status = command.status();
        entity.sortNo = command.sortNo(); entity.remark = command.remark(); entity.version = 0L;
        entity.revision = 0L;
        entity.createdBy = actorId; entity.updatedBy = actorId;
        entity.createdAt = now; entity.updatedAt = now;
        try {
            dictMapper.insert(entity);
            if (command.baseDictId() != null) {
                cloneItems(command.baseDictId().toString(), entity.id, actorId, now);
                touchDictionary(entity.id, actorId, now);
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("相同作用域、模块和编码的字典已存在");
        }
        return find(UUID.fromString(entity.id)).orElseThrow(() -> notFound("字典不存在"));
    }

    @Override
    @Transactional
    public DictView update(UUID dictId, DictCommand command, String actorId) {
        DictEntity changes = new DictEntity();
        changes.name = command.name(); changes.status = command.status();
        changes.sortNo = command.sortNo(); changes.remark = command.remark();
        changes.version = command.version() + 1; changes.updatedBy = actorId; changes.updatedAt = now();
        int updated = dictMapper.update(changes, Wrappers.<DictEntity>update()
                .setSql("revision = revision + 1")
                .eq("id", dictId.toString()).eq("version", command.version()));
        if (updated != 1) throw conflict("字典已被其他人修改，请刷新后重试");
        return find(dictId).orElseThrow(() -> notFound("字典不存在"));
    }

    @Override
    @Transactional
    public DictItemView createItem(UUID dictId, DictItemCommand command, String actorId) {
        DictItemEntity parent = parent(dictId, command.parentId(), null);
        LocalDateTime now = now();
        DictItemEntity entity = new DictItemEntity();
        entity.id = UUID.randomUUID().toString(); entity.dictId = dictId.toString();
        entity.parentId = parent == null ? null : parent.id;
        entity.levelNo = parent == null ? 1 : parent.levelNo + 1;
        entity.code = command.code(); entity.name = command.name(); entity.value = command.value();
        entity.sortNo = command.sortNo(); entity.status = command.status(); entity.extraJson = command.extraJson();
        entity.version = 0L; entity.createdBy = actorId; entity.updatedBy = actorId;
        entity.createdAt = now; entity.updatedAt = now;
        try {
            itemMapper.insert(entity);
            touchDictionary(dictId.toString(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前字典中已存在相同编码的字典项");
        }
        return view(entity);
    }

    @Override
    @Transactional
    public DictItemView updateItem(UUID itemId, DictItemCommand command, String actorId) {
        DictItemEntity existing = itemMapper.selectById(itemId.toString());
        if (existing == null) throw notFound("字典项不存在");
        if (!existing.dictId.equals(command.dictId().toString())) throw conflict("字典项不能移动到其他字典");
        DictItemEntity parent = parent(command.dictId(), command.parentId(), itemId);
        ensureNoCycle(existing, parent);
        int newLevel = parent == null ? 1 : parent.levelNo + 1;
        DictItemEntity changes = new DictItemEntity();
        changes.parentId = parent == null ? null : parent.id; changes.levelNo = newLevel;
        changes.code = command.code(); changes.name = command.name(); changes.value = command.value();
        changes.sortNo = command.sortNo(); changes.status = command.status(); changes.extraJson = command.extraJson();
        changes.version = command.version() + 1; changes.updatedBy = actorId; changes.updatedAt = now();
        try {
            int updated = itemMapper.update(changes, Wrappers.<DictItemEntity>update()
                    .eq("id", itemId.toString()).eq("dict_id", command.dictId().toString())
                    .eq("version", command.version()));
            if (updated != 1) throw conflict("字典项已被其他人修改，请刷新后重试");
            if (newLevel != existing.levelNo) updateDescendantLevels(existing.dictId, existing.id, newLevel, actorId);
            touchDictionary(existing.dictId, actorId, changes.updatedAt);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前字典中已存在相同编码的字典项");
        }
        DictItemEntity saved = itemMapper.selectById(itemId.toString());
        if (saved == null) throw notFound("字典项不存在");
        return view(saved);
    }

    @Override
    @Transactional
    public SyncStats syncMissingItems(UUID dictId, List<SyncItem> items, String actorId) {
        if (items == null || items.isEmpty()) return new SyncStats(0, 0, 0, 0);
        String dictionaryId = dictId.toString();
        List<DictItemEntity> stored = itemMapper.selectList(Wrappers.<DictItemEntity>query()
                .eq("dict_id", dictionaryId));
        Map<String, DictItemEntity> byValue = new LinkedHashMap<>();
        int nextSortNo = 0;
        for (DictItemEntity item : stored) {
            nextSortNo = Math.max(nextSortNo, item.sortNo == null ? 0 : item.sortNo + 1);
            if (item.value == null) continue;
            DictItemEntity duplicate = byValue.putIfAbsent(item.value, item);
            if (duplicate != null) throw conflict("当前字典存在重复来源值，请先修复字典数据");
        }
        LocalDateTime now = now();
        int created = 0;
        int existing = 0;
        int blocked = 0;
        int enriched = 0;
        for (SyncItem item : items) {
            DictItemEntity present = byValue.get(item.value());
            if (present != null) {
                if ("ACTIVE".equals(present.status)) {
                    existing++;
                    if (present.name != null && present.name.equals(present.value)
                            && !item.name().equals(item.value())) {
                        DictItemEntity changes = new DictItemEntity();
                        changes.name = item.name(); changes.version = present.version + 1;
                        changes.updatedBy = actorId; changes.updatedAt = now;
                        int updated = itemMapper.update(changes, Wrappers.<DictItemEntity>update()
                                .eq("id", present.id).eq("version", present.version));
                        if (updated != 1) throw conflict("字典项显示名称已被其他任务修改，请重试");
                        present.name = item.name(); present.version = changes.version;
                        enriched++;
                    }
                }
                else blocked++;
                continue;
            }
            DictItemEntity entity = new DictItemEntity();
            entity.id = UUID.randomUUID().toString(); entity.dictId = dictionaryId;
            entity.parentId = null; entity.levelNo = 1;
            entity.code = item.code(); entity.name = item.name(); entity.value = item.value();
            entity.sortNo = nextSortNo++; entity.status = "ACTIVE"; entity.extraJson = null;
            entity.version = 0L; entity.createdBy = actorId; entity.updatedBy = actorId;
            entity.createdAt = now; entity.updatedAt = now;
            try {
                itemMapper.insert(entity);
                byValue.put(entity.value, entity);
                created++;
            } catch (DataIntegrityViolationException exception) {
                DictItemEntity concurrent = findItemByExactValue(dictionaryId, item.value());
                if (concurrent == null) throw conflict("自动生成的字典项编码发生冲突");
                byValue.put(concurrent.value, concurrent);
                if ("ACTIVE".equals(concurrent.status)) existing++;
                else blocked++;
            }
        }
        if (created > 0 || enriched > 0) touchDictionary(dictionaryId, actorId, now);
        return new SyncStats(created, existing, blocked, enriched);
    }

    private void cloneItems(String sourceDictId, String targetDictId, String actorId, LocalDateTime now) {
        List<DictItemEntity> source = itemMapper.selectList(Wrappers.<DictItemEntity>query()
                .eq("dict_id", sourceDictId).orderByAsc("level_no", "sort_no", "code"));
        Map<String, String> copiedIds = new LinkedHashMap<>();
        for (DictItemEntity item : source) {
            DictItemEntity copy = new DictItemEntity();
            copy.id = UUID.randomUUID().toString(); copy.dictId = targetDictId;
            copy.parentId = item.parentId == null ? null : copiedIds.get(item.parentId);
            copy.levelNo = item.levelNo; copy.code = item.code; copy.name = item.name; copy.value = item.value;
            copy.sortNo = item.sortNo; copy.status = item.status; copy.extraJson = item.extraJson;
            copy.version = 0L; copy.createdBy = actorId; copy.updatedBy = actorId;
            copy.createdAt = now; copy.updatedAt = now;
            itemMapper.insert(copy);
            copiedIds.put(item.id, copy.id);
        }
    }

    private DictItemEntity findItemByExactValue(String dictId, String value) {
        return itemMapper.selectList(Wrappers.<DictItemEntity>query()
                        .eq("dict_id", dictId).eq("value", value))
                .stream().filter(item -> value.equals(item.value)).findFirst().orElse(null);
    }

    private DictItemEntity parent(UUID dictId, UUID parentId, UUID currentItemId) {
        if (parentId == null) return null;
        if (parentId.equals(currentItemId)) throw conflict("字典项不能将自己设为父级");
        DictItemEntity parent = itemMapper.selectById(parentId.toString());
        if (parent == null || !dictId.toString().equals(parent.dictId)) {
            throw conflict("父级字典项必须属于同一本字典");
        }
        return parent;
    }

    private void ensureNoCycle(DictItemEntity current, DictItemEntity parent) {
        DictItemEntity cursor = parent;
        while (cursor != null) {
            if (current.id.equals(cursor.id)) throw conflict("字典项父子关系不能形成循环");
            cursor = cursor.parentId == null ? null : itemMapper.selectById(cursor.parentId);
        }
    }

    private void updateDescendantLevels(String dictId, String parentId, int parentLevel, String actorId) {
        List<DictItemEntity> children = itemMapper.selectList(Wrappers.<DictItemEntity>query()
                .eq("dict_id", dictId).eq("parent_id", parentId));
        for (DictItemEntity child : children) {
            int level = parentLevel + 1;
            DictItemEntity changes = new DictItemEntity();
            changes.levelNo = level; changes.version = child.version + 1;
            changes.updatedBy = actorId; changes.updatedAt = now();
            int changed = itemMapper.update(changes, Wrappers.<DictItemEntity>update()
                    .eq("id", child.id).eq("version", child.version));
            if (changed != 1) throw conflict("字典项层级已被修改，请刷新后重试");
            updateDescendantLevels(dictId, child.id, level, actorId);
        }
    }

    private void touchDictionary(String dictId, String actorId, LocalDateTime changedAt) {
        DictEntity changes = new DictEntity();
        changes.updatedBy = actorId;
        changes.updatedAt = changedAt;
        int updated = dictMapper.update(changes, Wrappers.<DictEntity>update()
                .setSql("revision = revision + 1")
                .eq("id", dictId));
        if (updated != 1) throw notFound("字典不存在");
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static DictView view(DictEntity entity) {
        return new DictView(UUID.fromString(entity.id), entity.code, entity.name, entity.scopeType,
                entity.scopeId, entity.moduleCode, entity.tenantId, uuid(entity.baseDictId),
                entity.status, entity.sortNo, entity.remark, entity.version, entity.revision);
    }

    private static DictItemView view(DictItemEntity entity) {
        return new DictItemView(UUID.fromString(entity.id), UUID.fromString(entity.dictId),
                uuid(entity.parentId), entity.levelNo, entity.code, entity.name, entity.value,
                entity.sortNo, entity.status, entity.extraJson, entity.version);
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static String id(UUID value) { return value == null ? null : value.toString(); }
    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }
    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
