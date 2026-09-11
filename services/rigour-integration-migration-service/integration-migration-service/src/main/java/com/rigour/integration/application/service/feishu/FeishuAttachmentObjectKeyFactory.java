package com.rigour.integration.application.service.feishu;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 飞书导入附件对象 Key 生成策略；按来源表、来源单号和字段隔离。 */
public final class FeishuAttachmentObjectKeyFactory {
    private final String objectPrefix;

    public FeishuAttachmentObjectKeyFactory(String objectPrefix) {
        this.objectPrefix = normalizePrefix(objectPrefix);
    }

    public String generate(String tenantId, String tableCode, String sourceDocumentNo,
                           String fieldCode, byte[] content, String fileName, String contentType) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("飞书附件内容不能为空");
        }
        return tenantId + "/" + objectPrefix + "/" + safeKey(tableCode)
                + "/" + safeKey(sourceDocumentNo) + "/" + safeKey(fieldCode)
                + "/" + sha256(content) + extension(fileName, contentType, content);
    }

    public boolean isGeneratedForTenant(String tenantId, String objectKey) {
        return tenantId != null && objectKey != null
                && objectKey.startsWith(tenantId.strip() + "/" + objectPrefix + "/");
    }

    static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.strip();
        if (!value.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException("飞书附件 COS object-prefix 必须是安全的相对路径");
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

    private static String extension(String fileName, String contentType, byte[] content) {
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
            default -> extensionFromContent(content);
        };
    }

    private static String extensionFromContent(byte[] content) {
        if (content == null || content.length < 4) return ".bin";
        int b0 = content[0] & 0xff;
        int b1 = content[1] & 0xff;
        int b2 = content[2] & 0xff;
        int b3 = content[3] & 0xff;
        if (b0 == 0xff && b1 == 0xd8 && b2 == 0xff) return ".jpg";
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4e && b3 == 0x47) return ".png";
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return ".gif";
        if (content.length >= 12
                && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && (content[8] & 0xff) == 0x57 && (content[9] & 0xff) == 0x45
                && (content[10] & 0xff) == 0x42 && (content[11] & 0xff) == 0x50) {
            return ".webp";
        }
        if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) return ".pdf";
        return ".bin";
    }
}
