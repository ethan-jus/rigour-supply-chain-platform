package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 飞书销售订单/回款记录到 Order 回款记录命令的映射器。 */
final class FeishuSalesPaymentImportMapper {
    static final String PAYMENT_TABLE_CODE = "FEISHU_PAYMENT_RECORD";

    private static final String SOURCE_SYSTEM = "FEISHU";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern SOURCE_NO_DATE = Pattern.compile("20\\d{6}");
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

    PaymentProjectionPlan planFromSalesOrder(StoredRawRow row, SalesOrderDetailView order) {
        return planFromSalesOrder(row, order, FeishuSalesOrderImportMapper.MappingContext.empty());
    }

    PaymentProjectionPlan planFromSalesOrder(
            StoredRawRow row, SalesOrderDetailView order, FeishuSalesOrderImportMapper.MappingContext context) {
        if (row == null || paidAmount(row) == null) return PaymentProjectionPlan.none();
        return plan(row, order, true, context);
    }

    PaymentProjectionPlan plan(StoredRawRow row, SalesOrderDetailView order) {
        return plan(row, order, FeishuSalesOrderImportMapper.MappingContext.empty());
    }

    PaymentProjectionPlan plan(
            StoredRawRow row, SalesOrderDetailView order, FeishuSalesOrderImportMapper.MappingContext context) {
        return plan(row, order, false, context);
    }

    private PaymentProjectionPlan plan(StoredRawRow row, SalesOrderDetailView order,
                                       boolean optionalWhenNoEvidence,
                                       FeishuSalesOrderImportMapper.MappingContext context) {
        Map<String, String> values = row.values();
        List<String> missing = new ArrayList<>();
        String sourceOrderNo = FeishuSalesOrderImportMapper.sourceDocumentNo(values);
        if (sourceOrderNo == null && row.sourceDocumentNo() != null) {
            sourceOrderNo = FeishuSalesOrderImportMapper.sourceDocumentNo(Map.of("订单编号", row.sourceDocumentNo()));
        }
        if (sourceOrderNo == null) missing.add("销售订单来源单号");
        String sourcePaymentNo = sourcePaymentNo(values, sourceOrderNo, row.sourceDocumentNo());
        if (sourcePaymentNo == null) missing.add("回款来源单号");
        Long orderId = order == null ? null : order.id();
        if (orderId == null) missing.add("销售订单映射");
        Instant paymentTime = paymentTime(values, row.sourceCreatedAt());
        if (paymentTime == null) missing.add("回款日期");
        BigDecimal paidAmount = paidAmount(row);
        if (paidAmount == null) missing.add("回款金额");
        if (!missing.isEmpty()) {
            if (optionalWhenNoEvidence && !hasPaymentEvidence(row)) return PaymentProjectionPlan.none();
            return PaymentProjectionPlan.waiting(sourceOrderNo, sourcePaymentNo,
                    "FEISHU_PAYMENT_MAPPING_REQUIRED",
                    "缺少可自动建立的回款映射或必填字段：" + String.join("、", missing));
        }
        String rawPaymentMethod = value(values, "付款方式", "支付方式", "回款方式", "收款方式");
        String paymentMethodCode = paymentMethodCode(value(values, "付款方式编码", "paymentMethodCode"),
                rawPaymentMethod);
        String rawCollectorName = value(values, "回款人", "收款人", "销售", "业务员", "销售人员");
        String orderOwnerName = order == null
                ? null
                : firstNonBlank(order.ownerEmployeeNameSnapshot(), order.ownerSalesName());
        FeishuSalesOrderImportMapper.EmployeeMapping collectorEmployee =
                context(context).employee(rawCollectorName).orElse(null);
        String collectorName = firstNonBlank(rawCollectorName,
                collectorEmployee == null ? null : collectorEmployee.employeeName(),
                orderOwnerName);
        String collectorStaffCode = text(value(values, "回款员工编码", "收款员工编码", "销售员工编码", "销售工号"), 50);
        if (collectorStaffCode == null && collectorEmployee != null) {
            collectorStaffCode = text(collectorEmployee.employeeCode(), 50);
        }
        if (collectorStaffCode == null
                && order != null
                && (rawCollectorName == null || samePerson(rawCollectorName, orderOwnerName))) {
            collectorStaffCode = text(order.ownerEmployeeCode(), 50);
        }
        SalesPaymentRecordCommand command = new SalesPaymentRecordCommand(
                null,
                SOURCE_SYSTEM,
                sourcePaymentNo,
                orderId,
                collectorStaffCode,
                text(collectorName, 100),
                paymentTime,
                paymentMethodCode,
                paidAmount,
                voucherKeys(row),
                text(remark(values, rawPaymentMethod, paymentMethodCode, pendingVoucherRemark(row)), 1000),
                0);
        return PaymentProjectionPlan.project(sourceOrderNo, sourcePaymentNo, command);
    }

    static SalesPaymentRecordCommand commandWithRevision(SalesPaymentRecordCommand command, int revision) {
        return new SalesPaymentRecordCommand(command.connectorId(), command.sourceSystemCode(),
                command.sourceDocumentNo(), command.orderId(), command.collectorStaffCode(),
                command.collectorNameSnapshot(), command.paymentTime(), command.paymentMethodCode(),
                command.paidAmount(), command.voucherKeys(), command.remark(), revision);
    }

    static boolean samePayment(SalesPaymentRecordCommand command, SalesPaymentRecordDetailView existing) {
        if (command == null || existing == null) return false;
        return java.util.Objects.equals(command.sourceDocumentNo(), existing.sourceDocumentNo())
                && java.util.Objects.equals(command.orderId(), existing.orderId())
                && java.util.Objects.equals(command.collectorStaffCode(), existing.collectorStaffCode())
                && java.util.Objects.equals(command.collectorNameSnapshot(), existing.collectorNameSnapshot())
                && java.util.Objects.equals(command.paymentTime(), existing.paymentTime())
                && java.util.Objects.equals(command.paymentMethodCode(), existing.paymentMethodCode())
                && compare(command.paidAmount(), existing.paidAmount())
                && java.util.Objects.equals(command.voucherKeys(), existing.voucherKeys())
                && java.util.Objects.equals(command.remark(), existing.remark());
    }

    private static boolean compare(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    private static boolean hasPaymentEvidence(StoredRawRow row) {
        if (row == null) return false;
        Map<String, String> values = row.values();
        return explicitPaidAmount(values) != null
                || hasPaymentIndicator(row)
                || (genericPaidAmount(values) != null && hasPaymentIndicator(row));
    }

    private static boolean hasPaymentIndicator(StoredRawRow row) {
        if (row == null) return false;
        Map<String, String> values = row.values();
        return explicitPaymentDocumentNo(values) != null
                || paymentTime(values, null) != null
                || value(values, "付款方式", "支付方式", "回款方式", "收款方式") != null
                || hasPaymentAttachment(row);
    }

    private static String sourcePaymentNo(Map<String, String> values, String sourceOrderNo, String fallback) {
        String explicit = firstNonBlank(explicitPaymentDocumentNo(values), value(values, "来源单号"));
        String value = firstNonBlank(explicit, fallback, sourceOrderNo);
        return text(value, 128);
    }

    private static String explicitPaymentDocumentNo(Map<String, String> values) {
        return value(values, "回款编号", "回款单号", "收款编号", "收款单号",
                "付款编号", "付款单号", "流水号", "交易流水号");
    }

    private static Instant paymentTime(Map<String, String> values, Instant fallback) {
        Instant explicit = instant(value(values, "回款日期", "收款日期", "付款日期", "实际回款日期",
                "到账日期", "交易时间", "回款时间", "收款时间", "付款时间", "回款日期门店"));
        if (explicit != null) return explicit;
        explicit = instantFromSourceNo(value(values, "回款日期门店"));
        return explicit == null ? fallback : explicit;
    }

    private static BigDecimal paidAmount(StoredRawRow row) {
        if (row == null) return null;
        BigDecimal explicit = explicitPaidAmount(row.values());
        if (explicit != null) return explicit;
        return hasPaymentIndicator(row) ? genericPaidAmount(row.values()) : null;
    }

    private static BigDecimal explicitPaidAmount(Map<String, String> values) {
        return decimal(values, "实际回款额", "实际回款金额", "回款金额", "已回款金额",
                "收款金额", "付款金额", "已收金额", "实收金额", "本次回款", "到账金额",
                "收款合计", "已回款额", "已付金额", "回款合计", "收款总额", "回款总额");
    }

    private static BigDecimal genericPaidAmount(Map<String, String> values) {
        return decimal(values, "金额");
    }

    private static String paymentMethodCode(String explicitCode, String raw) {
        String code = codeValue(explicitCode);
        if (code != null) return code;
        String value = lower(raw);
        if (value == null) return null;
        if (value.contains("alipay") || value.contains("支付宝")) return "ALIPAY";
        if (value.contains("wechat") || value.contains("weixin") || value.contains("micro")
                || value.contains("微信")) return "WECHAT";
        if (value.contains("cash") || value.contains("现金")) return "CASH";
        if (value.contains("deposit") || value.contains("预存")) return "DEPOSIT";
        if (value.contains("credit") || value.contains("赊")) return "CREDIT";
        if (value.contains("bank") || value.contains("offline") || value.contains("transfer")
                || value.contains("netbank") || value.contains("银行") || value.contains("转账")
                || value.contains("对公")) {
            return "BANK_TRANSFER";
        }
        return "OTHER";
    }

    private static List<String> voucherKeys(StoredRawRow row) {
        if (row == null || row.attachmentRefs().isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : row.attachmentRefs().entrySet()) {
            if (!isVoucherField(entry.getKey())) continue;
            for (String value : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                String key = text(value, 500);
                if (key != null && isFeishuAttachmentObjectKey(key) && !result.contains(key)) result.add(key);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isFeishuAttachmentObjectKey(String value) {
        String key = text(value, 500);
        return key != null && (key.contains("/feishu-attachments/") || key.startsWith("feishu-attachments/"));
    }

    private static boolean hasPaymentAttachment(StoredRawRow row) {
        if (row == null || row.attachmentRefs().isEmpty()) return false;
        return row.attachmentRefs().entrySet().stream()
                .anyMatch(entry -> isVoucherField(entry.getKey())
                        && entry.getValue() != null && !entry.getValue().isEmpty());
    }

    private static boolean isVoucherField(String fieldName) {
        String name = fieldName == null ? "" : fieldName;
        return name.contains("凭证") || name.contains("附件") || name.contains("图片")
                || name.contains("照片") || name.contains("截图");
    }

    private static String remark(Map<String, String> values, String rawPaymentMethod, String paymentMethodCode,
                                 String pendingVoucherRemark) {
        List<String> parts = new ArrayList<>();
        String remark = text(value(values, "回款备注", "收款备注", "备注"), 800);
        if (remark != null) parts.add(remark);
        if (rawPaymentMethod != null && paymentMethodCode == null) {
            parts.add("飞书付款方式：" + rawPaymentMethod);
        }
        if (pendingVoucherRemark != null) parts.add(pendingVoucherRemark);
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private static String pendingVoucherRemark(StoredRawRow row) {
        List<String> refs = pendingVoucherRefs(row);
        return refs.isEmpty() ? null : "回款凭证待补录：" + String.join("、", refs);
    }

    private static List<String> pendingVoucherRefs(StoredRawRow row) {
        if (row == null || row.attachmentRefs().isEmpty()) return List.of();
        List<String> refs = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : row.attachmentRefs().entrySet()) {
            if (!isVoucherField(entry.getKey())) continue;
            for (String value : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                String ref = text(value, 120);
                if (ref != null && !isFeishuAttachmentObjectKey(ref) && !refs.contains(ref)) refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }

    private static BigDecimal decimal(Map<String, String> values, String... names) {
        String value = value(values, names);
        if (value == null) return null;
        try {
            BigDecimal parsed = new BigDecimal(value.replace(",", "")
                    .replace("¥", "")
                    .replace("￥", "")
                    .replace("元", "")
                    .strip());
            return parsed.compareTo(BigDecimal.ZERO) > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (value == null) return null;
        String text = value.strip();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text).atZone(DEFAULT_ZONE).toInstant();
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
        return instantFromSourceNo(text);
    }

    private static Instant instantFromSourceNo(String value) {
        String text = clean(value);
        if (text == null) return null;
        Matcher matcher = SOURCE_NO_DATE.matcher(text);
        if (!matcher.find()) return null;
        String date = matcher.group();
        try {
            return LocalDate.of(Integer.parseInt(date.substring(0, 4)),
                    Integer.parseInt(date.substring(4, 6)),
                    Integer.parseInt(date.substring(6, 8)))
                    .atStartOfDay(DEFAULT_ZONE)
                    .toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String codeValue(String value) {
        String text = text(value, 64);
        if (text == null) return null;
        String upper = text.toUpperCase(Locale.ROOT);
        return CODE.matcher(upper).matches() ? upper : null;
    }

    private static String lower(String value) {
        String text = clean(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static String value(Map<String, String> values, String... names) {
        if (values == null || names == null) return null;
        for (String name : names) {
            String value = values.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String normalized = value.replace('\r', ' ').replace('\n', ' ').strip();
        return normalized.isBlank() ? null : normalized;
    }

    private static String text(String value, int max) {
        String text = clean(value);
        if (text == null) return null;
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static FeishuSalesOrderImportMapper.MappingContext context(
            FeishuSalesOrderImportMapper.MappingContext context) {
        return context == null ? FeishuSalesOrderImportMapper.MappingContext.empty() : context;
    }

    private static boolean samePerson(String left, String right) {
        String normalizedLeft = clean(left);
        String normalizedRight = clean(right);
        if (normalizedLeft == null || normalizedRight == null) return false;
        return normalizedLeft.replaceAll("\\s+", "")
                .equalsIgnoreCase(normalizedRight.replaceAll("\\s+", ""));
    }

    record PaymentProjectionPlan(
            String status,
            String sourceOrderNo,
            String sourcePaymentNo,
            String errorCode,
            String message,
            SalesPaymentRecordCommand command) {
        static PaymentProjectionPlan none() {
            return new PaymentProjectionPlan("SKIPPED", null, null, null, "未检测到回款金额/日期/凭证，不生成回款", null);
        }

        static PaymentProjectionPlan waiting(String sourceOrderNo, String sourcePaymentNo,
                                             String errorCode, String message) {
            return new PaymentProjectionPlan("WAITING_MAPPING", sourceOrderNo, sourcePaymentNo,
                    errorCode, message, null);
        }

        static PaymentProjectionPlan project(String sourceOrderNo, String sourcePaymentNo,
                                             SalesPaymentRecordCommand command) {
            return new PaymentProjectionPlan("PENDING", sourceOrderNo, sourcePaymentNo,
                    null, null, command);
        }
    }
}
