package com.rigour.erp.infrastructure.integration;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** 生成与 Map 插入顺序无关的来源摘要；扩展字段仍保留在摘要中，避免静默丢失来源变化。 */
final class StablePayloadHasher {
    private StablePayloadHasher() { }

    static String sha256(ObjectMapper objectMapper, Object value) {
        try {
            Object decoded = objectMapper.readValue(objectMapper.writeValueAsBytes(value), Object.class);
            byte[] canonical = objectMapper.writeValueAsBytes(canonicalValue(decoded));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ERP来源摘要生成失败", exception);
        }
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(StablePayloadHasher::canonicalValue).toList();
        return value;
    }
}
