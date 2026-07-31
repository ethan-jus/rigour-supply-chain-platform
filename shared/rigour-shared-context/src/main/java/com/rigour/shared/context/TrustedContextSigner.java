package com.rigour.shared.context;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
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
        try {
            rejectUnknownContextHeaders(request);
            String keyId = required(request.getHeader(RequestHeaders.CONTEXT_KEY_ID));
            long timestamp = Long.parseLong(required(request.getHeader(RequestHeaders.CONTEXT_TIMESTAMP)));
            long age = Math.abs(clock.millis() - timestamp);
            if (age > properties.getMaximumAge().toMillis()) return false;
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
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
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
                .append(query == null ? "" : query).append('\n');
        for (String name : RequestHeaders.SIGNED_CONTEXT_HEADERS) {
            value.append(name.toLowerCase(Locale.ROOT)).append(':')
                    .append(headers.getOrDefault(name, "")).append('\n');
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
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

    private static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Signed context field is missing");
        return value.strip();
    }

    public record SignedContext(String keyId, String timestamp, String signature) { }
}
