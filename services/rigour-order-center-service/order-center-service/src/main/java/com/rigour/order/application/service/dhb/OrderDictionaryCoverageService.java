package com.rigour.order.application.service.dhb;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Order Center 订货宝同步的显式状态、类型和单位字典白名单。 */
@Service
public final class OrderDictionaryCoverageService {
    private final BusinessDictionaryBatchClient client;

    public OrderDictionaryCoverageService(BusinessDictionaryBatchClient client) {
        this.client = client;
    }

    /** 自动补齐当前订单域批次的已建模来源枚举；不读取 rawJson。 */
    public Audit sync(UUID tenantId, String objectType, DhbOrderImportBatch batch) {
        List<Observation> values = new ArrayList<>();
        batch.orders().forEach(item -> addOrder(values, item));
        batch.shipments().forEach(item -> addShipment(values, item));
        batch.shipmentLogistics().forEach(item -> addLogistics(values, item));
        batch.returns().forEach(item -> addReturn(values, item));
        batch.financialDocuments().forEach(item -> addFinancial(values, item));
        return client.sync(BusinessDictionaryBatchClient.serviceCaller(
                "rigour-order-center-service", "ORDER_DICTIONARY_SYNC", tenantId), objectType, values);
    }

    private static void addOrder(List<Observation> values, DhbOrderImportBatch.OrderItem item) {
        add(values, "ORDER", "DHB_ORDER_STATUS", "order.sourceStatus", item.sourceStatus(), null);
        add(values, "ORDER", "DHB_ORDER_PAYMENT_STATUS", "order.paymentStatus", item.paymentStatus(), null);
        add(values, "ORDER", "DHB_ORDER_TYPE", "order.orderType", item.orderType(), null);
        add(values, "ORDER", "DHB_ORDER_API_STATUS", "order.sourceApiStatus", item.sourceApiStatus(), null);
        add(values, "ORDER", "DHB_ORDER_EXCEPTION_STATUS", "order.sourceExceptionStatus",
                item.sourceExceptionStatus(), null);
        add(values, "ORDER", "DHB_ORDER_ADMIN_FLAG", "order.sourceAdminOrder", item.sourceAdminOrder(), null);
        add(values, "ORDER", "DHB_ORDER_SPLIT_TYPE", "order.splitType", item.splitType(), item.splitTypeName());
        add(values, "ORDER", "DHB_SETTLEMENT_METHOD", "order.settlementMethod", item.settlementMethod(), null);
        add(values, "ORDER", "DHB_INVOICE_TYPE", "order.invoiceType", item.invoiceType(), null);
        item.lines().forEach(line -> add(values, "COMMON", "DHB_UNIT", "orderLine.unit",
                line.unit(), line.unit()));
        item.lines().forEach(line -> {
            add(values, "ORDER", "DHB_ORDER_LINE_TYPE", "orderLine.contentType",
                    line.contentType(), null);
        });
        item.shipmentSnapshots().forEach(shipment -> add(values, "ORDER", "DHB_SHIPMENT_STATUS",
                "orderShipment.status", shipment.status(), null));
    }

    private static void addShipment(List<Observation> values, DhbOrderImportBatch.ShipmentItem item) {
        add(values, "ORDER", "DHB_SHIPMENT_STATUS", "shipment.status", item.status(), item.statusName());
        add(values, "ORDER", "DHB_SHIPMENT_TYPE", "shipment.typeId", item.typeId(), item.typeName());
        item.lines().forEach(line -> add(values, "COMMON", "DHB_UNIT", "shipmentLine.unit",
                line.unit(), line.unit()));
    }

    private static void addLogistics(List<Observation> values,
                                     DhbOrderImportBatch.ShipmentLogisticsItem item) {
        item.shipped().forEach(record -> {
            add(values, "ORDER", "DHB_SHIPMENT_STATUS", "shipmentLogistics.status",
                    record.status(), null);
            record.lines().forEach(line -> {
                add(values, "COMMON", "DHB_UNIT", "shipmentLogisticsLine.unit", line.unit(), line.unit());
                add(values, "COMMON", "DHB_UNIT", "shipmentLogisticsLine.containerUnit",
                        line.containerUnit(), line.containerUnit());
                add(values, "ORDER", "DHB_GOODS_LIST_TYPE", "shipmentLogisticsLine.listType",
                        line.listType(), null);
            });
        });
        item.waitStock().forEach(line -> {
            add(values, "COMMON", "DHB_UNIT", "waitStock.unit", line.unit(), line.unit());
            add(values, "COMMON", "DHB_UNIT", "waitStock.containerUnit",
                    line.containerUnit(), line.containerUnit());
            add(values, "ORDER", "DHB_GOODS_LIST_TYPE", "waitStock.listType",
                    line.listType(), null);
        });
    }

    private static void addReturn(List<Observation> values, DhbOrderImportBatch.ReturnItem item) {
        add(values, "ORDER", "DHB_RETURN_STATUS", "return.status", item.status(), null);
        add(values, "ORDER", "DHB_RETURN_TYPE", "return.returnType", item.returnType(), null);
        item.lines().forEach(line -> add(values, "COMMON", "DHB_UNIT", "returnLine.unit",
                line.unit(), line.unit()));
    }

    private static void addFinancial(List<Observation> values, DhbOrderImportBatch.FinancialItem item) {
        add(values, "ORDER", "DHB_FINANCIAL_DOCUMENT_TYPE", "financial.documentType",
                item.documentType(), null);
        add(values, "ORDER", "DHB_FINANCIAL_BUSINESS_TYPE", "financial.businessType",
                item.businessType(), null);
        add(values, "ORDER", "DHB_PAYMENT_METHOD", "financial.paymentMethod",
                item.paymentMethod(), null);
        add(values, "ORDER", "DHB_FINANCIAL_STATUS", "financial.status", item.status(), null);
    }

    private static void add(List<Observation> target, String moduleCode, String dictCode,
                            String fieldCode, String sourceValue, String sourceName) {
        if (sourceValue == null || sourceValue.isBlank()) return;
        if ("DHB_UNIT".equals(dictCode) && isUnitLevel(sourceValue)) return;
        target.add(new Observation(moduleCode, dictCode, fieldCode,
                sourceValue.strip(), sourceName));
    }

    private static boolean isUnitLevel(String value) {
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "base_units", "middle_units", "container_units", "big_units" -> true;
            default -> false;
        };
    }
}
