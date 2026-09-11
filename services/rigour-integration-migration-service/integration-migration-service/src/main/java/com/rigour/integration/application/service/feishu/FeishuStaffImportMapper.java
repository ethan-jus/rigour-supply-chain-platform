package com.rigour.integration.application.service.feishu;

import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** 飞书销售人员导出行到 HR 员工同步命令的映射器。 */
final class FeishuStaffImportMapper {
    static final String TABLE_CODE = "FEISHU_SALES_STAFF";
    private static final String SOURCE_TENANT_KEY = "FEISHU_SALES_STAFF";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final Pattern LEADING_DATE = Pattern.compile("^(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})");
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

    FeishuStaffImportMapper() {
        this(new ObjectMapper());
    }

    FeishuStaffImportMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProjectionPlan plan(StoredRawRow row) {
        Map<String, String> values = row.values();
        if (!hasMeaningfulValue(values)) {
            return ProjectionPlan.skipped("飞书人员空行已跳过");
        }
        String employeeName = text(value(values, "姓名", "销售", "销售人员", "业务员"), 128);
        if (employeeName == null) employeeName = nameFromSalesName(value(values, "销售姓名"));
        String sourceEmployeeId = text(firstNonBlank(row.sourceDocumentNo(),
                value(values, "销售姓名", "人员编号", "员工编号", "员工编码", "工号", "ID", "手机号")), 128);
        if (sourceEmployeeId == null && employeeName != null) sourceEmployeeId = text("NAME:" + employeeName, 128);
        if (employeeName == null || sourceEmployeeId == null) {
            return ProjectionPlan.waiting("FEISHU_STAFF_MAPPING_REQUIRED", "缺少人员姓名或来源人员标识");
        }
        Instant entryDate = instant(value(values, "入职日期"));
        Instant createDate = firstInstant(row.sourceCreatedAt(), entryDate,
                value(values, "创建时间", "新建时间"),
                leadingDate(value(values, "销售姓名")));
        if (createDate == null) {
            return ProjectionPlan.waiting("FEISHU_SOURCE_CREATED_AT_REQUIRED",
                    "缺少飞书创建时间/入职日期，不能按源时间生成员工编码");
        }
        Instant updateDate = firstInstant(null,
                value(values, "最后更新时间", "修改时间", "离职日期"));
        ExternalEmployeeRowCommand command = new ExternalEmployeeRowCommand(
                null,
                SOURCE_TENANT_KEY,
                sourceEmployeeId,
                text(value(values, "销售姓名", "账号", "账号名"), 128),
                employeeName,
                text(value(values, "岗位"), 80),
                text(value(values, "职位"), 120),
                text(value(values, "部门", "团队"), 128),
                text(value(values, "销售leader", "销售负责人", "直属leader"), 128),
                text(value(values, "销售区域", "区域"), 80),
                text(value(values, "城市"), 80),
                text(value(values, "手机号"), 32),
                text(value(values, "邮箱", "Email"), 128),
                text(value(values, "在职状态", "状态"), 32),
                entryDate,
                instant(value(values, "离职日期")),
                createDate,
                updateDate,
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

    private static Instant firstInstant(Instant first, String... values) {
        if (first != null) return first;
        for (String value : values) {
            Instant instant = instant(value);
            if (instant != null) return instant;
        }
        return null;
    }

    private static Instant firstInstant(Instant first, Instant second, String... values) {
        if (first != null) return first;
        if (second != null) return second;
        return firstInstant(null, values);
    }

    private static String leadingDate(String value) {
        String text = text(value, 128);
        if (text == null) return null;
        Matcher matcher = LEADING_DATE.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
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

    private static String value(Map<String, String> values, String... names) {
        for (String name : names) {
            String value = values.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String nameFromSalesName(String value) {
        String text = text(value, 128);
        if (text == null) return null;
        Matcher matcher = Pattern.compile("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}[-_\\s]*(.+)$").matcher(text);
        return matcher.find() ? text(matcher.group(1), 128) : null;
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (isPlaceholder(normalized)) return null;
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private static boolean hasMeaningfulValue(Map<String, String> values) {
        if (values == null || values.isEmpty()) return false;
        for (String value : values.values()) {
            if (text(value, 255) != null) return true;
        }
        return false;
    }

    private static boolean isPlaceholder(String value) {
        return "-".equals(value) || "--".equals(value) || "—".equals(value);
    }

    record ProjectionPlan(
            String status,
            String errorCode,
            String message,
            ExternalEmployeeRowCommand command) {
        static ProjectionPlan waiting(String errorCode, String message) {
            return new ProjectionPlan("WAITING_MAPPING", errorCode, message, null);
        }

        static ProjectionPlan skipped(String message) {
            return new ProjectionPlan("SKIPPED", null, message, null);
        }

        static ProjectionPlan project(ExternalEmployeeRowCommand command) {
            return new ProjectionPlan("PENDING", null, null, command);
        }
    }
}
