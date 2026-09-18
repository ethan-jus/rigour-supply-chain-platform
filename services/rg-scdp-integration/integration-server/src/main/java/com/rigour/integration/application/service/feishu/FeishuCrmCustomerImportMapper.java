package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** 飞书商家/门店导出行到 CRM 客户同步命令的映射器。 */
final class FeishuCrmCustomerImportMapper {
    static final String CUSTOMER_TABLE_CODE = "FEISHU_CUSTOMER";
    static final String STORE_TABLE_CODE = "FEISHU_STORE";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"));
    private static final List<DateTimeFormatter> LOCAL_DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"));

    private final ObjectMapper objectMapper;

    FeishuCrmCustomerImportMapper() {
        this(new ObjectMapper());
    }

    FeishuCrmCustomerImportMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProjectionPlan plan(StoredRawRow row) {
        Map<String, String> values = row.values();
        String sourceId = text(first(row.sourceDocumentNo(),
                value(values, "商家编号", "门店编码", "客户编码", "客户编号", "门店编号",
                        "商家ID", "门店ID", "客户ID", "商家编号名称", "门店编码名称",
                        "客户编码名称", "客户编号名称", "ID")), 128);
        String customerName = text(first(
                value(values, "门店名称", "商家名称", "客户名称", "客户", "门店", "商户名称",
                        "商户", "关联商家", "商家编号名称", "门店编码名称", "客户编码名称",
                        "客户编号名称"),
                sourceId), 200);
        if (sourceId == null || customerName == null) {
            return ProjectionPlan.waiting("FEISHU_CRM_CUSTOMER_MAPPING_REQUIRED",
                    "缺少商家/门店名称或来源标识");
        }
        String addressBlock = value(values, "默认收件地址", "默认地址", "收件地址", "门店默认地址");
        Instant sourceCreatedAt = firstInstant(row.sourceCreatedAt(),
                value(values, "创建时间", "合作日期", "签约日期"));
        if (sourceCreatedAt == null) {
            return ProjectionPlan.waiting("FEISHU_SOURCE_CREATED_AT_REQUIRED",
                    "缺少飞书创建时间/合作日期，不能按源时间生成客户编码");
        }
        ExternalCrmCustomerRowCommand command = new ExternalCrmCustomerRowCommand(
                null,
                sourceTenantKey(row.tableCode()),
                sourceId,
                text(first(row.sourceDocumentNo(), sourceId), 128),
                customerName,
                text(first(value(values, "对接人", "联系人"), labeled(addressBlock, "收件人")), 100),
                text(first(value(values, "联系方式", "联系电话", "手机号"), labeled(addressBlock, "电话")), 50),
                text(first(value(values, "商家来源", "客户来源", "来源"), row.sheetName()), 120),
                text(value(values, "客户类型", "客户分类", "客户类型名称", "商家类型", "商户类型",
                        "门店类型", "门店分类", "门店属性", "商家类目", "行业", "业态",
                        "经营类型", "标签"), 120),
                text(value(values, "所属地区", "销售区域", "区域"), 80),
                text(value(values, "市", "城市", "意向城市"), 80),
                text(first(value(values, "公司地址", "客户地址", "门店地址", "收货地址", "地址",
                                "详细地址", "经营地址", "店铺地址", "地理位置"),
                        labeled(addressBlock, "地址"),
                        labeled(addressBlock, "详细地址"),
                        addressBlock), 1000),
                null,
                text(value(values, "对接BD", "销售", "销售人员", "业务员", "BD", "负责人"), 100),
                settlementType(value(values, "结算周期", "结算方式")),
                text(value(values, "合作状态", "门店状态", "营业状态", "状态"), 80),
                sourceCreatedAt,
                firstInstant(null, value(values, "修改时间", "到期日期")),
                payloadHash(values),
                payloadJson(values));
        return ProjectionPlan.project(command);
    }

    private static String sourceTenantKey(String tableCode) {
        return STORE_TABLE_CODE.equals(tableCode) ? STORE_TABLE_CODE : CUSTOMER_TABLE_CODE;
    }

    private static String settlementType(String value) {
        String text = text(value, 80);
        if (text == null) return null;
        if (text.startsWith("A")) return "A";
        if (text.startsWith("B")) return "B";
        return null;
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

    private static Instant firstInstant(Instant first, String... values) {
        if (first != null) return first;
        for (String value : values) {
            Instant instant = instant(value);
            if (instant != null) return instant;
        }
        return null;
    }

    private static Instant instant(String value) {
        String text = text(value, 64);
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, formatter).atZone(DEFAULT_ZONE).toInstant();
            } catch (DateTimeParseException ignored) {
            }
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_FORMATS) {
            try {
                return LocalDate.parse(text, formatter).atStartOfDay(DEFAULT_ZONE).toInstant();
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            double serial = Double.parseDouble(text);
            if (serial > 20_000 && serial < 80_000) {
                long days = (long) Math.floor(serial);
                long seconds = Math.round((serial - days) * 86_400D);
                return EXCEL_EPOCH.plusDays(days).atStartOfDay(DEFAULT_ZONE).plusSeconds(seconds).toInstant();
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static String labeled(String value, String label) {
        String text = text(value, 1000);
        if (text == null) return null;
        Matcher matcher = Pattern.compile("(?m)" + Pattern.quote(label) + "[:：]\\s*([^\\n\\r]+)")
                .matcher(value);
        return matcher.find() ? text(matcher.group(1), 1000) : null;
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
            ExternalCrmCustomerRowCommand command) {
        static ProjectionPlan waiting(String errorCode, String message) {
            return new ProjectionPlan("WAITING_MAPPING", errorCode, message, null);
        }

        static ProjectionPlan project(ExternalCrmCustomerRowCommand command) {
            return new ProjectionPlan("PENDING", null, null, command);
        }
    }
}
