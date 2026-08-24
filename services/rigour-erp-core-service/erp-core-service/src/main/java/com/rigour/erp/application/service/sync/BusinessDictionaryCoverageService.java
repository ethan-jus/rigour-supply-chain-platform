package com.rigour.erp.application.service.sync;

import com.rigour.erp.application.model.DictionaryMappingAudit;
import com.rigour.erp.application.port.out.DhbProductMasterDataClient;
import com.rigour.erp.application.port.out.DhbSupplyDataClient;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 在每个 ERP 同步批次内一次性校验订货宝有限枚举和单位字典覆盖率。
 *
 * <p>只枚举本服务明确维护的字段白名单；公共客户端自动补齐缺失项并返回快照审计。</p>
 */
@Service
public final class BusinessDictionaryCoverageService {
    private final BusinessDictionaryBatchClient client;

    public BusinessDictionaryCoverageService(BusinessDictionaryBatchClient client) {
        this.client = client;
    }

    public DictionaryMappingAudit inspect(UUID tenantId, DhbProductMasterDataClient.Collected data) {
        List<Observation> values = new ArrayList<>();
        data.products().forEach(product -> addProduct(values, product));
        return inspect(tenantId, data.objectType().name(), values);
    }

    public DictionaryMappingAudit inspect(UUID tenantId, DhbSupplyDataClient.Collected data) {
        List<Observation> values = new ArrayList<>();
        data.purchaseOrders().forEach(order -> addPurchaseOrder(values, order));
        data.purchaseReturns().forEach(value -> {
            add(values, "ERP", "DHB_PURCHASE_RETURN_STATUS", "purchaseReturn.sourceStatus",
                    value.sourceStatus(), value.sourceStatusName());
            value.lines().forEach(line -> add(values, "COMMON", "DHB_UNIT", "purchaseReturnLine.unit",
                    firstText(line.unitCode(), line.unitName()), line.unitName()));
        });
        data.warehousingReceipts().forEach(value -> addWarehousing(values, value));
        data.warehouses().forEach(value -> add(values, "ERP", "DHB_WAREHOUSE_STATUS",
                "warehouse.sourceStatus", value.sourceStatus(), null));
        return inspect(tenantId, data.objectType().name(), values);
    }

    private DictionaryMappingAudit inspect(UUID tenantId, String objectType, List<Observation> values) {
        if (values.isEmpty()) return DictionaryMappingAudit.empty();
        Audit audit = client.sync(BusinessDictionaryBatchClient.serviceCaller(
                "rigour-erp-core-service", "ERP_DICTIONARY_SYNC", tenantId), objectType, values);
        return new DictionaryMappingAudit(audit.unmapped(), audit.revisions(), audit.issues().stream()
                .map(issue -> new DictionaryMappingAudit.MappingIssue(
                        issue.dictionaryCode(), issue.fieldCode(),
                        issue.sourceValue(), issue.count()))
                .toList());
    }

    private static void addProduct(List<Observation> values, Product product) {
        add(values, "ERP", "DHB_PRODUCT_STATUS", "product.status",
                exact(product.sourceFields(), "status"), null);
        add(values, "ERP", "DHB_PRODUCT_PUTAWAY", "product.putaway", product.putaway(), null);
        add(values, "COMMON", "DHB_UNIT", "product.baseUnit", product.unit(), product.unit());
        add(values, "COMMON", "DHB_UNIT", "product.middleUnit", product.middleUnit(), product.middleUnit());
        add(values, "COMMON", "DHB_UNIT", "product.bigUnit", product.bigUnit(), product.bigUnit());
    }

    private static void addPurchaseOrder(List<Observation> values, PurchaseOrder order) {
        add(values, "ERP", "DHB_PURCHASE_ORDER_STATUS", "purchaseOrder.sourceStatus",
                order.sourceStatus(), order.sourceStatusName());
        add(values, "ERP", "DHB_PURCHASE_PAYMENT_STATUS", "purchaseOrder.paymentStatus",
                order.paymentStatus(), order.paymentStatusName());
        order.lines().forEach(line -> add(values, "COMMON", "DHB_UNIT", "purchaseOrderLine.unit",
                firstText(line.unitCode(), line.unitName()), line.unitName()));
    }

    private static void addWarehousing(List<Observation> values, WarehousingReceipt receipt) {
        add(values, "ERP", "DHB_WAREHOUSING_STATUS", "warehousing.sourceStatus",
                receipt.sourceStatus(), receipt.sourceStatusName());
        add(values, "ERP", "DHB_WAREHOUSING_TYPE", "warehousing.typeId",
                receipt.typeId(), receipt.typeName());
        receipt.lines().forEach(line -> add(values, "COMMON", "DHB_UNIT", "warehousingLine.unit",
                firstText(line.unitCode(), line.unitName()), line.unitName()));
    }

    private static void add(List<Observation> target, String moduleCode, String dictCode,
                            String fieldCode, String sourceValue, String sourceName) {
        if (sourceValue == null || sourceValue.isBlank()) return;
        if ("DHB_UNIT".equals(dictCode) && isUnitLevel(sourceValue)) return;
        target.add(new Observation(dictCode, fieldCode, sourceValue.strip(), sourceName));
    }

    private static boolean isUnitLevel(String value) {
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "base_units", "middle_units", "container_units", "big_units" -> true;
            default -> false;
        };
    }

    private static String firstText(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private static String exact(java.util.Map<String, Object> fields, String key) {
        if (fields == null || !fields.containsKey(key) || fields.get(key) == null) return null;
        String value = String.valueOf(fields.get(key));
        return value.isBlank() ? null : value;
    }

}
