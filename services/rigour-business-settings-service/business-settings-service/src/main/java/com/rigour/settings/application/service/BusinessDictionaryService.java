package com.rigour.settings.application.service;

import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictSourceValue;
import com.rigour.settings.api.v1.model.DictSyncCommand;
import com.rigour.settings.api.v1.model.DictSyncResult;
import com.rigour.settings.api.v1.model.DictView;
import com.rigour.settings.api.v1.model.EffectiveDictView;
import com.rigour.settings.application.port.out.BusinessDictionaryStore;
import com.rigour.settings.application.port.out.BusinessDictionaryStore.SyncItem;
import com.rigour.settings.application.port.out.BusinessDictionaryStore.SyncStats;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 公共业务字典用例。
 *
 * <p>新模型只暴露 dictionaryCode 和 dictionaryItemCode，避免调用方理解旧的 scope/module/tenant 继承规则。</p>
 */
@Service
public class BusinessDictionaryService {
    private static final Logger log = LoggerFactory.getLogger(BusinessDictionaryService.class);
    private static final String READ_PERMISSION = "business-settings:dict:read";
    private static final String WRITE_PERMISSION = "business-settings:dict:write";
    private static final String SYNC_PERMISSION = "business-settings:dict:sync";
    private static final int MAX_SYNC_VALUES = 500;
    private static final Set<String> DICTIONARY_TYPES = Set.of("COMMON", "ERP", "CRM", "ORDER");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    private final BusinessDictionaryStore store;

    public BusinessDictionaryService(BusinessDictionaryStore store) {
        this.store = store;
    }

    public List<DictView> list(String dictionaryType, String dictionaryCode) {
        AuthorizationContext.requirePermission(READ_PERMISSION);
        String type = optionalAllowed(dictionaryType, DICTIONARY_TYPES, "dictionaryType");
        String code = code(dictionaryCode, "dictionaryCode", false);
        List<DictView> result = store.list(type, code);
        log.debug("业务字典列表查询完成 dictionaryType={} dictionaryCode={} count={}",
                text(type), text(code), result.size());
        return result;
    }

    public List<DictItemView> items(Long dictionaryId) {
        AuthorizationContext.requirePermission(READ_PERMISSION);
        DictView dictionary = requireDictionary(dictionaryId);
        List<DictItemView> result = store.items(dictionary.dictionaryCode());
        log.debug("业务字典项查询完成 dictionaryCode={} dictionaryId={} count={}",
                dictionary.dictionaryCode(), dictionaryId, result.size());
        return result;
    }

    public EffectiveDictView effective(String dictionaryCode) {
        return resolve(dictionaryCode);
    }

    public EffectiveDictView resolve(String dictionaryCode) {
        AuthorizationContext.requirePermission(READ_PERMISSION);
        String code = code(dictionaryCode, "dictionaryCode", true);
        DictView dictionary = store.findByCode(code)
                .orElseThrow(() -> notFound("未配置有效字典: " + code));
        List<DictItemView> items = store.items(dictionary.dictionaryCode());
        log.debug("业务字典解析完成 dictionaryCode={} revision={} itemCount={}",
                dictionary.dictionaryCode(), dictionary.revision(), items.size());
        return new EffectiveDictView(dictionary, items);
    }

    /**
     * 服务间批量补齐只用于外部数据导入阶段。
     *
     * <p>它不会创建字典定义，也不会把第三方语义写进业务表；后续订货宝应优先走外部映射表。</p>
     */
    public DictSyncResult syncItems(DictSyncCommand command) {
        AuthorizationContext.requirePermission(SYNC_PERMISSION);
        CallerIdentity actor = actor();
        if (!"SERVICE".equals(actor.principalScope())) throw forbidden("只有服务身份可以自动补齐字典项");
        if (command == null) throw badRequest("字典同步参数不能为空");
        String dictionaryCode = code(command.dictionaryCode(), "dictionaryCode", true);
        List<SyncItem> values = normalizeSyncValues(command.values(), dictionaryCode);
        DictView dictionary = store.findByCode(dictionaryCode)
                .orElseThrow(() -> notFound("未配置有效字典: " + dictionaryCode));
        SyncStats stats = store.syncMissingItems(dictionary.dictionaryCode(), values, actor.principalId().toString());
        DictView refreshed = store.findByCode(dictionaryCode)
                .orElseThrow(() -> notFound("字典不存在"));
        List<DictItemView> items = store.items(dictionary.dictionaryCode());
        log.info("业务字典来源值补齐完成 dictionaryCode={} observed={} created={} existing={} blocked={} enriched={} revision={}",
                dictionaryCode, values.size(), stats.created(), stats.existing(), stats.blocked(),
                stats.enriched(), refreshed.revision());
        return new DictSyncResult(new EffectiveDictView(refreshed, items), values.size(),
                stats.created(), stats.existing(), stats.blocked());
    }

    public DictView create(DictCommand command) {
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        CallerIdentity actor = actor();
        DictCommand normalized = normalize(command, false);
        DictView created = store.create(normalized, actor.principalId().toString());
        log.info("业务字典创建完成 dictionaryCode={} dictionaryName={} dictionaryType={} actorId={}",
                created.dictionaryCode(), created.dictionaryName(), created.dictionaryType(), actor.principalId());
        return created;
    }

    public DictView update(Long dictionaryId, DictCommand command) {
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        CallerIdentity actor = actor();
        DictView existing = requireDictionary(dictionaryId);
        DictCommand normalized = normalize(command, true);
        if (!existing.dictionaryCode().equals(normalized.dictionaryCode())) {
            throw conflict("字典编码创建后不可修改");
        }
        DictView updated = store.update(dictionaryId, normalized, actor.principalId().toString());
        log.info("业务字典修改完成 dictionaryCode={} dictionaryName={} dictionaryType={} revision={} actorId={}",
                updated.dictionaryCode(), updated.dictionaryName(), updated.dictionaryType(),
                updated.revision(), actor.principalId());
        return updated;
    }

    public DictItemView createItem(Long dictionaryId, DictItemCommand command) {
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        CallerIdentity actor = actor();
        DictView dictionary = requireDictionary(dictionaryId);
        if (command != null && command.revision() != 0) throw badRequest("新增字典项时revision必须为0");
        DictItemCommand normalized = normalize(command, dictionary.dictionaryCode(), false);
        DictItemView created = store.createItem(dictionaryId, normalized, actor.principalId().toString());
        log.info("业务字典项创建完成 dictionaryCode={} itemCode={} itemName={} parentItemCode={} actorId={}",
                created.dictionaryCode(), created.dictionaryItemCode(), created.dictionaryItemName(),
                text(created.parentDictionaryItemCode()), actor.principalId());
        return created;
    }

    public DictItemView updateItem(Long itemId, DictItemCommand command) {
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        CallerIdentity actor = actor();
        if (command == null) throw badRequest("字典项参数不能为空");
        DictItemCommand normalized = normalize(command, null, true);
        DictItemView updated = store.updateItem(itemId, normalized, actor.principalId().toString());
        log.info("业务字典项修改完成 dictionaryCode={} itemCode={} itemName={} revision={} actorId={}",
                updated.dictionaryCode(), updated.dictionaryItemCode(), updated.dictionaryItemName(),
                updated.revision(), actor.principalId());
        return updated;
    }

    private DictCommand normalize(DictCommand command, boolean update) {
        if (command == null) throw badRequest("字典参数不能为空");
        int revision = command.revision();
        if (revision < 0 || (!update && revision != 0)) throw badRequest("revision无效");
        return new DictCommand(
                code(command.dictionaryCode(), "dictionaryCode", true),
                required(command.dictionaryName(), "dictionaryName不能为空", 100),
                allowed(command.dictionaryType(), DICTIONARY_TYPES, "dictionaryType"),
                limit(command.remark(), 500, "remark"),
                revision);
    }

    private DictItemCommand normalize(DictItemCommand command, String expectedDictionaryCode, boolean update) {
        if (command == null) throw badRequest("字典项参数不能为空");
        int revision = command.revision();
        if (revision < 0 || (!update && revision != 0)) throw badRequest("revision无效");
        String dictionaryCode = code(command.dictionaryCode(), "dictionaryCode", true);
        if (expectedDictionaryCode != null && !expectedDictionaryCode.equals(dictionaryCode)) {
            throw badRequest("dictionaryCode与请求路径不一致");
        }
        return new DictItemCommand(
                dictionaryCode,
                code(command.parentDictionaryItemCode(), "parentDictionaryItemCode", false),
                code(command.dictionaryItemCode(), "dictionaryItemCode", true),
                required(command.dictionaryItemName(), "dictionaryItemName不能为空", 100),
                limit(command.remark(), 500, "remark"),
                command.ordinal(),
                revision);
    }

    private List<SyncItem> normalizeSyncValues(List<DictSourceValue> values, String dictionaryCode) {
        List<DictSourceValue> source = values == null ? List.of() : values;
        if (source.size() > MAX_SYNC_VALUES) throw badRequest("单次字典同步来源值不能超过" + MAX_SYNC_VALUES + "个");
        Map<String, Set<String>> names = new LinkedHashMap<>();
        for (DictSourceValue item : source) {
            if (item == null) throw badRequest("字典同步来源值不能为空");
            String value = exact(item.value(), "来源值不能为空", 255);
            String name = limit(item.name(), 100, "来源显示名称");
            names.computeIfAbsent(value, ignored -> new LinkedHashSet<>());
            if (name != null) names.get(value).add(name);
        }
        return names.entrySet().stream()
                .map(entry -> syncItem(dictionaryCode, entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .toList();
    }

    private SyncItem syncItem(String dictionaryCode, String sourceValue, Set<String> names) {
        String explicitName = names.size() == 1 ? names.iterator().next() : null;
        String displayName = explicitName != null ? explicitName : sourceValue;
        return new SyncItem(sourceItemCode(dictionaryCode, sourceValue), displayName, "外部来源值：" + sourceValue);
    }

    private DictView requireDictionary(Long dictionaryId) {
        if (dictionaryId == null) throw badRequest("dictionaryId不能为空");
        return store.find(dictionaryId).orElseThrow(() -> notFound("字典不存在"));
    }

    private static String autoCode(String dictionaryCode, String sourceValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((dictionaryCode + '\0' + sourceValue).getBytes(StandardCharsets.UTF_8));
            return "AUTO_" + HexFormat.of().formatHex(digest).substring(0, 45).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持SHA-256", exception);
        }
    }

    private static String sourceItemCode(String dictionaryCode, String sourceValue) {
        String normalized = upper(sourceValue);
        if (normalized != null && CODE.matcher(normalized).matches()) return normalized;
        return autoCode(dictionaryCode, sourceValue);
    }

    private static CallerIdentity actor() {
        return AuthorizationContext.requireCurrent();
    }

    private static String code(String value, String name, boolean required) {
        String normalized = upper(value);
        if (normalized == null) {
            if (required) throw badRequest(name + "不能为空");
            return null;
        }
        if (!CODE.matcher(normalized).matches()) throw badRequest(name + "格式无效");
        return normalized;
    }

    private static String allowed(String value, Set<String> allowed, String name) {
        String normalized = upper(value);
        if (normalized == null || !allowed.contains(normalized)) throw badRequest(name + "取值无效");
        return normalized;
    }

    private static String optionalAllowed(String value, Set<String> allowed, String name) {
        String normalized = upper(value);
        if (normalized == null) return null;
        if (!allowed.contains(normalized)) throw badRequest(name + "取值无效");
        return normalized;
    }

    private static String required(String value, String message, int max) {
        String normalized = trim(value);
        if (normalized == null) throw badRequest(message);
        if (normalized.length() > max) throw badRequest(message.replace("不能为空", "长度不能超过" + max));
        return normalized;
    }

    private static String exact(String value, String message, int max) {
        if (value == null || value.isBlank()) throw badRequest(message);
        if (value.length() > max) throw badRequest(message.replace("不能为空", "长度不能超过" + max));
        return value;
    }

    private static String limit(String value, int max, String name) {
        String normalized = trim(value);
        if (normalized != null && normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static String upper(String value) {
        String normalized = trim(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String text(String value) {
        return value == null ? "-" : value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message, List.of());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
