package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 飞书销售订单导入行到 Order 销售订单命令的映射器。 */
final class FeishuSalesOrderImportMapper {
    static final String SOURCE_SYSTEM = "FEISHU";
    static final String ORDER_TABLE_CODE = "FEISHU_SALES_ORDER";
    static final String ORDER_LINE_TABLE_CODE = "FEISHU_SALES_ORDER_LINE";
    static final String TABLE_CODE = ORDER_TABLE_CODE;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern COMPACT_SOURCE_NO = Pattern.compile("([A-Za-z]{1,12}\\d{6,})");
    private static final Pattern ORDER_LINE_TO_ORDER_NO = Pattern.compile("^O(DD\\d{6,})$", Pattern.CASE_INSENSITIVE);
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

    ProjectionPlan plan(StoredRawRow row) {
        return plan(row, MappingContext.empty());
    }

    ProjectionPlan plan(StoredRawRow row, MappingContext mappingContext) {
        MappingContext context = context(mappingContext);
        OrderHeaderPlan headerPlan = headerPlan(row, context);
        if (headerPlan.command() == null) {
            return new ProjectionPlan(headerPlan.status(), headerPlan.errorCode(), headerPlan.message(), null);
        }
        LineProjectionPlan linePlan = inlineOrderLinePlan(row, context);
        if (linePlan.command() == null) {
            return ProjectionPlan.project(commandWithLines(headerPlan.command(), List.of()));
        }
        return ProjectionPlan.project(commandWithLines(headerPlan.command(), List.of(linePlan.command())));
    }

    OrderHeaderPlan headerPlan(StoredRawRow row, MappingContext mappingContext) {
        Map<String, String> values = row.values();
        MappingContext context = context(mappingContext);
        List<String> blocking = new ArrayList<>();
        String sourceNo = sourceOrderNo(values, row.sourceDocumentNo());
        if (sourceNo == null) blocking.add("订单编号");
        if (sourceNo != null && quantityIsExplicitZero(values)) {
            return OrderHeaderPlan.skipped(sourceNo, "FEISHU_ORDER_RETURN_QUANTITY_ZERO_SKIPPED",
                    "飞书订单数量为0，按退货/冲销类单据跳过业务订单投影");
        }
        Instant sourceCreatedAt = businessOrderDate(values);
        if (sourceCreatedAt == null) sourceCreatedAt = row.sourceCreatedAt();
        if (sourceCreatedAt == null) sourceCreatedAt = sourceCreatedAt(values);
        if (sourceCreatedAt == null) sourceCreatedAt = instantFromSourceNo(sourceNo);
        if (sourceCreatedAt == null) blocking.add("创建时间/销售日期");
        Long customerId = longValue(values, "客户ID", "customerId", "内部客户ID");
        CustomerMapping customer = null;
        if (customerId == null) {
            customer = context.customer(value(values, "关联门店", "门店", "客户名称", "客户"),
                            value(values, "客户编码", "门店编码", "商家编号"),
                            linkedDisplayName(value(values, "订单编号门店", "销售订单", "关联订单", "关联销售订单")),
                            sourceNo)
                    .orElse(null);
            customerId = customer == null ? null : customer.customerId();
        }
        String customerName = firstNonBlank(value(values, "门店", "关联门店", "客户名称", "客户"),
                linkedDisplayName(value(values, "订单编号门店", "销售订单", "关联订单", "关联销售订单")),
                customer == null ? null : customer.customerName());
        String ownerName = value(values, "销售", "业务员", "销售人员");
        EmployeeMapping employee = context.employee(ownerName).orElse(null);
        String ownerEmployeeCode = firstNonBlank(value(values, "销售员工编码", "销售工号"),
                employee == null ? null : employee.employeeCode());
        String ownerEmployeeName = firstNonBlank(ownerName, employee == null ? null : employee.employeeName());
        if (sourceNo != null && customerName == null && !hasRecoverableOrderContent(values)) {
            return OrderHeaderPlan.skipped(sourceNo, "FEISHU_ORDER_DIRTY_BLANK_CUSTOMER",
                    "门店/客户为空，按飞书脏数据跳过");
        }
        if (!blocking.isEmpty()) {
            return OrderHeaderPlan.waiting(sourceNo, "FEISHU_ORDER_HEADER_MAPPING_REQUIRED",
                    "缺少销售订单落库必填字段（订单主表）："
                            + String.join("、", blocking));
        }
        String regionCode = firstNonBlank(
                codeValue(value(values, "区域编码", "regionCode")),
                context.regionCode(value(values, "城市", "市", "订单城市", "所属城市"),
                        value(values, "销售区域", "所属地区", "区域", "地区", "大区", "业务区域", "归属地区"))
                        .orElse(null));
        SalesOrderCommand command = new SalesOrderCommand(
                customerId,
                SOURCE_SYSTEM,
                text(sourceNo, 80),
                sourceStatusCode(values),
                text(value(values, "创建人ID", "创建人"), 80),
                text(value(values, "创建人员工编码", "创建人工号"), 50),
                text(value(values, "创建人"), 100),
                text(firstNonBlank(value(values, "客户编码", "门店编码", "商家编号"),
                        customer == null ? null : customer.customerCode()), 50),
                text(customerName, 200),
                text(value(values, "联系人", "收货人"), 100),
                text(value(values, "联系电话", "手机号", "电话"), 50),
                regionCode,
                null,
                text(ownerEmployeeName, 100),
                text(ownerEmployeeCode, 50),
                text(ownerEmployeeName, 100),
                sourceCreatedAt,
                codeValue(value(values, "订单类型编码", "orderTypeCode")),
                codeValue(value(values, "付款方式编码", "paymentMethodCode")),
                paymentVoucherKeys(row),
                sourceUnpaidAmount(values),
                null,
                orderDiscountAmount(values),
                text(orderRemark(values, row), 1000),
                List.of(),
                false,
                0);
        return OrderHeaderPlan.project(sourceNo, command);
    }

    LineProjectionPlan linePlan(StoredRawRow row, MappingContext mappingContext) {
        Map<String, String> values = row.values();
        MappingContext context = context(mappingContext);
        List<String> blocking = new ArrayList<>();
        String parentSourceNo = sourceOrderNo(values, isSalesOrderRow(row) ? row.sourceDocumentNo() : null);
        if (parentSourceNo == null && sourceNoCanBeParentOrder(row.sourceDocumentNo())) {
            parentSourceNo = normalizeSourceOrderNo(row.sourceDocumentNo());
        }
        if (parentSourceNo == null) {
            parentSourceNo = parentOrderNoFromLineSourceNo(row.sourceDocumentNo());
        }
        if (parentSourceNo == null) blocking.add("关联订单编号");
        String sourceLineNo = sourceLineNo(values, row.sourceDocumentNo());
        String explicitProductRef = value(values, "产品编号", "商品编码", "产品编码", "SKU编码",
                "商品编号", "产品编码名称", "产品编号名称");
        String orderProductRef = value(values, "订单产品", "产品名称", "商品名称", "产品", "商品");
        String specificationRef = firstNonBlank(value(values, "规格", "规格名称", "规格描述", "产品规格"),
                descriptorSpecification(explicitProductRef), descriptorSpecification(orderProductRef));
        boolean multiProductSummary = explicitProductRef == null && hasMultipleValues(orderProductRef);
        ProductMapping product = multiProductSummary ? null : context.product(explicitProductRef,
                        orderProductRef, specificationRef, linkedDisplayName(orderProductRef))
                .orElse(null);
        Long productId = longValue(values, "商品ID", "产品ID", "productId", "内部商品ID");
        if (productId == null && product != null) productId = product.productId();
        if (multiProductSummary) blocking.add("独立订单明细/商品");
        Long variantId = longValue(values, "规格ID", "商品规格ID", "productVariantId", "内部规格ID");
        if (variantId == null && product != null) variantId = product.productVariantId();
        String unitCode = codeValue(value(values, "单位编码", "unitCode", "内部单位编码"));
        if (unitCode == null && product != null) unitCode = codeValue(product.unitCode());
        BigDecimal quantity = decimal(values, "数量", "购买数量", "数量(箱)", "数量（箱）", "销售数量");
        if (quantity == null) blocking.add("数量");
        BigDecimal unitPrice = unitPrice(values, quantity);
        if (unitPrice == null) blocking.add("单价/小计");
        String productName = firstNonBlank(product == null ? null : product.productName(),
                descriptorProductName(orderProductRef),
                descriptorProductName(explicitProductRef), linkedDisplayName(explicitProductRef),
                orderProductRef, linkedDisplayName(orderProductRef));
        if (productName == null) blocking.add("订单产品/商品名称");
        if (!blocking.isEmpty()) {
            return LineProjectionPlan.waiting(parentSourceNo, sourceLineNo,
                    "FEISHU_ORDER_LINE_MAPPING_REQUIRED",
                    "缺少销售订单明细落库必填字段："
                            + String.join("、", blocking));
        }
        SalesOrderLineCommand line = new SalesOrderLineCommand(
                productId,
                variantId,
                text(firstNonBlank(value(values, "商品编码", "产品编码", "productCode"),
                        product == null ? null : product.productCode()), 128),
                text(firstNonBlank(value(values, "SKU编码", "规格编码", "skuCode"),
                        product == null ? null : product.variantCode()), 128),
                text(productName, 200),
                text(firstNonBlank(value(values, "规格", "规格名称", "规格描述"),
                        descriptorSpecification(explicitProductRef), descriptorSpecification(orderProductRef),
                        product == null ? null : product.specification()), 500),
                unitCode,
                quantity,
                unitPrice,
                null,
                lineDiscountAmount(values, quantity, unitPrice),
                text(value(values, "明细备注", "备注"), 1000));
        return LineProjectionPlan.project(parentSourceNo, sourceLineNo, line, lineAmount(values));
    }

    LineProjectionPlan inlineOrderLinePlan(StoredRawRow row, MappingContext mappingContext) {
        LineProjectionPlan linePlan = linePlan(row, mappingContext);
        if (linePlan.command() != null) return linePlan;
        SalesOrderLineCommand subtotalLine = subtotalPlaceholderLine(row, linePlan);
        if (subtotalLine == null) return linePlan;
        return LineProjectionPlan.project(linePlan.parentSourceOrderNo(), linePlan.sourceLineNo(),
                subtotalLine, lineAmount(row.values()));
    }

    static String sourceDocumentNo(Map<String, String> values) {
        return text(sourceOrderNo(values, null), 128);
    }

    static Instant sourceCreatedAt(Map<String, String> values) {
        Instant explicit = businessOrderDate(values);
        if (explicit != null) return explicit;
        explicit = instant(value(values, "创建时间"));
        if (explicit != null) return explicit;
        return instantFromSourceNo(sourceDocumentNo(values));
    }

    private static Instant businessOrderDate(Map<String, String> values) {
        return businessDateInstant(value(values, "销售日期", "下单时间", "订单时间"));
    }

    static String sourceOrderNo(Map<String, String> values, String fallback) {
        String explicit = value(values, "订单编号", "订单编号门店", "销售订单", "关联订单",
                "关联销售订单", "订单", "销售订单编号", "订单单号", "下单单号", "来源订单号", "来源订单");
        String normalized = normalizeSourceOrderNo(explicit);
        if (normalized != null) return normalized;
        return normalizeSourceOrderNo(fallback);
    }

    private static String sourceStatusCode(Map<String, String> values) {
        String value = value(values, "审核状态", "付款状态", "状态");
        if (value == null) return null;
        String text = value.strip();
        if (text.contains("已审核") || text.contains("通过")) return "APPROVED";
        if (text.contains("待审核") || text.contains("待审批")) return "PENDING";
        if (text.contains("驳回") || text.contains("拒绝")) return "REJECTED";
        if (text.contains("草稿")) return "DRAFT";
        String sanitized = "FEISHU_" + text.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (sanitized.length() > 64) sanitized = sanitized.substring(0, 64);
        return CODE.matcher(sanitized).matches() ? sanitized : null;
    }

    private static BigDecimal unitPrice(Map<String, String> values, BigDecimal quantity) {
        BigDecimal explicit = decimal(values, "单价", "销售单价", "实际单价", "设计单价", "unitPrice");
        if (explicit != null) return explicit;
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal amount = originalAmount(values);
        if (amount == null) amount = lineAmount(values);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) return null;
        return amount.divide(quantity, 6, RoundingMode.HALF_UP);
    }

    static BigDecimal lineAmount(Map<String, String> values) {
        BigDecimal payableAmount = payableAmount(values);
        if (payableAmount != null) return payableAmount;
        BigDecimal originalAmount = originalAmount(values);
        BigDecimal discountAmount = decimal(values, "订单优惠金额", "优惠金额", "折扣金额",
                "行优惠金额", "行折扣金额");
        if (originalAmount != null && discountAmount != null && originalAmount.compareTo(discountAmount) >= 0) {
            return originalAmount.subtract(discountAmount);
        }
        return originalAmount;
    }

    static BigDecimal headerPayableAmount(Map<String, String> values) {
        BigDecimal payableAmount = payableAmount(values);
        if (payableAmount != null) return payableAmount;
        BigDecimal originalAmount = originalAmount(values);
        BigDecimal discountAmount = decimal(values, "订单优惠金额", "优惠金额", "折扣金额");
        if (originalAmount != null && discountAmount != null && originalAmount.compareTo(discountAmount) >= 0) {
            return originalAmount.subtract(discountAmount);
        }
        return originalAmount;
    }

    private static BigDecimal payableAmount(Map<String, String> values) {
        return decimal(values, true, "实际小计", "应收小计", "明细金额", "商品金额", "销售金额", "金额");
    }

    private static BigDecimal originalAmount(Map<String, String> values) {
        return decimal(values, true, "小计", "原小计", "订单小计", "商品小计", "总价");
    }

    private static BigDecimal orderDiscountAmount(Map<String, String> values) {
        BigDecimal explicit = decimal(values, "订单优惠金额", "优惠金额", "折扣金额");
        if (explicit != null) return explicit;
        BigDecimal originalAmount = originalAmount(values);
        BigDecimal payableAmount = lineAmount(values);
        if (originalAmount == null || payableAmount == null || originalAmount.compareTo(payableAmount) <= 0) {
            return null;
        }
        return originalAmount.subtract(payableAmount);
    }

    static BigDecimal sourceUnpaidAmount(Map<String, String> values) {
        return signedDecimal(values, "待付金额", "待收金额", "未收金额");
    }

    private static boolean quantityIsExplicitZero(Map<String, String> values) {
        BigDecimal quantity = signedDecimal(values, "数量", "购买数量", "数量(箱)", "数量（箱）", "销售数量");
        return quantity != null && quantity.compareTo(BigDecimal.ZERO) == 0;
    }

    private static boolean hasRecoverableOrderContent(Map<String, String> values) {
        BigDecimal amount = lineAmount(values);
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0
                || decimal(values, "数量", "购买数量", "数量(箱)", "数量（箱）", "销售数量") != null
                || value(values, "订单产品", "产品名称", "商品名称", "产品", "商品") != null
                || decimal(values, "收款合计", "已付金额", "已回款额") != null;
    }

    private static SalesOrderLineCommand subtotalPlaceholderLine(StoredRawRow row, LineProjectionPlan linePlan) {
        if (!isSalesOrderRow(row) || linePlan == null) return null;
        Map<String, String> values = row.values();
        BigDecimal amount = lineAmount(values);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) return null;
        BigDecimal quantity = decimal(values, "数量", "购买数量", "数量(箱)", "数量（箱）", "销售数量");
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            if (amount.compareTo(BigDecimal.ZERO) <= 0) return null;
            quantity = BigDecimal.ONE;
        }
        BigDecimal originalAmount = originalAmount(values);
        if (originalAmount == null) originalAmount = amount;
        BigDecimal unitPrice = originalAmount.divide(quantity, 6, RoundingMode.HALF_UP);
        String productName = firstNonBlank(
                descriptorProductName(value(values, "订单产品", "产品名称", "商品名称", "产品", "商品")),
                value(values, "订单产品", "产品名称", "商品名称", "产品", "商品"),
                "待补齐商品");
        String remark = firstNonBlank(linePlan.message(), "飞书订单表实际小计占位，待补齐商品明细");
        return new SalesOrderLineCommand(null, null, null, null, text(productName, 200),
                null, null, quantity, unitPrice, null,
                lineDiscountAmount(values, quantity, unitPrice), text(remark, 1000));
    }

    private static boolean isSalesOrderRow(StoredRawRow row) {
        return row != null
                && (ORDER_TABLE_CODE.equals(row.tableCode())
                || ("ORDER".equals(row.domainCode()) && "SALES_ORDER".equals(row.objectType())));
    }

    private static BigDecimal lineDiscountAmount(Map<String, String> values, BigDecimal quantity,
                                                 BigDecimal unitPrice) {
        BigDecimal explicit = decimal(values, "优惠金额", "行优惠金额", "折扣金额", "行折扣金额");
        if (explicit != null) return explicit;
        if (quantity == null || unitPrice == null
                || quantity.compareTo(BigDecimal.ZERO) <= 0
                || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        BigDecimal amount = lineAmount(values);
        if (amount == null) return null;
        BigDecimal originalAmount = quantity.multiply(unitPrice);
        if (originalAmount.compareTo(amount) <= 0) return null;
        return originalAmount.subtract(amount);
    }

    private static Long longValue(Map<String, String> values, String... names) {
        String value = value(values, names);
        if (value == null) return null;
        String normalized = value.strip().replaceAll("\\.0+$", "");
        if (!normalized.matches("\\d+")) return null;
        try {
            long parsed = Long.parseLong(normalized);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal decimal(Map<String, String> values, String... names) {
        return decimal(values, false, names);
    }

    private static BigDecimal decimal(Map<String, String> values, boolean allowZero, String... names) {
        String value = value(values, names);
        if (value == null) return null;
        try {
            BigDecimal parsed = new BigDecimal(value.replace(",", "")
                    .replace("¥", "")
                    .replace("￥", "")
                    .replace("元", "")
                    .replace("%", "")
                    .strip());
            return parsed.compareTo(BigDecimal.ZERO) > 0
                    || (allowZero && parsed.compareTo(BigDecimal.ZERO) == 0) ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal signedDecimal(Map<String, String> values, String... names) {
        String value = value(values, names);
        if (value == null) return null;
        try {
            return new BigDecimal(value.replace(",", "")
                    .replace("¥", "")
                    .replace("￥", "")
                    .replace("元", "")
                    .replace("%", "")
                    .strip());
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
        return null;
    }

    private static Instant businessDateInstant(String value) {
        if (value == null) return null;
        String text = value.strip();
        for (DateTimeFormatter formatter : LOCAL_DATE_FORMATS) {
            try {
                return LocalDate.parse(text, formatter).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            double serial = Double.parseDouble(text);
            if (serial > 20_000 && serial < 80_000 && Math.floor(serial) == serial) {
                return EXCEL_EPOCH.plusDays((long) serial).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
        } catch (NumberFormatException ignored) {
        }
        return instant(text);
    }

    private static String codeValue(String value) {
        String text = text(value, 64);
        if (text == null) return null;
        String upper = text.toUpperCase(Locale.ROOT);
        return CODE.matcher(upper).matches() ? upper : null;
    }

    private static String sourceLineNo(Map<String, String> values, String fallback) {
        return text(firstNonBlank(value(values, "订单明细号", "明细编号", "明细号", "行编号",
                "来源明细号", "来源单号"), fallback), 128);
    }

    private static boolean sourceNoCanBeParentOrder(String sourceDocumentNo) {
        String value = clean(sourceDocumentNo);
        if (value == null) return false;
        String upper = value.toUpperCase(Locale.ROOT);
        return !upper.startsWith("ODD") && !upper.contains("明细");
    }

    private static String parentOrderNoFromLineSourceNo(String sourceDocumentNo) {
        String value = normalizeSourceOrderNo(sourceDocumentNo);
        if (value == null) return null;
        Matcher matcher = ORDER_LINE_TO_ORDER_NO.matcher(value);
        return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String normalizeSourceOrderNo(String value) {
        String text = clean(value);
        if (text == null) return null;
        Matcher matcher = COMPACT_SOURCE_NO.matcher(text);
        if (matcher.find()) return matcher.group(1).toUpperCase(Locale.ROOT);
        if (text.contains(" - ")) {
            String prefix = text.split("\\s+-\\s+", 2)[0].strip();
            if (!prefix.isBlank()) return prefix;
        }
        return text;
    }

    private static String linkedDisplayName(String value) {
        String text = clean(value);
        if (text == null) return null;
        if (text.contains(" - ")) {
            String[] parts = text.split("\\s+-\\s+", 2);
            return parts.length > 1 ? text(parts[1], 200) : null;
        }
        String sourceNo = normalizeSourceOrderNo(text);
        if (sourceNo != null && text.length() > sourceNo.length()) {
            String suffix = text.substring(sourceNo.length()).replaceFirst("^[-_：:\\s]+", "").strip();
            return suffix.isBlank() ? null : text(suffix, 200);
        }
        return null;
    }

    private static String descriptorProductName(String value) {
        List<String> parts = descriptorParts(value);
        if (parts.size() == 2) return clean(parts.get(0));
        if (parts.size() < 3) return null;
        StringBuilder builder = new StringBuilder();
        for (int index = 1; index < parts.size() - 1; index++) {
            if (builder.length() > 0) builder.append('-');
            builder.append(parts.get(index));
        }
        return clean(builder.toString());
    }

    private static String descriptorSpecification(String value) {
        List<String> parts = descriptorParts(value);
        return parts.size() >= 2 ? clean(parts.get(parts.size() - 1)) : null;
    }

    private static List<String> descriptorParts(String value) {
        String text = clean(value);
        if (text == null) return List.of();
        List<String> parts = new ArrayList<>();
        for (String part : text.split("\\s*[-－–—]\\s*")) {
            String cleaned = clean(part);
            if (cleaned != null) parts.add(cleaned);
        }
        return parts;
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

    static SalesOrderCommand commandWithLines(SalesOrderCommand command, List<SalesOrderLineCommand> lines) {
        return new SalesOrderCommand(command.customerId(), command.sourceSystemCode(), command.sourceOrderNo(),
                command.sourceStatusCode(), command.sourceCreatorId(), command.sourceCreatorStaffCode(),
                command.sourceCreatorName(), command.customerCodeSnapshot(), command.customerNameSnapshot(),
                command.contactNameSnapshot(), command.contactPhoneSnapshot(), command.regionCode(),
                command.ownerSalesUserId(), command.ownerSalesName(), command.ownerEmployeeCode(),
                command.ownerEmployeeNameSnapshot(), command.orderDate(), command.orderTypeCode(),
                command.paymentMethodCode(), command.paymentVoucherKeys(), command.sourceUnpaidAmount(),
                command.discountRate(), command.discountAmount(),
                command.remark(), lines, command.submit(), command.revision());
    }

    private static List<String> paymentVoucherKeys(StoredRawRow row) {
        if (row == null || row.attachmentRefs().isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : row.attachmentRefs().entrySet()) {
            if (!isPaymentVoucherField(entry.getKey())) continue;
            for (String value : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                String key = text(value, 500);
                if (key != null && isFeishuAttachmentObjectKey(key) && !result.contains(key)) result.add(key);
            }
        }
        return List.copyOf(result);
    }

    private static String orderRemark(Map<String, String> values, StoredRawRow row) {
        List<String> parts = new ArrayList<>();
        String remark = text(value(values, "备注", "订单备注"), 800);
        if (remark != null) parts.add(remark);
        String pendingVoucherRemark = pendingPaymentVoucherRemark(row);
        if (pendingVoucherRemark != null) parts.add(pendingVoucherRemark);
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private static String pendingPaymentVoucherRemark(StoredRawRow row) {
        List<String> refs = pendingPaymentVoucherRefs(row);
        return refs.isEmpty() ? null : "回款凭证待补录：" + String.join("、", refs);
    }

    private static List<String> pendingPaymentVoucherRefs(StoredRawRow row) {
        if (row == null || row.attachmentRefs().isEmpty()) return List.of();
        List<String> refs = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : row.attachmentRefs().entrySet()) {
            if (!isPaymentVoucherField(entry.getKey())) continue;
            for (String value : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                String ref = text(value, 120);
                if (ref != null && !isFeishuAttachmentObjectKey(ref) && !refs.contains(ref)) refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }

    private static boolean isPaymentVoucherField(String fieldName) {
        String name = fieldName == null ? "" : fieldName;
        return name.contains("付款凭证") || name.contains("支付凭证") || name.contains("回款凭证")
                || name.contains("收款凭证") || name.contains("付款附件") || name.contains("支付附件")
                || name.contains("回款附件") || name.contains("收款附件");
    }

    private static boolean isFeishuAttachmentObjectKey(String value) {
        String key = text(value, 500);
        return key != null && (key.contains("/feishu-attachments/") || key.startsWith("feishu-attachments/"));
    }

    private static MappingContext context(MappingContext mappingContext) {
        return mappingContext == null ? MappingContext.empty() : mappingContext;
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
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private static boolean hasMultipleValues(String value) {
        String text = text(value, 2000);
        if (text == null) return false;
        return text.split("[,，;；\\n\\r]+").length > 1;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    interface MappingContext {
        Optional<CustomerMapping> customer(String... references);

        Optional<ProductMapping> product(String... references);

        default Optional<EmployeeMapping> employee(String... references) {
            return Optional.empty();
        }

        default Optional<String> regionCode(String... references) {
            return Optional.empty();
        }

        static MappingContext empty() {
            return new MappingContext() {
                @Override
                public Optional<CustomerMapping> customer(String... references) {
                    return Optional.empty();
                }

                @Override
                public Optional<ProductMapping> product(String... references) {
                    return Optional.empty();
                }
            };
        }
    }

    record CustomerMapping(Long customerId, String customerCode, String customerName) {
    }

    record ProductMapping(Long productId, Long productVariantId, String productCode,
                          String variantCode, String productName, String specification,
        String unitCode) {
    }

    record EmployeeMapping(String employeeCode, String employeeName) {
    }

    record OrderHeaderPlan(
            String status,
            String sourceOrderNo,
            String errorCode,
            String message,
            SalesOrderCommand command) {
        static OrderHeaderPlan waiting(String sourceOrderNo, String errorCode, String message) {
            return new OrderHeaderPlan("WAITING_MAPPING", sourceOrderNo, errorCode, message, null);
        }

        static OrderHeaderPlan skipped(String sourceOrderNo, String errorCode, String message) {
            return new OrderHeaderPlan("SKIPPED", sourceOrderNo, errorCode, message, null);
        }

        static OrderHeaderPlan project(String sourceOrderNo, SalesOrderCommand command) {
            return new OrderHeaderPlan("PENDING", sourceOrderNo, null, null, command);
        }
    }

    record LineProjectionPlan(
            String status,
            String parentSourceOrderNo,
            String sourceLineNo,
            String errorCode,
            String message,
            SalesOrderLineCommand command,
            BigDecimal lineAmount) {
        static LineProjectionPlan waiting(String parentSourceOrderNo, String sourceLineNo,
                                          String errorCode, String message) {
            return new LineProjectionPlan("WAITING_MAPPING", parentSourceOrderNo, sourceLineNo,
                    errorCode, message, null, null);
        }

        static LineProjectionPlan project(String parentSourceOrderNo, String sourceLineNo,
                                          SalesOrderLineCommand command, BigDecimal lineAmount) {
            return new LineProjectionPlan("PENDING", parentSourceOrderNo, sourceLineNo,
                    null, null, command, lineAmount);
        }
    }

    record ProjectionPlan(
            String status,
            String errorCode,
            String message,
            SalesOrderCommand command) {
        static ProjectionPlan waiting(String errorCode, String message) {
            return new ProjectionPlan("WAITING_MAPPING", errorCode, message, null);
        }

        static ProjectionPlan project(SalesOrderCommand command) {
            return new ProjectionPlan("PENDING", null, null, command);
        }
    }
}
