package com.rigour.integration.application.service.dhb;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 订货宝资金附件对象 Key 生成策略；内容摘要保证同一附件重复同步幂等。 */
public final class DhbAttachmentObjectKeyFactory {
    private final String objectPrefix;

    public DhbAttachmentObjectKeyFactory(String objectPrefix) {
        this.objectPrefix = normalizePrefix(objectPrefix);
    }

    public String generate(String tenantId, String sourceDocumentNo, String sourceReference,
                           byte[] content, String fileName, String contentType) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("资金附件内容不能为空");
        }
        String source = sourceReference == null || sourceReference.isBlank()
                ? firstNonBlank(fileName, "attachment") : sourceReference;
        return tenantId + "/" + objectPrefix + "/" + safeKey(sourceDocumentNo)
                + "/" + shortHash(source) + "/" + sha256(content) + extension(fileName, contentType);
    }

    static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.strip();
        if (!value.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException("资金附件 COS object-prefix 必须是安全的相对路径");
        }
        return value;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static String shortHash(String text) {
        return sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static String safeKey(String value) {
        String text = value == null ? "unknown" : value.strip();
        StringBuilder result = new StringBuilder();
        for (char character : text.toCharArray()) {
            if (Character.isLetterOrDigit(character) || character == '-' || character == '_') {
                result.append(character);
            } else {
                result.append('_');
            }
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }

    private static String extension(String fileName, String contentType) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String suffix = fileName.substring(dot).toLowerCase(Locale.ROOT);
                if (suffix.length() <= 8 && suffix.matches("\\.[a-z0-9]+")) return suffix;
            }
        }
        return switch (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "application/pdf" -> ".pdf";
            default -> ".bin";
        };
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
