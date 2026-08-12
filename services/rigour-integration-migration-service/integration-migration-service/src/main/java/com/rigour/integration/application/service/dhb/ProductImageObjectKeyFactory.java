package com.rigour.integration.application.service.dhb;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 商品图片对象 Key 生成策略；前缀由 COS 配置注入，内容摘要保证同图幂等。 */
public final class ProductImageObjectKeyFactory {
    private final String objectPrefix;

    public ProductImageObjectKeyFactory(String objectPrefix) {
        this.objectPrefix = normalizePrefix(objectPrefix);
    }

    public String generate(String tenantId, String sourceProductId, String sourceResourceId,
                           Integer sortOrder, byte[] content, String fileName, String contentType) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("商品图片内容不能为空");
        }
        String sourceId = sourceResourceId == null || sourceResourceId.isBlank()
                ? "image-" + (sortOrder == null ? 0 : sortOrder) : sourceResourceId;
        return tenantId + "/" + objectPrefix + "/" + safeKey(sourceProductId)
                + "/" + safeKey(sourceId) + "/" + sha256(content) + extension(fileName, contentType);
    }

    static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.strip();
        if (!value.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException("商品图片 COS object-prefix 必须是安全的相对路径");
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
            default -> ".jpg";
        };
    }
}
