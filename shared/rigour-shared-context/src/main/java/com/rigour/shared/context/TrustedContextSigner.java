package com.rigour.shared.context;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 使用HMAC-SHA-256绑定请求方法、路径和身份头，防止绕过Gateway伪造内部上下文。 */
public final class TrustedContextSigner {
    private static final String VERSION = "v1";
    private final ContextTrustProperties properties;
    private final Clock clock;

    public TrustedContextSigner(ContextTrustProperties properties) {
        this(properties, Clock.systemUTC());
    }

    TrustedContextSigner(ContextTrustProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void requireSigningConfiguration() { properties.requireActiveKey(); }

    /**
     * 返回当前HMAC密钥的不可逆指纹，只用于确认Gateway与下游服务是否加载了同一份Secret。
     * 指纹不是密钥，不能用于签名或还原密钥。
     */
    public String activeKeyFingerprint() {
        return fingerprint(properties.requireActiveKey());
    }

    public String activeKeyId() { return properties.getActiveKeyId(); }

    public long maximumAgeMillis() { return properties.getMaximumAge().toMillis(); }

    public SignedContext sign(HttpServletRequest request, Map<String, String> headers) {
        String keyId = properties.getActiveKeyId();
        long timestamp = clock.millis();
        Map<String, String> normalized = normalize(headers);
        validateSize(normalized);
        String signature = encode(mac(properties.requireActiveKey(), canonical(
                request.getMethod(), request.getRequestURI(), request.getQueryString(), keyId, timestamp, normalized)));
        return new SignedContext(keyId, Long.toString(timestamp), signature);
    }

    public boolean hasContextHeaders(HttpServletRequest request) {
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            if (names.nextElement().toLowerCase(Locale.ROOT).startsWith("x-rigour-")) return true;
        }
        return false;
    }

    public boolean verify(HttpServletRequest request) {
        return verifyDetailed(request).valid();
    }

    /** 对外只暴露稳定失败类型，不记录密钥、签名或身份头原值。 */
    public VerificationResult verifyDetailed(HttpServletRequest request) {
        String keyId = null;
        long ageMillis = -1;
        try {
            rejectUnknownContextHeaders(request);
            keyId = required(request.getHeader(RequestHeaders.CONTEXT_KEY_ID));
            long timestamp = Long.parseLong(required(request.getHeader(RequestHeaders.CONTEXT_TIMESTAMP)));
            ageMillis = Math.abs(clock.millis() - timestamp);
            if (ageMillis > properties.getMaximumAge().toMillis()) {
                return VerificationResult.rejected("TIMESTAMP_OUT_OF_WINDOW", keyId, ageMillis);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : RequestHeaders.SIGNED_CONTEXT_HEADERS) {
                String value = request.getHeader(name);
                if (value != null && !value.isBlank()) headers.put(name, value.strip());
            }
            headers = normalize(headers);
            validateSize(headers);
            byte[] actual = decode(required(request.getHeader(RequestHeaders.CONTEXT_SIGNATURE)));
            byte[] expected = mac(properties.requireKey(keyId), canonical(
                    request.getMethod(), request.getRequestURI(), request.getQueryString(), keyId, timestamp, headers));
            return MessageDigest.isEqual(expected, actual)
                    ? VerificationResult.accepted(keyId, ageMillis)
                    : VerificationResult.rejected("SIGNATURE_MISMATCH", keyId, ageMillis);
        } catch (IllegalArgumentException exception) {
            return VerificationResult.rejected("INVALID_CONTEXT_FORMAT", keyId, ageMillis);
        } catch (IllegalStateException exception) {
            return VerificationResult.rejected("KEY_CONFIGURATION_INVALID", keyId, ageMillis);
        }
    }

    private void rejectUnknownContextHeaders(HttpServletRequest request) {
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.toLowerCase(Locale.ROOT).startsWith("x-rigour-")
                    && RequestHeaders.ALL_CONTEXT_HEADERS.stream().noneMatch(value -> value.equalsIgnoreCase(name))) {
                throw new IllegalArgumentException("Unknown context header");
            }
        }
    }

    private Map<String, String> normalize(Map<String, String> headers) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String name : RequestHeaders.SIGNED_CONTEXT_HEADERS) {
            String value = headers.get(name);
            if (value == null || value.isBlank()) continue;
            String clean = value.strip();
            if (clean.indexOf('\r') >= 0 || clean.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Context header contains a line break");
            }
            normalized.put(name, clean);
        }
        validateCount(normalized.get(RequestHeaders.ROLES), properties.getMaximumRoles());
        validateCount(normalized.get(RequestHeaders.PERMISSIONS), properties.getMaximumPermissions());
        return Map.copyOf(normalized);
    }

    private void validateCount(String csv, int maximum) {
        if (csv != null && csv.split(",", -1).length > maximum) {
            throw new IllegalArgumentException("Context collection is too large");
        }
    }

    private void validateSize(Map<String, String> headers) {
        int bytes = headers.entrySet().stream().mapToInt(entry ->
                entry.getKey().getBytes(StandardCharsets.UTF_8).length
                        + entry.getValue().getBytes(StandardCharsets.UTF_8).length).sum();
        if (bytes > properties.getMaximumHeaderBytes()) {
            throw new IllegalArgumentException("Trusted context is too large");
        }
    }

    private static byte[] canonical(String method, String path, String query, String keyId, long timestamp,
                                    Map<String, String> headers) {
        StringBuilder value = new StringBuilder(VERSION).append('\n').append(keyId).append('\n')
                .append(timestamp).append('\n').append(method).append('\n').append(path).append('\n')
                .append(canonicalQuery(query)).append('\n');
        for (String name : RequestHeaders.SIGNED_CONTEXT_HEADERS) {
            value.append(name.toLowerCase(Locale.ROOT)).append(':')
                    .append(headers.getOrDefault(name, "")).append('\n');
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 按查询参数语义生成稳定表示，避免Gateway转发时重排参数或规范化百分号编码导致误判篡改。
     * 参数名排序；同名参数的值顺序保留，因此真正修改参数名、值或同名值顺序仍会破坏签名。
     */
    private static String canonicalQuery(String query) {
        if (query == null || query.isEmpty()) return "";
        List<QueryParameter> parameters = new ArrayList<>();
        for (String pair : query.split("&", -1)) {
            int separator = pair.indexOf('=');
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            parameters.add(new QueryParameter(decodeQueryComponent(rawName), decodeQueryComponent(rawValue)));
        }
        // List.sort是稳定排序：只规范化参数名顺序，不改变同名参数值的业务顺序。
        parameters.sort(Comparator.comparing(QueryParameter::name));
        return parameters.stream()
                .map(parameter -> encode(parameter.name()) + ':' + encode(parameter.value()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String decodeQueryComponent(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static byte[] mac(byte[] key, byte[] content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(content);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String encode(String value) { return encode(value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Signed context field is missing");
        return value.strip();
    }

    public record SignedContext(String keyId, String timestamp, String signature) { }

    private record QueryParameter(String name, String value) { }

    /** 可安全写入日志的验签结果；不包含任何原始身份头或密钥材料。 */
    public record VerificationResult(boolean valid, String reason, String keyId, long ageMillis) {
        static VerificationResult accepted(String keyId, long ageMillis) {
            return new VerificationResult(true, "OK", keyId, ageMillis);
        }

        static VerificationResult rejected(String reason, String keyId, long ageMillis) {
            return new VerificationResult(false, reason, keyId, ageMillis);
        }
    }
}
