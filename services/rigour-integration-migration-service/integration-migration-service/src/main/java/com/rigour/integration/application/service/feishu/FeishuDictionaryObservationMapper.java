package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 从飞书导出行提取明确业务枚举字段，用于补齐 Settings 字典项。 */
final class FeishuDictionaryObservationMapper {

    List<Observation> observations(List<StoredRawRow> rows) {
        List<Observation> result = new ArrayList<>();
        for (StoredRawRow row : rows == null ? List.<StoredRawRow>of() : rows) {
            Map<String, String> values = row.values();
            addCommon(result, values);
            addHr(result, row, values);
            addCrm(result, row, values);
            addErp(result, row, values);
            addOrder(result, row, values);
        }
        return List.copyOf(result);
    }

    private static void addCommon(List<Observation> result, Map<String, String> values) {
        add(result, "PRODUCT_UNIT", "单位", value(values, "单位", "单位编码"));
    }

    private static void addHr(List<Observation> result, StoredRawRow row, Map<String, String> values) {
        if (!"HR".equals(row.domainCode())) return;
        add(result, "EMPLOYEE_STATUS", "在职状态", value(values, "在职状态", "状态"));
    }

    private static void addCrm(List<Observation> result, StoredRawRow row, Map<String, String> values) {
        if (!"CRM".equals(row.domainCode())) return;
        add(result, "CUSTOMER_SOURCE", "商家来源", value(values, "商家来源"));
        add(result, "CUSTOMER_CATEGORY", "商家类目", value(values, "商家类目"));
        add(result, "STORE_ATTRIBUTE", "门店属性", value(values, "门店属性", "属性"));
        add(result, "STORE_STATUS", "门店状态", value(values, "门店状态", "营业状态"));
        addSplit(result, "STORE_BUSINESS_TYPE", "经营类型", value(values, "经营类型"));
        add(result, "STORE_SCALE", "门店规模", value(values, "门店规模", "面积"));
        addSplit(result, "STORE_TAG", "门店标签", value(values, "标签", "门店标签"));
        add(result, "CUSTOMER_RISK_LEVEL", "风控等级", value(values, "风控等级", "风险等级"));
        add(result, "CUSTOMER_COOP_LEVEL", "合作等级", value(values, "合作等级"));
        add(result, "CUSTOMER_COOP_STATUS", "合作状态", value(values, "合作状态"));
        add(result, "CUSTOMER_INTENTION_LEVEL", "合作意向", value(values, "合作意向"));
        add(result, "LEAD_SUBJECT_IDENTITY", "主体身份", value(values, "主体身份"));
        addSplit(result, "ACTIVITY_FORM", "活动形式", value(values, "活动形式"));
        add(result, "ACTIVITY_PREHEAT_PERIOD", "预热周期", value(values, "预热周期"));
        addSplit(result, "BUSINESS_LINE", "业务线", value(values, "已合作业务线", "意向业务线", "意向业务", "核心业务"));
    }

    private static void addErp(List<Observation> result, StoredRawRow row, Map<String, String> values) {
        if (!"ERP".equals(row.domainCode())) return;
        add(result, "BUSINESS_LINE", "业务线", value(values, "业务线"));
        add(result, "PRODUCT_INDUSTRY", "行业", value(values, "行业"));
        add(result, "PRODUCT_CATEGORY_SOURCE", "品类", value(values, "品类", "产品分类"));
        if ("PRODUCT".equals(row.objectType()) || "PRODUCT_CALCULATION".equals(row.objectType())) {
            add(result, "PRODUCT_SOURCE_STATUS", "状态", value(values, "状态"));
        }
        if ("MATERIAL_REQUEST".equals(row.objectType())) {
            add(result, "MATERIAL_REQUEST_STATUS", "物料状态", value(values, "物料状态"));
        }
        if ("PURCHASE_ORDER".equals(row.objectType())) {
            add(result, "PURCHASE_ORDER_STATUS", "订单状态", value(values, "订单状态"));
        }
        if ("INVENTORY_COUNT".equals(row.objectType())) {
            add(result, "INVENTORY_LOCATION_TYPE", "库存地点类型", value(values, "类型"));
        }
        if ("QUALITY_FEEDBACK".equals(row.objectType())) {
            add(result, "QUALITY_ISSUE_TYPE", "问题类型", value(values, "问题类型"));
            add(result, "QUALITY_SEVERITY", "严重程度", value(values, "严重程度"));
            add(result, "QUALITY_DISCOVERY_CHANNEL", "发现渠道", value(values, "发现渠道"));
            add(result, "QUALITY_PROCESS_STATUS", "城市处理状态", value(values, "城市处理状态", "处理状态"));
            add(result, "MANUFACTURER_COMPENSATION_STATUS", "厂家赔付状态", value(values, "厂家赔付状态"));
        }
    }

    private static void addOrder(List<Observation> result, StoredRawRow row, Map<String, String> values) {
        if (!"ORDER".equals(row.domainCode())) return;
        add(result, "ORDER_TYPE", "订单类型", value(values, "订单类型"));
        if ("SALES_ORDER".equals(row.objectType()) || "PAYMENT_RECORD".equals(row.objectType())) {
            add(result, "PAYMENT_METHOD", "付款方式", value(values, "付款方式"));
            add(result, "PAYMENT_STATUS", "付款状态", value(values, "付款状态"));
            add(result, "SALES_ORDER_STATUS", "订单状态", value(values, "订单状态", "审核状态"));
        }
        if ("SALES_SHIPMENT".equals(row.objectType())) {
            add(result, "SALES_SHIPMENT_STATUS", "发货状态", value(values, "发货状态"));
        }
        if ("INVOICE".equals(row.objectType())) {
            add(result, "INVOICE_STATUS", "发票状态", value(values, "发票状态"));
        }
        if ("SAMPLE_REQUEST".equals(row.objectType())) {
            add(result, "SAMPLE_REQUEST_TYPE", "样品类型", value(values, "类型"));
            add(result, "SAMPLE_REQUEST_STATUS", "样品状态", value(values, "状态"));
        }
        if ("FELT_PICKUP_ORDER".equals(row.objectType())) {
            add(result, "FELT_PICKUP_PAYMENT_STATUS", "台呢付款状态", value(values, "付款状态"));
        }
    }

    private static void addSplit(List<Observation> result, String dictionaryCode,
                                 String fieldCode, String sourceValue) {
        String value = text(sourceValue);
        if (value == null) return;
        for (String item : value.split("[,，、;/；|]")) {
            add(result, dictionaryCode, fieldCode, item);
        }
    }

    private static void add(List<Observation> result, String dictionaryCode,
                            String fieldCode, String sourceValue) {
        String value = text(sourceValue);
        if (value != null) result.add(new Observation(dictionaryCode, fieldCode, value, value));
    }

    private static String value(Map<String, String> values, String... names) {
        for (String name : names) {
            String value = values.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }
}
