package com.rigour.settings.infrastructure.persistence.repository;

import com.rigour.settings.api.v1.model.*;
import com.rigour.settings.application.port.out.BusinessDictionaryStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** 内置基线只读；租户按稳定编码覆盖，未覆盖的新基线条目仍可见。 */
@Repository
public class JdbcTenantDictionaryRepository implements BusinessDictionaryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcTenantDictionaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String tenant() {
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null
                || !Set.of("TENANT", "SERVICE").contains(actor.principalScope()))
            throw new AuthorizationDeniedException("business-settings:tenant-required");
        return actor.tenantId().toString();
    }

    @Override
    public List<DictView> list(String type, String code) {
        var merged = new LinkedHashMap<String, DictView>();
        jdbc.query(
                        "SELECT * FROM data_dictionary WHERE tenant_key IN ('GLOBAL',?) AND"
                                + " deleted=0 ORDER BY (tenant_key='GLOBAL') DESC,dictionary_code",
                        this::dictionary,
                        tenant())
                .forEach(d -> merged.put(d.dictionaryCode(), d));
        return merged.values().stream()
                .filter(d -> type == null || type.equals(d.dictionaryType()))
                .filter(d -> code == null || code.equals(d.dictionaryCode()))
                .sorted(
                        Comparator.comparing(DictView::dictionaryType)
                                .thenComparing(DictView::dictionaryCode))
                .toList();
    }

    @Override
    public Optional<DictView> findByCode(String code) {
        return list(null, code).stream().findFirst();
    }

    @Override
    public Optional<DictView> find(Long id) {
        if (id == null) return Optional.empty();
        var rows =
                jdbc.queryForList(
                        "SELECT dictionary_code FROM data_dictionary WHERE id=? AND tenant_key IN"
                                + " ('GLOBAL',?) AND deleted=0",
                        String.class,
                        id,
                        tenant());
        return rows.isEmpty() ? Optional.empty() : findByCode(rows.getFirst());
    }

    @Override
    public List<DictItemView> items(String code) {
        if (findByCode(code).isEmpty()) return List.of();
        var merged = new LinkedHashMap<String, DictItemView>();
        jdbc.query(
                        "SELECT * FROM data_dictionary_item WHERE tenant_key IN ('GLOBAL',?) AND"
                                + " dictionary_code=? AND deleted=0 ORDER BY (tenant_key='GLOBAL')"
                                + " DESC,id",
                        this::item,
                        tenant(),
                        code)
                .forEach(i -> merged.put(i.dictionaryItemCode(), i));
        return merged.values().stream()
                .sorted(
                        Comparator.comparingInt(DictItemView::dictionaryItemLevel)
                                .thenComparingInt(DictItemView::ordinal)
                                .thenComparing(DictItemView::dictionaryItemCode))
                .toList();
    }

    @Override
    @Transactional
    public DictView create(DictCommand c, String actor) {
        if (findByCode(c.dictionaryCode()).isPresent()) throw conflict("字典编码已存在");
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " data_dictionary(tenant_key,dictionary_code,dictionary_name,dictionary_type,remark,allow_new_items,created_by,updated_by)"
                        + " VALUES(?,?,?,?,?,TRUE,?,?)",
                    tenant(),
                    c.dictionaryCode(),
                    c.dictionaryName(),
                    c.dictionaryType(),
                    c.remark(),
                    actor,
                    actor);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw conflict("字典编码已存在");
        }
        var saved = requireCode(c.dictionaryCode());
        audit("dictionary:create", c.dictionaryCode(), actor, null, saved);
        return saved;
    }

    @Override
    @Transactional
    public DictView update(Long id, DictCommand c, String actor) {
        var existing = requireId(id);
        if (!existing.dictionaryCode().equals(c.dictionaryCode())) throw conflict("字典编码创建后不可修改");
        var current = lock(c.dictionaryCode(), actor);
        revision(current.revision(), c.revision());
        if (builtin(c.dictionaryCode()) && !current.dictionaryType().equals(c.dictionaryType()))
            throw conflict("内置字典不能修改业务分类");
        jdbc.update(
                "UPDATE data_dictionary SET"
                    + " dictionary_name=?,dictionary_type=?,remark=?,revision=revision+1,updated_by=?"
                    + " WHERE tenant_key=? AND dictionary_code=?",
                c.dictionaryName(),
                c.dictionaryType(),
                c.remark(),
                actor,
                tenant(),
                c.dictionaryCode());
        var saved = requireCode(c.dictionaryCode());
        audit("dictionary:update", c.dictionaryCode(), actor, current, saved);
        return saved;
    }

    @Override
    @Transactional
    public DictItemView createItem(Long id, DictItemCommand c, String actor) {
        var dictionary = requireId(id);
        if (!dictionary.dictionaryCode().equals(c.dictionaryCode())) throw conflict("字典项必须属于当前字典");
        dictionary = lock(c.dictionaryCode(), actor);
        if (!dictionary.allowNewItems()) throw conflict("流程控制字典由程序维护，不能新增状态");
        var entries = entries(c.dictionaryCode());
        if (entries.containsKey(c.dictionaryItemCode())) throw conflict("字典项编码已存在");
        uniqueName(c, entries);
        int level = validateParent(c, entries);
        putItem(c, level, 1, actor, true);
        touch(c.dictionaryCode(), actor);
        var saved = entries(c.dictionaryCode()).get(c.dictionaryItemCode());
        audit(
                "dictionary-item:create",
                c.dictionaryCode() + ":" + c.dictionaryItemCode(),
                actor,
                null,
                saved);
        return saved;
    }

    @Override
    @Transactional
    public DictItemView updateItem(Long id, DictItemCommand c, String actor) {
        var idRows =
                jdbc.queryForList(
                        "SELECT dictionary_code,dictionary_item_code FROM data_dictionary_item"
                                + " WHERE id=? AND tenant_key IN ('GLOBAL',?) AND deleted=0",
                        id,
                        tenant());
        if (idRows.isEmpty()) throw missing("字典项不存在");
        var identified = idRows.getFirst();
        if (!c.dictionaryCode().equals(identified.get("dictionary_code"))
                || !c.dictionaryItemCode().equals(identified.get("dictionary_item_code")))
            throw conflict("字典项编码和所属字典不可修改");
        var dictionary = lock(c.dictionaryCode(), actor);
        var entries = entries(c.dictionaryCode());
        var before = entries.get(c.dictionaryItemCode());
        if (before == null) throw missing("字典项不存在");
        revision(before.revision(), c.revision());
        if (before.alias()) throw conflict("历史兼容项保留原始信息，请维护对应标准项");
        if (!dictionary.allowNewItems()
                && (!Objects.equals(before.parentDictionaryItemCode(), c.parentDictionaryItemCode())
                        || !Boolean.TRUE.equals(c.enabled())))
            throw conflict("流程控制值仅允许修改显示名称、说明和排序");
        uniqueName(c, entries);
        int level = validateParent(c, entries);
        putItem(c, level, before.revision() + 1, actor, true);
        // 整本字典锁保证层级变更和所有后代重算原子完成；父链校验避免循环。
        if (level != before.dictionaryItemLevel())
            updateChildren(
                    c.dictionaryCode(),
                    c.dictionaryItemCode(),
                    level,
                    entries,
                    actor,
                    new HashSet<>());
        touch(c.dictionaryCode(), actor);
        var saved = entries(c.dictionaryCode()).get(c.dictionaryItemCode());
        audit(
                "dictionary-item:update",
                c.dictionaryCode() + ":" + c.dictionaryItemCode(),
                actor,
                before,
                saved);
        return saved;
    }

    @Override
    public DictMergePreview previewMerge(Long itemId, Long targetItemId) {
        var source = requireItem(itemId);
        var target = requireItem(targetItemId);
        var blockers = new ArrayList<String>();
        if (source.id().equals(target.id())) blockers.add("不能合并到自身");
        if (!source.dictionaryCode().equals(target.dictionaryCode())) blockers.add("跨字典调整需按维度迁移处理");
        if (!Objects.equals(source.parentDictionaryItemCode(), target.parentDictionaryItemCode()))
            blockers.add("仅允许合并同父级条目");
        if (source.alias() || target.alias()) blockers.add("合并双方必须是标准项");
        if (!requireCode(source.dictionaryCode()).allowNewItems()) blockers.add("流程控制字典不能合并状态");
        if (!active(target, entries(target.dictionaryCode()))) blockers.add("目标及其祖先必须启用");
        long children =
                items(source.dictionaryCode()).stream()
                        .filter(
                                i ->
                                        source.dictionaryItemCode()
                                                .equals(i.parentDictionaryItemCode()))
                        .count();
        long aliases = aliasesOf(source).size();
        if (children > 0) blockers.add("存在下级条目，需要先迁移下级关系");
        return new DictMergePreview(source, target, children, aliases, List.copyOf(blockers));
    }

    @Override
    @Transactional
    public DictMergePreview merge(
            Long itemId, DictMergeCommand command, String actor, String tenantId) {
        if (!tenant().equals(tenantId)) throw conflict("合并租户与当前身份不一致");
        var initial = requireItem(itemId);
        // 字典锁按编码排序；只写本租户覆盖，不修改共享基线或其他租户。
        var codes = new TreeSet<String>();
        codes.add(initial.dictionaryCode());
        aliasesOf(initial).forEach(i -> codes.add(i.dictionaryCode()));
        for (String code : codes) lock(code, actor);
        var preview = previewMerge(itemId, command.targetItemId());
        if (!preview.blockers().isEmpty()) throw conflict(String.join("；", preview.blockers()));
        revision(preview.source().revision(), command.sourceRevision());
        revision(preview.target().revision(), command.targetRevision());
        var aliases = new ArrayList<>(aliasesOf(preview.source()));
        aliases.add(preview.source());
        for (var alias : aliases) {
            putAlias(alias, preview.target(), actor);
            touch(alias.dictionaryCode(), actor);
        }
        jdbc.update(
                """
                INSERT INTO data_dictionary_merge_log(tenant_id,dictionary_code,source_item_code,
                    target_item_code,source_revision,target_revision,reason,created_by)
                VALUES(?,?,?,?,?,?,?,?)
                """,
                tenant(),
                initial.dictionaryCode(),
                preview.source().dictionaryItemCode(),
                preview.target().dictionaryItemCode(),
                command.sourceRevision(),
                command.targetRevision(),
                command.reason(),
                actor);
        audit(
                "dictionary-item:merge",
                initial.dictionaryCode() + ":" + initial.dictionaryItemCode(),
                actor,
                preview,
                Map.of(
                        "reason",
                        command.reason(),
                        "source",
                        entries(initial.dictionaryCode()).get(initial.dictionaryItemCode())));
        return preview;
    }

    private List<DictItemView> aliasesOf(DictItemView source) {
        return list(null, null).stream()
                .flatMap(d -> items(d.dictionaryCode()).stream())
                .filter(
                        i ->
                                source.dictionaryCode().equals(i.canonicalDictionaryCode())
                                        && source.dictionaryItemCode()
                                                .equals(i.canonicalItemCode()))
                .toList();
    }

    private DictItemView requireItem(Long id) {
        var keys =
                jdbc.queryForList(
                        "SELECT dictionary_code,dictionary_item_code FROM data_dictionary_item"
                            + " WHERE id=? AND tenant_key IN ('GLOBAL',?) AND deleted=0",
                        id,
                        tenant());
        if (keys.isEmpty()) throw missing("字典项不存在");
        var row = keys.getFirst();
        var item =
                entries((String) row.get("dictionary_code")).get(row.get("dictionary_item_code"));
        if (item == null) throw missing("字典项不存在");
        return item;
    }

    private void putAlias(DictItemView before, DictItemView target, String actor) {
        jdbc.update(
                """
INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,remark,ordinal,revision,enabled,locally_managed,canonical_dictionary_code,canonical_item_code,created_by,updated_by)
VALUES(?,?,?,?,?,?,?,?,?,?,TRUE,?,?,?,?)
ON DUPLICATE KEY UPDATE canonical_dictionary_code=VALUES(canonical_dictionary_code),canonical_item_code=VALUES(canonical_item_code),revision=VALUES(revision),locally_managed=TRUE,updated_by=VALUES(updated_by)
""",
                tenant(),
                before.dictionaryCode(),
                before.dictionaryItemLevel(),
                before.parentDictionaryItemCode(),
                before.dictionaryItemCode(),
                before.dictionaryItemName(),
                before.remark(),
                before.ordinal(),
                before.revision() + 1,
                before.enabled(),
                target.dictionaryCode(),
                target.dictionaryItemCode(),
                actor,
                actor);
    }

    private static boolean active(DictItemView item, Map<String, DictItemView> entries) {
        var seen = new HashSet<String>();
        while (item != null) {
            if (!item.enabled() || item.alias() || !seen.add(item.dictionaryItemCode()))
                return false;
            if (item.parentDictionaryItemCode() == null) return true;
            item = entries.get(item.parentDictionaryItemCode());
        }
        return false;
    }

    private DictView lock(String code, String actor) {
        requireCode(code);
        jdbc.update(
                """
INSERT INTO data_dictionary(tenant_key,dictionary_code,dictionary_name,dictionary_type,remark,allow_new_items,revision,created_by,updated_by)
SELECT ?,baseline.dictionary_code,baseline.dictionary_name,baseline.dictionary_type,baseline.remark,baseline.allow_new_items,baseline.revision,?,?
  FROM data_dictionary baseline WHERE baseline.tenant_key='GLOBAL' AND baseline.dictionary_code=? AND baseline.deleted=0
ON DUPLICATE KEY UPDATE id=data_dictionary.id
""",
                tenant(),
                actor,
                actor,
                code);
        return jdbc
                .query(
                        "SELECT * FROM data_dictionary WHERE tenant_key=? AND dictionary_code=? AND"
                                + " deleted=0 FOR UPDATE",
                        this::dictionary,
                        tenant(),
                        code)
                .stream()
                .findFirst()
                .orElseThrow(() -> missing("字典不存在"));
    }

    private void putItem(DictItemCommand c, int level, int revision, String actor, boolean local) {
        // 不能用 ON DUPLICATE KEY UPDATE：名称唯一键冲突不能误改另一个编码的记录。
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM data_dictionary_item WHERE tenant_key=? AND"
                            + " dictionary_code=? AND dictionary_item_code=?",
                        Long.class,
                        tenant(),
                        c.dictionaryCode(),
                        c.dictionaryItemCode());
        try {
            if (ids.isEmpty())
                jdbc.update(
                        """
INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,remark,ordinal,revision,enabled,locally_managed,created_by,updated_by)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
                        tenant(),
                        c.dictionaryCode(),
                        level,
                        c.parentDictionaryItemCode(),
                        c.dictionaryItemCode(),
                        c.dictionaryItemName(),
                        c.remark(),
                        c.ordinal(),
                        revision,
                        !Boolean.FALSE.equals(c.enabled()),
                        local,
                        actor,
                        actor);
            else
                jdbc.update(
                        """
UPDATE data_dictionary_item SET dictionary_item_level=?,parent_dictionary_item_code=?,dictionary_item_name=?,remark=?,ordinal=?,revision=?,enabled=?,locally_managed=?,updated_by=? WHERE id=? AND tenant_key=?
""",
                        level,
                        c.parentDictionaryItemCode(),
                        c.dictionaryItemName(),
                        c.remark(),
                        c.ordinal(),
                        revision,
                        !Boolean.FALSE.equals(c.enabled()),
                        local,
                        actor,
                        ids.getFirst(),
                        tenant());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw conflict("同父级下已存在同名字典项");
        }
    }

    private static void uniqueName(DictItemCommand c, Map<String, DictItemView> entries) {
        if (entries.values().stream()
                .anyMatch(
                        i ->
                                !i.alias()
                                        && !i.dictionaryItemCode().equals(c.dictionaryItemCode())
                                        && Objects.equals(
                                                i.parentDictionaryItemCode(),
                                                c.parentDictionaryItemCode())
                                        && i.dictionaryItemName()
                                                .strip()
                                                .equalsIgnoreCase(c.dictionaryItemName().strip())))
            throw conflict("同父级下已存在同名字典项");
    }

    private int validateParent(DictItemCommand c, Map<String, DictItemView> entries) {
        if (c.parentDictionaryItemCode() == null) return 1;
        var parent = entries.get(c.parentDictionaryItemCode());
        if (parent == null
                || parent.alias()
                || (!parent.enabled() && !Boolean.FALSE.equals(c.enabled())))
            throw conflict("父节点必须是同一字典的有效条目");
        var seen = new HashSet<String>();
        seen.add(c.dictionaryItemCode());
        var cursor = parent;
        while (cursor != null) {
            if (!seen.add(cursor.dictionaryItemCode()) || seen.size() > 32)
                throw conflict("字典层级循环或超过32级");
            cursor =
                    cursor.parentDictionaryItemCode() == null
                            ? null
                            : entries.get(cursor.parentDictionaryItemCode());
        }
        return parent.dictionaryItemLevel() + 1;
    }

    private void updateChildren(
            String code,
            String parent,
            int level,
            Map<String, DictItemView> entries,
            String actor,
            Set<String> visited) {
        if (!visited.add(parent) || level >= 32) throw conflict("字典层级循环或过深");
        for (var child : entries.values())
            if (parent.equals(child.parentDictionaryItemCode())) {
                var c =
                        new DictItemCommand(
                                code,
                                parent,
                                child.dictionaryItemCode(),
                                child.dictionaryItemName(),
                                child.remark(),
                                child.ordinal(),
                                child.revision(),
                                child.enabled());
                putItem(c, level + 1, child.revision() + 1, actor, true);
                updateChildren(
                        code, child.dictionaryItemCode(), level + 1, entries, actor, visited);
            }
    }

    private Map<String, DictItemView> entries(String code) {
        var values = new LinkedHashMap<String, DictItemView>();
        items(code).forEach(i -> values.put(i.dictionaryItemCode(), i));
        return values;
    }

    private void touch(String code, String actor) {
        jdbc.update(
                "UPDATE data_dictionary SET revision=revision+1,updated_by=? WHERE tenant_key=? AND"
                        + " dictionary_code=?",
                actor,
                tenant(),
                code);
    }

    private boolean builtin(String code) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM data_dictionary WHERE tenant_key='GLOBAL' AND"
                                + " dictionary_code=? AND deleted=0",
                        Integer.class,
                        code)
                > 0;
    }

    private DictView requireCode(String code) {
        return findByCode(code).orElseThrow(() -> missing("字典不存在"));
    }

    private DictView requireId(Long id) {
        return find(id).orElseThrow(() -> missing("字典不存在"));
    }

    private static void revision(int actual, int expected) {
        if (actual != expected) throw conflict("数据已被修改，请刷新后重试");
    }

    private void audit(String action, String object, String actor, Object before, Object after) {
        jdbc.update(
                "INSERT INTO"
                    + " settings_operation_audit(tenant_id,actor_id,module_code,action_code,object_code,before_json,after_json)"
                    + " VALUES(?,?,'DICTIONARY',?,?,?,?)",
                tenant(),
                actor,
                action,
                object,
                before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after));
    }

    private DictView dictionary(ResultSet r, int row) throws SQLException {
        String code = r.getString("dictionary_code");
        return new DictView(
                r.getLong("id"),
                code,
                r.getString("dictionary_name"),
                r.getString("dictionary_type"),
                r.getString("remark"),
                r.getInt("revision"),
                "GLOBAL".equals(r.getString("tenant_key"))
                        ? "BUILTIN"
                        : builtin(code) ? "OVERRIDE" : "CUSTOM",
                r.getBoolean("allow_new_items"));
    }

    private DictItemView item(ResultSet r, int row) throws SQLException {
        return new DictItemView(
                r.getLong("id"),
                r.getString("dictionary_code"),
                r.getInt("dictionary_item_level"),
                r.getString("parent_dictionary_item_code"),
                r.getString("dictionary_item_code"),
                r.getString("dictionary_item_name"),
                r.getString("remark"),
                r.getInt("ordinal"),
                r.getInt("revision"),
                r.getBoolean("enabled"),
                r.getString("canonical_dictionary_code"),
                r.getString("canonical_item_code"));
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException missing(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
