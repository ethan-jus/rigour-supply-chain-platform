package com.rigour.integration.application.service.feishu;

import com.rigour.erp.api.v1.model.ExternalProductRowCommand;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** 飞书产品信息库导出行到 ERP 商品同步命令的映射器。 */
final class FeishuErpProductImportMapper {
    static final String TABLE_CODE = "FEISHU_PRODUCT";
    private static final String SOURCE_TENANT_KEY = "FEISHU_PRODUCT";

    private final ObjectMapper objectMapper;

    FeishuErpProductImportMapper() {
        this(new ObjectMapper());
    }

    FeishuErpProductImportMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProjectionPlan plan(StoredRawRow row) {
        Map<String, String> values = row.values();
        String sourceId = text(first(row.sourceDocumentNo(),
                value(values, "产品编码", "商品编码", "产品编码名称", "ID")), 128);
        String productName = text(first(value(values, "产品名称", "商品名称", "产品编码名称"), sourceId), 200);
        if (sourceId == null || productName == null) {
            return ProjectionPlan.waiting("FEISHU_ERP_PRODUCT_MAPPING_REQUIRED",
                    "缺少产品名称或来源产品标识");
        }
        String specification = text(value(values, "规格", "产品规格"), 500);
        Instant sourceCreatedAt = first(row.sourceCreatedAt(), instant(value(values, "创建时间", "新建时间")));
        if (sourceCreatedAt == null) {
            return ProjectionPlan.waiting("FEISHU_SOURCE_CREATED_AT_REQUIRED",
                    "缺少飞书创建时间，不能按源时间生成商品编码");
        }
        ExternalProductRowCommand command = new ExternalProductRowCommand(
                null,
                SOURCE_TENANT_KEY,
                sourceId,
                text(first(row.sourceDocumentNo(), sourceId), 128),
                productName,
                text(value(values, "业务线"), 120),
                text(value(values, "品牌"), 120),
                text(value(values, "行业"), 120),
                text(value(values, "品类", "产品分类"), 120),
                specification,
                unitCode(value(values, "单位", "计量单位"), specification),
                decimal(value(values, "定价", "售价", "销售价")),
                decimal(value(values, "市场价")),
                decimal(value(values, "采购价", "成本价")),
                text(value(values, "状态"), 80),
                sourceCreatedAt,
                instant(value(values, "修改时间", "有效截至日期")),
                payloadHash(values),
                payloadJson(values));
        return ProjectionPlan.project(command);
    }

    private String payloadHash(Map<String, String> values) {
        try {
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(values == null ? Map.of() : values));
            MessageDigest digest = sha256Digest();
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (RuntimeException exception) {
            MessageDigest digest = sha256Digest();
            digest.update(String.valueOf(values).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private String payloadJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(values == null ? Map.of() : values));
        } catch (RuntimeException exception) {
            return "{}";
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private static BigDecimal decimal(String value) {
        String text = text(value, 64);
        if (text == null) return null;
        try {
            String normalized = text.replace(",", "")
                    .replace("¥", "")
                    .replace("￥", "")
                    .replace("元", "")
                    .strip();
            BigDecimal parsed = new BigDecimal(normalized);
            return parsed.compareTo(BigDecimal.ZERO) >= 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Instant instant(String value) {
        return FeishuSalesOrderImportMapper.sourceCreatedAt(Map.of("创建时间", value == null ? "" : value));
    }

    private static String unitCode(String explicitUnit, String specification) {
        String value = first(text(explicitUnit, 32), text(specification, 120));
        if (value == null) return null;
        if (value.contains("箱")) return "BOX";
        if (value.contains("桶")) return "BUCKET";
        if (value.contains("瓶")) return "BOTTLE";
        if (value.contains("条")) return "STRIP";
        if (value.contains("颗")) return "GRAIN";
        if (value.contains("份")) return "PORTION";
        if (value.contains("套")) return "SET";
        String upper = value.toUpperCase(Locale.ROOT);
        return upper.matches("[A-Z][A-Z0-9_]{0,63}") ? upper : null;
    }

    private static String value(Map<String, String> values, String... names) {
        for (String name : names) {
            String value = values.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value instanceof String text && text.isBlank()) continue;
            if (value != null) return value;
        }
        return null;
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    record ProjectionPlan(
            String status,
            String errorCode,
            String message,
            ExternalProductRowCommand command) {
        static ProjectionPlan waiting(String errorCode, String message) {
            return new ProjectionPlan("WAITING_MAPPING", errorCode, message, null);
        }

        static ProjectionPlan project(ExternalProductRowCommand command) {
            return new ProjectionPlan("PENDING", null, null, command);
        }
    }
}
