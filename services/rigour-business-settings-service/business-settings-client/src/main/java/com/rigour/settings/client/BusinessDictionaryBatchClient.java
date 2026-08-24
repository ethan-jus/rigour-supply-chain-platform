package com.rigour.settings.client;

import com.rigour.settings.api.v1.BusinessDictionaryInternalApi;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictSourceValue;
import com.rigour.settings.api.v1.model.DictSyncCommand;
import com.rigour.settings.api.v1.model.DictSyncResult;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 各领域服务复用的业务字典批处理客户端。
 *
 * <p>调用方只声明明确字段观察值；本类负责按 dictionaryCode 去重并批量补齐、按来源值精确解析、
 * 在 Settings 不可用时返回审计告警而不抛出异常中断主数据落库。它不会扫描任意来源字段，
 * 也不会把第三方字段定义成业务字典结构。</p>
 */
public final class BusinessDictionaryBatchClient {
    private static final Logger log = LoggerFactory.getLogger(BusinessDictionaryBatchClient.class);
    private static final String SYNC_PERMISSION = "business-settings:dict:sync";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public BusinessDictionaryBatchClient(RestClient.Builder builder,
                                         TrustedContextSigner signer,
                                         String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(baseUrl);
    }

    /**
     * 批量补齐并校验当前同步批次的所有明确观察值。
     *
     * @param caller 携带租户和 business-settings:dict:sync 权限的服务身份
     * @param sourceType 调用方用于日志审计的同步对象类型
     * @param observations 调用方显式列出的字典字段和值
     * @return 各字典修订号及未能精确解析的来源值；远端失败也通过该结果返回
     */
    public Audit sync(CallerIdentity caller, String sourceType, Collection<Observation> observations) {
        requireCaller(caller);
        List<Observation> source = observations == null ? List.of() : observations.stream()
                .filter(Objects::nonNull).filter(Observation::hasValue).toList();
        if (source.isEmpty()) return Audit.empty();
        Map<DictionaryKey, Map<SourceKey, Long>> grouped = group(source);
        Map<String, Long> revisions = new LinkedHashMap<>();
        List<MappingIssue> issues = new ArrayList<>();
        for (Map.Entry<DictionaryKey, Map<SourceKey, Long>> entry : grouped.entrySet()) {
            DictionaryKey key = entry.getKey();
            try {
                DictSyncResult result = syncDictionary(caller, key, entry.getValue().keySet());
                revisions.put(key.auditCode(), (long) result.effective().dictionary().revision());
                Map<String, String> active = activeMappings(result);
                entry.getValue().forEach((value, count) -> {
                    if (!active.containsKey(sourceItemCode(key.dictionaryCode(), value.value()))) {
                        issues.add(new MappingIssue(key.dictionaryCode(), value.fieldCode(),
                                value.value(), count));
                    }
                });
            } catch (RuntimeException error) {
                revisions.put(key.auditCode(), -1L);
                entry.getValue().forEach((value, count) -> issues.add(new MappingIssue(
                        key.dictionaryCode(), value.fieldCode(), value.value(), count)));
                log.warn("业务字典批量补齐不可用 tenantId={} sourceType={} dictionaryCode={} errorType={} reason={}",
                        caller.tenantId(), text(sourceType), key.dictionaryCode(),
                        error.getClass().getSimpleName(), oneLine(error.getMessage()));
            }
        }
        issues.sort(Comparator.comparing(MappingIssue::dictionaryCode)
                .thenComparing(MappingIssue::fieldCode).thenComparing(MappingIssue::sourceValue));
        long unmapped = issues.stream().mapToLong(MappingIssue::count).sum();
        log.info("业务字典批处理完成 tenantId={} sourceType={} dictionaryCount={} observedCount={} unmappedCount={}",
                caller.tenantId(), text(sourceType), grouped.size(), source.size(), unmapped);
        return new Audit(unmapped, revisions, issues);
    }

    /** 为领域服务构造稳定、最小权限的租户服务身份。 */
    public static CallerIdentity serviceCaller(String serviceName, String role, UUID tenantId) {
        if (serviceName == null || serviceName.isBlank()) throw new IllegalArgumentException("serviceName不能为空");
        if (tenantId == null) throw new IllegalArgumentException("tenantId不能为空");
        UUID serviceId = UUID.nameUUIDFromBytes(("service:" + serviceName).getBytes(StandardCharsets.UTF_8));
        return new CallerIdentity("SERVICE", serviceId, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(text(role)), Set.of(SYNC_PERMISSION));
    }

    private DictSyncResult syncDictionary(CallerIdentity caller, DictionaryKey key,
                                          Collection<SourceKey> values) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(BusinessDictionaryInternalApi.BASE_PATH).path("/items/sync")
                .build().encode().toUri();
        Map<String, String> context = signedHeaders("POST", uri, caller);
        DictSyncCommand command = new DictSyncCommand(key.dictionaryCode(), values.stream()
                .map(value -> new DictSourceValue(value.value(), value.sourceName())).toList());
        ApiResponse<DictSyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .headers(headers -> context.forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(command).retrieve().body(new ParameterizedTypeReference<>() { });
        if (response == null || !"OK".equals(response.code()) || response.data() == null
                || response.data().effective() == null) {
            throw new IllegalStateException("公共业务字典返回空响应: " + key.auditCode());
        }
        return response.data();
    }

    private Map<String, String> activeMappings(DictSyncResult result) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (DictItemView item : result.effective().items()) {
            String previous = mappings.putIfAbsent(item.dictionaryItemCode(), item.dictionaryItemCode());
            if (previous != null && !previous.equals(item.dictionaryItemCode())) {
                throw new IllegalStateException("公共业务字典存在重复来源值: "
                        + result.effective().dictionary().dictionaryCode());
            }
        }
        return mappings;
    }

    private static String sourceItemCode(String dictionaryCode, String sourceValue) {
        String normalized = upper(sourceValue);
        if (normalized != null && CODE.matcher(normalized).matches()) return normalized;
        return autoCode(dictionaryCode, sourceValue);
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

    private Map<DictionaryKey, Map<SourceKey, Long>> group(List<Observation> source) {
        Map<DictionaryKey, Map<String, SourceAccumulator>> grouped = new LinkedHashMap<>();
        for (Observation item : source) {
            DictionaryKey key = new DictionaryKey(required(item.dictionaryCode(), "dictionaryCode"));
            Map<String, SourceAccumulator> values = grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            values.compute(item.sourceValue(), (ignored, current) -> current == null
                    ? new SourceAccumulator(item.fieldCode(), item.sourceName(), 1)
                    : current.add(item.fieldCode(), item.sourceName(), item.sourceValue()));
        }
        Map<DictionaryKey, Map<SourceKey, Long>> result = new LinkedHashMap<>();
        grouped.forEach((key, values) -> {
            Map<SourceKey, Long> counts = new LinkedHashMap<>();
            values.forEach((value, item) -> counts.put(
                    new SourceKey(item.fieldCode, value, item.sourceName), item.count));
            result.put(key, counts);
        });
        return result;
    }

    private Map<String, String> signedHeaders(String method, URI uri, CallerIdentity caller) {
        Map<String, String> headers = new LinkedHashMap<>();
        put(headers, RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        put(headers, RequestHeaders.PRINCIPAL_ID, caller.principalId());
        put(headers, RequestHeaders.TENANT_ID, caller.tenantId());
        put(headers, RequestHeaders.SESSION_ID, caller.sessionId());
        put(headers, RequestHeaders.SESSION_VERSION, caller.sessionVersion());
        put(headers, RequestHeaders.USER_SECURITY_VERSION, caller.userSecurityVersion());
        put(headers, RequestHeaders.TENANT_POLICY_VERSION, caller.tenantPolicyVersion());
        put(headers, RequestHeaders.ROLES, joined(caller.roles()));
        put(headers, RequestHeaders.PERMISSIONS, joined(caller.permissions()));
        TrustedContextSigner.SignedContext signed = signer.sign(
                method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private static void requireCaller(CallerIdentity caller) {
        if (caller == null || caller.tenantId() == null || !"SERVICE".equals(caller.principalScope())
                || (!caller.permissions().contains(SYNC_PERMISSION) && !caller.permissions().contains("*:*:*"))) {
            throw new IllegalArgumentException("字典批处理必须使用带租户和同步权限的服务身份");
        }
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("公共业务设置服务地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("公共业务设置服务地址必须使用http或https");
        }
        return uri;
    }

    private static void put(Map<String, String> target, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(name, String.valueOf(value));
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }

    private static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        return value.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : value.strip();
    }

    private static String upper(String value) {
        String normalized = value == null ? null : value.trim();
        return normalized == null || normalized.isEmpty() ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }

    /** 领域服务显式声明的单个白名单字段观察值。 */
    public record Observation(String dictionaryCode, String fieldCode, String sourceValue, String sourceName) {
        boolean hasValue() { return sourceValue != null && !sourceValue.isBlank(); }
    }

    /** 未能从补齐后的有效字典中精确解析的来源值。 */
    public record MappingIssue(String dictionaryCode, String fieldCode, String sourceValue, long count) { }

    /** 单个同步批次的字典快照审计。 */
    public record Audit(long unmapped, Map<String, Long> revisions, List<MappingIssue> issues) {
        public Audit {
            if (unmapped < 0) throw new IllegalArgumentException("unmapped不能小于0");
            revisions = revisions == null ? Map.of() : Map.copyOf(revisions);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
        public static Audit empty() { return new Audit(0, Map.of(), List.of()); }
    }

    private record DictionaryKey(String dictionaryCode) {
        String auditCode() { return dictionaryCode; }
    }

    private record SourceKey(String fieldCode, String value, String sourceName) { }

    private static final class SourceAccumulator {
        private final String fieldCode;
        private String sourceName;
        private long count;
        private boolean nameConflict;

        private SourceAccumulator(String fieldCode, String sourceName, long count) {
            this.fieldCode = text(fieldCode);
            this.sourceName = sourceName == null || sourceName.isBlank() ? null : sourceName.strip();
            this.count = count;
        }

        private SourceAccumulator add(String nextField, String nextName, String sourceValue) {
            count++;
            String normalized = nextName == null || nextName.isBlank() ? null : nextName.strip();
            if (!nameConflict && normalized != null) {
                if (sourceName == null) sourceName = normalized;
                else if (!sourceName.equals(normalized)) {
                    sourceName = sourceValue;
                    nameConflict = true;
                }
            }
            return this;
        }
    }
}
