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
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 公共业务字典用例；统一执行作用域授权、编码规范和整本字典选择。 */
@Service
public class BusinessDictionaryService {
    private static final Logger log = LoggerFactory.getLogger(BusinessDictionaryService.class);
    private static final String READ_PERMISSION = "business-settings:dict:read";
    private static final String WRITE_PERMISSION = "business-settings:dict:write";
    private static final String SYNC_PERMISSION = "business-settings:dict:sync";
    private static final int MAX_SYNC_VALUES = 500;
    private static final Set<String> SCOPES = Set.of("SYSTEM", "MODULE", "TENANT");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern MODULE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    private final BusinessDictionaryStore store;
    private final ObjectMapper objectMapper;

    public BusinessDictionaryService(BusinessDictionaryStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public List<DictView> list(String moduleCode, String scopeType, String tenantId, String status) {
        AuthorizationContext.requirePermission(READ_PERMISSION);
        CallerIdentity actor = actor();
        if ("SERVICE".equals(actor.principalScope())) throw forbidden("服务身份不能使用字典管理列表");
        String requestedTenant = trim(tenantId);
        if ("TENANT".equals(actor.principalScope()) && requestedTenant != null
                && !requestedTenant.equals(currentTenant(actor))) {
            throw forbidden("不能查询其他租户的字典");
        }
        List<DictView> result = store.list(actor.principalScope(), currentTenant(actor), upper(moduleCode),
                allowed(scopeType, SCOPES, "scopeType"), requestedTenant,
                allowed(status, STATUSES, "status"));
        log.debug("Business dictionaries listed: actorScope={}, tenantId={}, moduleCode={}, count={}",
                actor.principalScope(), currentTenant(actor), upper(moduleCode), result.size());
        return result;
    }

    public List<DictItemView> items(UUID dictId) {
        AuthorizationContext.requirePermission(READ_PERMISSION);
        DictView dictionary = requireDictionary(dictId);
        requireReadable(actor(), dictionary);
        List<DictItemView> result = store.items(dictId);
        log.debug("Business dictionary items listed: dictId={}, actorId={}, count={}",
                dictId, actor().principalId(), result.size());
        return result;
    }

    public EffectiveDictView effective(String moduleCode, String code) {
        return resolve(moduleCode, code, false);
    }

    /** 历史显示解析保留已停用条目，防止旧业务记录失去可读名称。 */
    public EffectiveDictView resolve(String moduleCode, String code) {
        return resolve(moduleCode, code, true);
    }

    private EffectiveDictView resolve(String moduleCode, String code, boolean includeDisabled) {
        AuthorizationContext.requirePermission(READ_PERMISSION);
        CallerIdentity actor = actor();
        String module = requireModule(moduleCode);
        String dictionaryCode = requireCode(code, "code");
        DictView dictionary = effectiveDictionary(actor, module, dictionaryCode);
        List<DictItemView> items = store.items(dictionary.id()).stream()
                .filter(item -> includeDisabled || "ACTIVE".equals(item.status()))
                .toList();
        log.debug("Effective business dictionary resolved: dictId={}, moduleCode={}, code={}, tenantId={}, revision={}, includeDisabled={}, itemCount={}",
                dictionary.id(), module, dictionaryCode, currentTenant(actor), dictionary.revision(),
                includeDisabled, items.size());
        return new EffectiveDictView(dictionary, items);
    }

    /**
     * 服务间批量补齐明确来源值。该操作不创建字典定义、不猜测未知值含义、不重新启用停用项；
     * 仅允许把历史“名称=原值”的占位项补充为已确认名称。
     */
    public DictSyncResult syncItems(DictSyncCommand command) {
        AuthorizationContext.requirePermission(SYNC_PERMISSION);
        CallerIdentity actor = actor();
        if (!"SERVICE".equals(actor.principalScope())) throw forbidden("只有服务身份可以自动补齐字典项");
        if (actor.tenantId() == null) throw forbidden("自动补齐字典项必须携带租户身份");
        if (command == null) throw badRequest("字典同步参数不能为空");
        String module = requireModule(command.moduleCode());
        String dictionaryCode = requireCode(command.dictCode(), "dictCode");
        List<SyncItem> values = normalizeSyncValues(command.values(), module, dictionaryCode);
        DictView dictionary = effectiveDictionary(actor, module, dictionaryCode);
        SyncStats stats = store.syncMissingItems(dictionary.id(), values, actor.principalId().toString());
        DictView refreshed = requireDictionary(dictionary.id());
        List<DictItemView> activeItems = store.items(dictionary.id()).stream()
                .filter(item -> "ACTIVE".equals(item.status()))
                .toList();
        log.info("Business dictionary source values synchronized: dictId={}, moduleCode={}, code={}, tenantId={}, observed={}, created={}, existing={}, blocked={}, enriched={}, revision={}",
                dictionary.id(), module, dictionaryCode, currentTenant(actor), values.size(), stats.created(),
                stats.existing(), stats.blocked(), stats.enriched(), refreshed.revision());
        return new DictSyncResult(new EffectiveDictView(refreshed, activeItems), values.size(),
                stats.created(), stats.existing(), stats.blocked());
    }

    public DictView create(DictCommand command) {
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        CallerIdentity actor = actor();
        DictCommand normalized = normalize(command, false);
        Owner owner = owner(actor, normalized.scopeType(), normalized.moduleCode(), normalized.tenantId());
        validateBase(normalized, actor);
        DictView created = store.create(normalized, owner.scopeId(), owner.tenantId(), actor.principalId().toString());
        log.info("Business dictionary created: dictId={}, code={}, moduleCode={}, scopeType={}, tenantId={}, actorId={}",
                created.id(), created.code(), created.moduleCode(), created.scopeType(), created.tenantId(),
                actor.principalId());
        return created;
    }

    public DictView update(UUID dictId, DictCommand command) {
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        CallerIdentity actor = actor();
        DictView existing = requireDictionary(dictId);
        requireWritable(actor, existing);
        DictCommand normalized = normalize(command, true);
        if (!existing.code().equals(normalized.code())
                || !existing.scopeType().equals(normalized.scopeType())
                || !existing.moduleCode().equals(normalized.moduleCode())
                || !Objects.equals(existing.tenantId(), normalized.tenantId())
                || !Objects.equals(existing.baseDictId(), normalized.baseDictId())) {
            throw conflict("字典编码、作用域、模块和基础字典创建后不可修改");
        }
        DictView updated = store.update(dictId, normalized, actor.principalId().toString());
        log.info("Business dictionary updated: dictId={}, code={}, moduleCode={}, scopeType={}, tenantId={}, actorId={}, version={}",
                updated.id(), updated.code(), updated.moduleCode(), updated.scopeType(), updated.tenantId(),
                actor.principalId(), updated.version());
        return updated;
    }

    public DictItemView createItem(UUID dictId, DictItemCommand command) {
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        CallerIdentity actor = actor();
        DictView dictionary = requireDictionary(dictId);
        requireWritable(actor, dictionary);
        if (command != null && command.version() != 0) throw badRequest("新增字典项时version必须为0");
        DictItemCommand normalized = normalize(command, dictId);
        DictItemView created = store.createItem(dictId, normalized, actor.principalId().toString());
        log.info("Business dictionary item created: dictId={}, itemId={}, itemCode={}, parentId={}, actorId={}",
                dictId, created.id(), created.code(), created.parentId(), actor.principalId());
        return created;
    }

    public DictItemView updateItem(UUID itemId, DictItemCommand command) {
        AuthorizationContext.requirePermission(WRITE_PERMISSION);
        CallerIdentity actor = actor();
        if (command == null || command.dictId() == null) throw badRequest("dictId不能为空");
        DictView dictionary = requireDictionary(command.dictId());
        requireWritable(actor, dictionary);
        DictItemCommand normalized = normalize(command, command.dictId());
        DictItemView updated = store.updateItem(itemId, normalized, actor.principalId().toString());
        log.info("Business dictionary item updated: dictId={}, itemId={}, itemCode={}, parentId={}, actorId={}, version={}",
                updated.dictId(), updated.id(), updated.code(), updated.parentId(), actor.principalId(),
                updated.version());
        return updated;
    }

    private void validateBase(DictCommand command, CallerIdentity actor) {
        if (command.baseDictId() == null) return;
        if (!"TENANT".equals(command.scopeType())) throw badRequest("只有租户级字典可以指定基础字典");
        DictView base = requireDictionary(command.baseDictId());
        requireReadable(actor, base);
        if ("TENANT".equals(base.scopeType())) throw badRequest("基础字典只能是系统级或模块级字典");
        if (!base.code().equals(command.code()) || !base.moduleCode().equals(command.moduleCode())) {
            throw badRequest("租户字典与基础字典的编码、模块必须一致");
        }
    }

    private static Owner owner(CallerIdentity actor, String scopeType, String moduleCode, String commandTenantId) {
        if ("SYSTEM".equals(scopeType)) {
            requirePlatform(actor, "只有平台身份可以创建系统级字典");
            return new Owner("SYSTEM", null);
        }
        if ("MODULE".equals(scopeType)) {
            requirePlatform(actor, "只有平台身份可以创建模块级字典");
            return new Owner(moduleCode, null);
        }
        String tenantId;
        if ("PLATFORM".equals(actor.principalScope())) {
            tenantId = requireText(commandTenantId, "创建租户级字典时tenantId不能为空", 64);
        } else if ("TENANT".equals(actor.principalScope())) {
            tenantId = currentTenant(actor);
            if (commandTenantId != null && !tenantId.equals(commandTenantId)) {
                throw forbidden("不能为其他租户创建字典");
            }
        } else {
            throw forbidden("服务身份不能维护字典");
        }
        return new Owner(tenantId, tenantId);
    }

    private static void requireReadable(CallerIdentity actor, DictView dictionary) {
        if ("PLATFORM".equals(actor.principalScope())) return;
        if (!"TENANT".equals(dictionary.scopeType())) return;
        String tenantId = currentTenant(actor);
        if (tenantId == null || !tenantId.equals(dictionary.tenantId())) throw forbidden("不能访问其他租户的字典");
    }

    private static void requireWritable(CallerIdentity actor, DictView dictionary) {
        if ("PLATFORM".equals(actor.principalScope())) return;
        if (!"TENANT".equals(actor.principalScope()) || !"TENANT".equals(dictionary.scopeType())
                || !currentTenant(actor).equals(dictionary.tenantId())) {
            throw forbidden("当前身份不能修改该作用域的字典");
        }
    }

    private DictCommand normalize(DictCommand command, boolean update) {
        if (command == null) throw badRequest("字典参数不能为空");
        String scopeType = allowedRequired(command.scopeType(), SCOPES, "scopeType");
        String status = allowedRequired(command.status(), STATUSES, "status");
        String tenantId = trim(command.tenantId());
        if (!"TENANT".equals(scopeType)) tenantId = null;
        long version = command.version();
        if (version < 0 || (!update && version != 0)) throw badRequest("version无效");
        return new DictCommand(requireCode(command.code(), "code"),
                requireText(command.name(), "name不能为空", 100), scopeType,
                requireModule(command.moduleCode()), tenantId, command.baseDictId(), status,
                command.sortNo(), limit(command.remark(), 500, "remark"), version);
    }

    private DictItemCommand normalize(DictItemCommand command, UUID dictId) {
        if (command == null) throw badRequest("字典项参数不能为空");
        if (command.dictId() != null && !dictId.equals(command.dictId())) throw badRequest("dictId与请求路径不一致");
        if (command.version() < 0) throw badRequest("version无效");
        String extraJson = trim(command.extraJson());
        if (extraJson != null) {
            try {
                objectMapper.readTree(extraJson);
            } catch (Exception exception) {
                throw badRequest("extraJson必须是合法JSON");
            }
        }
        return new DictItemCommand(dictId, command.parentId(), requireCode(command.code(), "code"),
                requireText(command.name(), "name不能为空", 100), limit(command.value(), 255, "value"),
                command.sortNo(), allowedRequired(command.status(), STATUSES, "status"), extraJson,
                command.version());
    }

    private List<SyncItem> normalizeSyncValues(List<DictSourceValue> values, String moduleCode,
                                               String dictCode) {
        List<DictSourceValue> source = values == null ? List.of() : values;
        if (source.size() > MAX_SYNC_VALUES) throw badRequest("单次字典同步来源值不能超过" + MAX_SYNC_VALUES + "个");
        Map<String, Set<String>> names = new LinkedHashMap<>();
        for (DictSourceValue item : source) {
            if (item == null) throw badRequest("字典同步来源值不能为空");
            String value = exactText(item.value(), "来源值不能为空", 255);
            String name = trim(item.name());
            if (name != null && name.length() > 100) throw badRequest("来源显示名称长度不能超过100");
            names.computeIfAbsent(value, ignored -> new LinkedHashSet<>());
            if (name != null) names.get(value).add(name);
        }
        return names.entrySet().stream()
                .map(entry -> syncItem(moduleCode, dictCode, entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .toList();
    }

    private SyncItem syncItem(String moduleCode, String dictCode, String value, Set<String> names) {
        String officialName = DhbDictionaryDisplayNames.resolve(moduleCode, dictCode, value);
        String explicitName = names.size() == 1 ? names.iterator().next() : null;
        String displayName = explicitName != null && !explicitName.equals(value)
                ? explicitName : officialName;
        if (displayName == null && containsCjk(value)) displayName = value;
        if (displayName == null) return null;
        return new SyncItem(autoCode(dictCode, value), displayName, value);
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                        || (codePoint >= 0x4E00 && codePoint <= 0x9FFF));
    }

    private DictView effectiveDictionary(CallerIdentity actor, String module, String dictionaryCode) {
        DictView dictionary = null;
        String tenantId = currentTenant(actor);
        if (tenantId != null) {
            dictionary = store.findActive("TENANT", tenantId, module, dictionaryCode).orElse(null);
        }
        if (dictionary == null) {
            dictionary = store.findActive("MODULE", module, module, dictionaryCode).orElse(null);
        }
        if (dictionary == null) {
            dictionary = store.findActive("SYSTEM", "SYSTEM", module, dictionaryCode).orElse(null);
        }
        if (dictionary == null) throw notFound("未配置有效字典: " + module + "." + dictionaryCode);
        return dictionary;
    }

    private static String exactText(String value, String message, int max) {
        if (value == null || value.isBlank()) throw badRequest(message);
        if (value.length() > max) throw badRequest("来源值长度不能超过" + max);
        return value;
    }

    private static String autoCode(String dictCode, String sourceValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((dictCode + '\0' + sourceValue).getBytes(StandardCharsets.UTF_8));
            return "AUTO_" + HexFormat.of().formatHex(digest).substring(0, 59).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持SHA-256", exception);
        }
    }

    private DictView requireDictionary(UUID dictId) {
        if (dictId == null) throw badRequest("dictId不能为空");
        return store.find(dictId).orElseThrow(() -> notFound("字典不存在"));
    }

    private static CallerIdentity actor() { return AuthorizationContext.requireCurrent(); }
    private static String currentTenant(CallerIdentity actor) {
        return actor.tenantId() == null ? null : actor.tenantId().toString();
    }
    private static String requireModule(String value) {
        String module = upper(value);
        if (module == null || !MODULE.matcher(module).matches()) throw badRequest("moduleCode格式无效");
        return module;
    }
    private static String requireCode(String value, String name) {
        String code = upper(value);
        if (code == null || !CODE.matcher(code).matches()) throw badRequest(name + "格式无效");
        return code;
    }
    private static String allowed(String value, Set<String> allowed, String name) {
        String normalized = upper(value);
        if (normalized == null) return null;
        if (!allowed.contains(normalized)) throw badRequest(name + "取值无效");
        return normalized;
    }
    private static String allowedRequired(String value, Set<String> allowed, String name) {
        String normalized = allowed(value, allowed, name);
        if (normalized == null) throw badRequest(name + "不能为空");
        return normalized;
    }
    private static String requireText(String value, String message, int max) {
        String normalized = trim(value);
        if (normalized == null) throw badRequest(message);
        if (normalized.length() > max) throw badRequest(message.replace("不能为空", "长度不能超过" + max));
        return normalized;
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
    private static void requirePlatform(CallerIdentity actor, String message) {
        if (!"PLATFORM".equals(actor.principalScope())) throw forbidden(message);
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

    private record Owner(String scopeId, String tenantId) {
    }
}
