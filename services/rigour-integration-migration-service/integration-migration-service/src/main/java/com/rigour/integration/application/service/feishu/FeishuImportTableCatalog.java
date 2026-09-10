package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuImportStore.ImportTemplate;
import com.rigour.integration.application.port.out.FeishuImportStore.ImportTemplateDependency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 飞书导出表到内部业务域的预检目录；正式字段落库仍以后续映射配置为准。 */
final class FeishuImportTableCatalog {
    private static final List<TableDefinition> DEFINITIONS = List.of(
            new TableDefinition("FEISHU_SALES_ORDER", "ORDER", "SALES_ORDER",
                    List.of("销售订单表", "武汉销售订单表", "销售订单列表", "全国销售订单列表"), List.of(), true,
                    List.of("订单编号", "订单编号门店", "来源单号"),
                    List.of("销售日期", "下单时间", "订单时间", "创建时间", "新建时间"),
                    "SOURCE_DOCUMENT_NO", List.of(),
                    List.of(
                            new DependencyDefinition("FEISHU_STORE", "CUSTOMER",
                                    List.of("关联门店", "门店", "客户名称", "客户", "门店编码", "订单编号门店"),
                                    List.of("门店编码", "门店编码名称", "门店名称"), true),
                            new DependencyDefinition("FEISHU_PRODUCT", "PRODUCT",
                                    List.of("产品编号", "商品编码", "产品编码", "订单产品", "产品名称", "商品名称"),
                                    List.of("产品编码", "产品编码名称", "产品名称"), false))),
            new TableDefinition("FEISHU_SALES_STAFF", "HR", "EMPLOYEE",
                    List.of("销售人员信息", "渡江战役团队管理"), List.of("姓名"), true),
            new TableDefinition("FEISHU_SALES_ORDER_LINE", "ORDER", "SALES_ORDER_LINE",
                    List.of("订单明细表", "订单明细列表"), List.of("订单明细号"), true,
                    List.of("订单明细号", "来源单号"),
                    List.of("销售日期", "下单时间", "订单时间", "创建时间", "新建时间"),
                    "SOURCE_DOCUMENT_NO", List.of(),
                    List.of(
                            new DependencyDefinition("FEISHU_SALES_ORDER", "ORDER_HEADER",
                                    List.of("订单编号", "订单编号门店", "关联订单", "销售订单"),
                                    List.of("订单编号", "订单编号门店"), true),
                            new DependencyDefinition("FEISHU_STORE", "CUSTOMER",
                                    List.of("关联门店", "门店", "客户名称", "客户", "门店编码"),
                                    List.of("门店编码", "门店编码名称", "门店名称"), true),
                            new DependencyDefinition("FEISHU_PRODUCT", "PRODUCT",
                                    List.of("产品编号", "商品编码", "产品编码", "订单产品", "产品名称", "商品名称"),
                                    List.of("产品编码", "产品编码名称", "产品名称"), true))),
            new TableDefinition("FEISHU_SALES_SHIPMENT", "ORDER", "SALES_SHIPMENT",
                    List.of("物流信息表"), List.of(), false),
            new TableDefinition("FEISHU_INVOICE", "ORDER", "INVOICE",
                    List.of("发票管理表"), List.of(), false),
            new TableDefinition("FEISHU_PAYMENT_RECORD", "ORDER", "PAYMENT_RECORD",
                    List.of("回款记录表", "回款记录列表", "回款列表"),
                    List.of("回款编号", "关联订单", "实际回款额"), true,
                    List.of("回款编号", "回款日期门店", "来源单号"),
                    List.of("回款日期", "回款日期门店", "收款日期", "付款日期", "创建时间", "新建时间"),
                    "SOURCE_DOCUMENT_NO", List.of(),
                    List.of(new DependencyDefinition("FEISHU_SALES_ORDER", "ORDER_HEADER",
                            List.of("关联订单", "订单编号", "销售订单"),
                            List.of("订单编号", "订单编号门店"), true))),
            new TableDefinition("FEISHU_SAMPLE_REQUEST", "ORDER", "SAMPLE_REQUEST",
                    List.of("样品申请表"), List.of(), false),
            new TableDefinition("FEISHU_MATERIAL_REQUEST", "ERP", "MATERIAL_REQUEST",
                    List.of("物料申请管理"), List.of(), false),
            new TableDefinition("FEISHU_ACTIVITY_APPLICATION", "CRM", "ACTIVITY_APPLICATION",
                    List.of("活动申请管理"), List.of(), false),
            new TableDefinition("FEISHU_CUSTOMER", "CRM", "CUSTOMER",
                    List.of("商家库"), List.of(), true),
            new TableDefinition("FEISHU_VISITED_STORE", "CRM", "VISITED_STORE",
                    List.of("拜访门店信息"), List.of(), false),
            new TableDefinition("FEISHU_STORE", "CRM", "STORE",
                    List.of("门店信息库"), List.of(), true),
            new TableDefinition("FEISHU_PRODUCT_CALCULATION", "ERP", "PRODUCT_CALCULATION",
                    List.of("产品信息库测算表"), List.of(), false),
            new TableDefinition("FEISHU_PRODUCT", "ERP", "PRODUCT",
                    List.of("产品信息库"), List.of("产品编码", "产品名称"), true),
            new TableDefinition("FEISHU_SALES_LEAD", "CRM", "SALES_LEAD",
                    List.of("招商留资表"), List.of(), false),
            new TableDefinition("FEISHU_TEAM", "HR", "TEAM",
                    List.of("团队层级", "团队架构"), List.of(), false),
            new TableDefinition("FEISHU_REGION", "CRM", "REGION",
                    List.of("大区数据"), List.of(), true),
            new TableDefinition("FEISHU_PURCHASE_ORDER", "ERP", "PURCHASE_ORDER",
                    List.of("采购订单表"), List.of(), false),
            new TableDefinition("FEISHU_FELT_PICKUP_ORDER", "ORDER", "FELT_PICKUP_ORDER",
                    List.of("台呢提货单"), List.of(), false),
            new TableDefinition("FEISHU_QUALITY_FEEDBACK", "ERP", "QUALITY_FEEDBACK",
                    List.of("品控反馈"), List.of(), false),
            new TableDefinition("FEISHU_PAYMENT_PENDING_STAT", "ORDER", "PAYMENT_PENDING_STAT",
                    List.of("待回款门店统计"), List.of(), false),
            new TableDefinition("FEISHU_SAMPLE_CITY_SALES_SUMMARY", "SALES", "SAMPLE_CITY_SALES_SUMMARY",
                    List.of("样品销量城市汇总表"), List.of(), false),
            new TableDefinition("FEISHU_ATTENDANCE", "SALES", "ATTENDANCE",
                    List.of("打卡考勤"), List.of(), false),
            new TableDefinition("FEISHU_VISIT_CHECKIN", "SALES", "VISIT_CHECKIN",
                    List.of("拜访门店打卡表"), List.of(), false),
            new TableDefinition("FEISHU_CITY_DAILY_CHECKIN_STAT", "SALES", "CITY_DAILY_CHECKIN_STAT",
                    List.of("城市日打卡战报统计"), List.of(), false),
            new TableDefinition("FEISHU_INVENTORY_COUNT", "ERP", "INVENTORY_COUNT",
                    List.of("库存盘点"), List.of(), false));

    private FeishuImportTableCatalog() {
    }

    static Match match(String sheetName, List<String> headers) {
        return match(sheetName, headers, List.of());
    }

    static Match match(String sheetName, List<String> headers, List<ImportTemplate> templates) {
        TableDefinition definition = definition(sheetName, templates);
        if (definition == null) {
            return new Match(null, null, null, null, "UNMAPPED_TABLE",
                    List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of());
        }
        List<String> missing = missingHeaders(definition.requiredHeaders(), headers);
        String status;
        if (!missing.isEmpty()) {
            status = "NEEDS_FIELD_MAPPING";
        } else if (definition.readyByDefault()) {
            status = "READY";
        } else {
            status = "NEEDS_FIELD_MAPPING";
        }
        return new Match(definition.tableCode(), definition.domainCode(), definition.objectType(),
                definition.requiredHeaders(), status, missing, attachmentFields(headers),
                definition.deduplicationStrategy(), definition.deduplicationFields(),
                definition.sourceDocumentFields(), definition.sourceCreatedFields(),
                definition.dependencies());
    }

    static List<ImportTemplate> defaultTemplates() {
        return DEFINITIONS.stream()
                .map(TableDefinition::toImportTemplate)
                .toList();
    }

    static List<String> aliases(String tableCode) {
        if (tableCode == null || tableCode.isBlank()) return List.of();
        for (TableDefinition definition : DEFINITIONS) {
            if (definition.tableCode().equals(tableCode)) return definition.aliases();
        }
        return List.of();
    }

    static List<String> attachmentFields(List<String> headers) {
        List<String> fields = new ArrayList<>();
        for (String header : headers == null ? List.<String>of() : headers) {
            String normalized = normalized(header);
            if (normalized.contains("附件") || normalized.contains("图片")
                    || normalized.contains("照片") || normalized.contains("凭证")
                    || normalized.contains("文件") || normalized.contains("录音")) {
                fields.add(header);
            }
        }
        return List.copyOf(fields);
    }

    private static TableDefinition definition(String sheetName, List<ImportTemplate> templates) {
        String normalizedSheet = normalized(sheetName);
        for (TableDefinition definition : definitions(templates)) {
            for (String alias : definition.aliases()) {
                if (normalizedSheet.contains(normalized(alias))) return definition;
            }
        }
        return null;
    }

    private static List<String> missingHeaders(List<String> required, List<String> headers) {
        List<String> normalizedHeaders = (headers == null ? List.<String>of() : headers).stream()
                .map(FeishuImportTableCatalog::normalized)
                .toList();
        List<String> missing = new ArrayList<>();
        for (String requiredHeader : required) {
            if (!normalizedHeaders.contains(normalized(requiredHeader))) missing.add(requiredHeader);
        }
        return List.copyOf(missing);
    }

    private static String normalized(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isLetterOrDigit(character) || Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN) {
                builder.append(Character.toUpperCase(character));
            }
        }
        return builder.toString().toUpperCase(Locale.ROOT);
    }

    private static List<TableDefinition> definitions(List<ImportTemplate> templates) {
        Map<String, TableDefinition> values = new LinkedHashMap<>();
        for (TableDefinition definition : DEFINITIONS) {
            values.put(definition.tableCode(), definition);
        }
        if (templates != null) {
            for (ImportTemplate template : templates) {
                if (template == null || template.templateCode() == null || template.templateCode().isBlank()) continue;
                values.put(template.templateCode(), TableDefinition.from(template));
            }
        }
        return List.copyOf(values.values());
    }

    record Match(String tableCode, String domainCode, String objectType,
                 List<String> requiredHeaders, String mappingStatus,
                 List<String> missingHeaders, List<String> attachmentFields,
                 String deduplicationStrategy, List<String> deduplicationFields,
                 List<String> sourceDocumentFields, List<String> sourceCreatedFields,
                 List<DependencyDefinition> dependencies) {
        Match(String tableCode, String domainCode, String objectType,
              List<String> requiredHeaders, String mappingStatus,
              List<String> missingHeaders, List<String> attachmentFields) {
            this(tableCode, domainCode, objectType, requiredHeaders, mappingStatus,
                    missingHeaders, attachmentFields, "SOURCE_DOCUMENT_NO", List.of(),
                    List.of(), List.of(), List.of());
        }

        Match {
            requiredHeaders = requiredHeaders == null ? List.of() : List.copyOf(requiredHeaders);
            missingHeaders = missingHeaders == null ? List.of() : List.copyOf(missingHeaders);
            attachmentFields = attachmentFields == null ? List.of() : List.copyOf(attachmentFields);
            deduplicationFields = deduplicationFields == null ? List.of() : List.copyOf(deduplicationFields);
            sourceDocumentFields = sourceDocumentFields == null ? List.of() : List.copyOf(sourceDocumentFields);
            sourceCreatedFields = sourceCreatedFields == null ? List.of() : List.copyOf(sourceCreatedFields);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    private record TableDefinition(String tableCode, String domainCode, String objectType,
                                   List<String> aliases, List<String> requiredHeaders,
                                   boolean readyByDefault,
                                   List<String> sourceDocumentFields,
                                   List<String> sourceCreatedFields,
                                   String deduplicationStrategy,
                                   List<String> deduplicationFields,
                                   List<DependencyDefinition> dependencies) {
        private TableDefinition(String tableCode, String domainCode, String objectType,
                                List<String> aliases, List<String> requiredHeaders,
                                boolean readyByDefault) {
            this(tableCode, domainCode, objectType, aliases, requiredHeaders, readyByDefault,
                    List.of(), List.of(), "SOURCE_DOCUMENT_NO", List.of(), List.of());
        }

        private TableDefinition {
            aliases = List.copyOf(aliases);
            requiredHeaders = List.copyOf(requiredHeaders);
            sourceDocumentFields = sourceDocumentFields == null ? List.of() : List.copyOf(sourceDocumentFields);
            sourceCreatedFields = sourceCreatedFields == null ? List.of() : List.copyOf(sourceCreatedFields);
            deduplicationStrategy = deduplicationStrategy == null || deduplicationStrategy.isBlank()
                    ? "SOURCE_DOCUMENT_NO" : deduplicationStrategy;
            deduplicationFields = deduplicationFields == null ? List.of() : List.copyOf(deduplicationFields);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        private static TableDefinition from(ImportTemplate template) {
            return new TableDefinition(template.templateCode(), template.domainCode(), template.objectType(),
                    template.aliases(), template.requiredHeaders(), template.readyByDefault(),
                    template.sourceDocumentFields(), template.sourceCreatedFields(),
                    template.deduplicationStrategy(), template.deduplicationFields(),
                    template.dependencies().stream()
                            .map(DependencyDefinition::from)
                            .toList());
        }

        private ImportTemplate toImportTemplate() {
            return new ImportTemplate(tableCode, tableCode, "FEISHU", domainCode, objectType,
                    aliases, requiredHeaders, sourceDocumentFields, sourceCreatedFields,
                    deduplicationStrategy, deduplicationFields,
                    readyByDefault, dependencies.stream()
                    .map(DependencyDefinition::toImportTemplateDependency)
                    .toList());
        }
    }

    record DependencyDefinition(String dependsOnTemplateCode, String relationKind,
                                List<String> sourceReferenceFields,
                                List<String> targetReferenceFields,
                                boolean required) {
        DependencyDefinition {
            sourceReferenceFields = sourceReferenceFields == null ? List.of() : List.copyOf(sourceReferenceFields);
            targetReferenceFields = targetReferenceFields == null ? List.of() : List.copyOf(targetReferenceFields);
        }

        private static DependencyDefinition from(ImportTemplateDependency dependency) {
            return new DependencyDefinition(dependency.dependsOnTemplateCode(), dependency.relationKind(),
                    dependency.sourceReferenceFields(), dependency.targetReferenceFields(),
                    dependency.required());
        }

        private ImportTemplateDependency toImportTemplateDependency() {
            return new ImportTemplateDependency(dependsOnTemplateCode, relationKind,
                    sourceReferenceFields, targetReferenceFields, required);
        }
    }
}
