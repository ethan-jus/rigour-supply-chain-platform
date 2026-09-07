package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.port.out.DhbSyncStore.ExternalObjectMapping;
import com.rigour.integration.application.port.out.DhbSyncStore.ManualResolution;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesShipmentLineCommand;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DhbOrderSyncServiceLineMappingTest {

    @Test
    void preservesDifferentSourceLinesForSameSkuBeforeProjectingToSalesOrder() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "PROD-1"),
                mapping("PRODUCT_SPU", "PROD-1", "P-1", "ERP", "PRODUCT", 10L, "SP202608220001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "PROD-1::SKU-1"),
                mapping("PRODUCT_SKU", "PROD-1::SKU-1", "SKU-1",
                        "ERP", "PRODUCT_VARIANT", 11L, "SK202608220001"));
        DhbSyncStore store = storeWithMappings(mappings);
        DhbOrderSyncService service = service(store);

        List<SalesOrderLineCommand> lines = salesOrderLines(service, tenantId, connectorId,
                Map.of("OrderProduct", List.of(
                        orderProductRow("LINE-1", "PROD-1", "SKU-1", "2", "10.00"),
                        orderProductRow("LINE-2", "PROD-1", "SKU-1", "3", "12.00"))));

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst().quantity()).isEqualByComparingTo("2");
        assertThat(lines.getFirst().unitPrice()).isEqualByComparingTo("10.00");
        assertThat(lines.getFirst().productCodeSnapshot()).isEqualTo("SP202608220001");
        assertThat(lines.getFirst().skuCodeSnapshot()).isEqualTo("SK202608220001");
        assertThat(lines.get(1).quantity()).isEqualByComparingTo("3");
        assertThat(lines.get(1).unitPrice()).isEqualByComparingTo("12.00");
    }

    @Test
    void resolvesSkuWithProductMappingSourceIdWhenOrderLineOnlyContainsProductCode() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "P-1"),
                mapping("PRODUCT_SPU", "PROD-1", "P-1", "ERP", "PRODUCT", 10L, "SP202608220001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "PROD-1::SKU-1"),
                mapping("PRODUCT_SKU", "PROD-1::SKU-1", "SKU-1",
                        "ERP", "PRODUCT_VARIANT", 11L, "SK202608220001"));
        DhbSyncStore store = storeWithMappings(mappings);
        DhbOrderSyncService service = service(store);

        List<SalesOrderLineCommand> lines = salesOrderLines(service, tenantId, connectorId,
                Map.of("OrderProduct", List.of(Map.<String, Object>of(
                        "Coding", "P-1",
                        "OptionsGoodsNo", "SKU-1",
                        "Name", "酸麻粉面菜蛋",
                        "multiName", "箱装",
                        "ContentNumber", "2",
                        "ContentPrice", "10.00",
                        "Units", "箱"))));

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().productCodeSnapshot()).isEqualTo("SP202608220001");
        assertThat(lines.getFirst().skuCodeSnapshot()).isEqualTo("SK202608220001");
    }

    @Test
    void mapsDhbPaymentToFundDocumentBusinessTypeWithoutDefaultingToRefund() {
        assertThat(paymentFundBusinessType(null, Map.of())).isEqualTo("BALANCE_DEDUCTION");
        assertThat(paymentFundBusinessType(null, Map.of("IncexpId", "5")))
                .isEqualTo("BALANCE_DEDUCTION");
        assertThat(paymentFundBusinessType("普通付款", Map.of())).isEqualTo("BALANCE_DEDUCTION");
        assertThat(paymentFundBusinessType(null, Map.of("IncexpName", "余额抵扣")))
                .isEqualTo("BALANCE_DEDUCTION");
        assertThat(paymentFundBusinessType("退款", Map.of())).isEqualTo("CUSTOMER_REFUND");
    }

    @Test
    void mapsDhbReceiptToRechargeOrOrderReceiptBySourceOrderPresence() {
        assertThat(receiptFundBusinessType(null, Map.of(), null)).isEqualTo("CUSTOMER_RECHARGE");
        assertThat(receiptFundBusinessType(null, Map.of(), "DHB-DD-1")).isEqualTo("ORDER_RECEIPT");
        assertThat(receiptFundBusinessType(null, Map.of("IncexpName", "客户余额充值"), "DHB-DD-1"))
                .isEqualTo("CUSTOMER_RECHARGE");
    }

    @Test
    void projectsDhbReceiptSourcePaymentFieldsToFundDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "SALES_ORDER", "DH-1"),
                mapping("SALES_ORDER", "DH-1", "DH-1", "ORDER", "SALES_ORDER",
                        100L, "SO202608260001"));
        DhbOrderSyncService service = service(storeWithMappings(mappings));
        Map<String, Object> attributes = Map.of(
                "Attachments", List.of(Map.of("fileName", "202608260239531787726393103.png")));

        FundDocumentCommand command = prepareReceiptFundDocument(service, tenantId, connectorId,
                "FR.20260826.0247",
                new DhbClient.Receipt("FR.20260826.0247", "FR.20260826.0247", "DH-1",
                        "C-1", "CLIENT-GUID-1", "充值", "Offline", new BigDecimal("507.00"),
                        "pend_receipted", Instant.parse("2026-08-25T16:00:00Z"),
                        Instant.parse("2026-08-26T06:39:00Z"),
                        Instant.parse("2026-08-26T06:40:00Z"),
                        "252112_FR.20260826.0247", "北京瑞盖文化传媒有限公司",
                        "中国建设银行股份有限公司北京经济技术开发区分行",
                        "11050171360000002801", "备注", attributes),
                attributes);

        assertThat(command.directionCode()).isEqualTo("RECEIPT");
        assertThat(command.relatedOrderId()).isEqualTo(100L);
        assertThat(command.sourceDocumentNo()).isEqualTo("FR.20260826.0247");
        assertThat(command.sourceOrderNo()).isEqualTo("DH-1");
        assertThat(command.paymentSerialNo()).isEqualTo("252112_FR.20260826.0247");
        assertThat(command.bankAccountName()).isEqualTo("北京瑞盖文化传媒有限公司");
        assertThat(command.bankName()).isEqualTo("中国建设银行股份有限公司北京经济技术开发区分行");
        assertThat(command.bankAccountNo()).isEqualTo("11050171360000002801");
        assertThat(command.submittedAt()).isEqualTo(Instant.parse("2026-08-26T06:39:00Z"));
        assertThat(command.confirmedAt()).isNull();
        assertThat(command.documentStatusCode()).isEqualTo("PENDING");
        assertThat(command.sourceAttachmentKeys()).containsExactly("202608260239531787726393103.png");
    }

    @Test
    void uploadsDhbReceiptAttachmentToCosBeforeProjectingFundDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "SALES_ORDER", "DH-1"),
                mapping("SALES_ORDER", "DH-1", "DH-1", "ORDER", "SALES_ORDER",
                        100L, "SO202608260001"));
        DhbClient client = mock(DhbClient.class);
        ProductMediaStorage storage = mock(ProductMediaStorage.class);
        byte[] content = new byte[]{1, 2, 3};
        when(client.downloadAttachment(any(DhbClient.Connector.class), eq("/receipts/proof.png")))
                .thenReturn(new DhbClient.DownloadedFile(content, "image/png"));
        DhbOrderSyncService service = new DhbOrderSyncService(storeWithMappings(mappings),
                client, proxy(OrderSalesOrderProjectionClient.class), null,
                proxy(IamDhbStaffSyncClient.class), null, 3,
                storage, new DhbAttachmentObjectKeyFactory("fund-attachments"));
        Map<String, Object> attributes = Map.of(
                "Attachments", List.of(Map.of(
                        "fileUrl", "/receipts/proof.png",
                        "fileName", "202608260239531787726393103.png")));

        FundDocumentCommand command = prepareReceiptFundDocument(service, tenantId, connectorId,
                "FR.20260826.0247",
                new DhbClient.Receipt("FR.20260826.0247", "FR.20260826.0247", "DH-1",
                        "C-1", "CLIENT-GUID-1", "充值", "Offline", new BigDecimal("507.00"),
                        "confirmed", Instant.parse("2026-08-25T16:00:00Z"),
                        Instant.parse("2026-08-26T06:39:00Z"),
                        Instant.parse("2026-08-26T06:40:00Z"),
                        null, null, null, null, null, attributes),
                attributes);

        assertThat(command.sourceAttachmentKeys()).hasSize(1);
        String objectKey = command.sourceAttachmentKeys().getFirst();
        assertThat(objectKey)
                .startsWith(tenantId + "/fund-attachments/FR_20260826_0247/")
                .endsWith(".png");
        verify(storage).put(eq(tenantId.toString()), eq(objectKey),
                eq("202608260239531787726393103.png"), eq("image/png"),
                argThat(value -> Arrays.equals(value, content)));
    }

    @Test
    void projectsDhbPaymentSourcePaymentFieldsToFundDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "SALES_ORDER", "DH-1"),
                mapping("SALES_ORDER", "DH-1", "DH-1", "ORDER", "SALES_ORDER",
                        100L, "SO202608260001"));
        DhbOrderSyncService service = service(storeWithMappings(mappings));
        Map<String, Object> attributes = Map.of("Files", "payment-proof.png");

        FundDocumentCommand command = preparePaymentFundDocument(service, tenantId, connectorId,
                "FP.20260826.0001",
                new DhbClient.Payment("FP.20260826.0001", "FP.20260826.0001", "FR.20260826.0247",
                        "DH-1", "C-1", "CLIENT-GUID-1", "5", "Deposit",
                        new BigDecimal("10.00"), "confirmed", Instant.parse("2026-08-25T16:00:00Z"),
                        Instant.parse("2026-08-26T07:00:00Z"),
                        Instant.parse("2026-08-26T07:01:00Z"),
                        "252112_FP.20260826.0001", "北京瑞盖文化传媒有限公司",
                        "中国建设银行股份有限公司北京经济技术开发区分行",
                        "11050171360000002801", "付款备注", attributes),
                attributes);

        assertThat(command.directionCode()).isEqualTo("PAYMENT");
        assertThat(command.businessTypeCode()).isEqualTo("BALANCE_DEDUCTION");
        assertThat(command.sourceDocumentNo()).isEqualTo("FP.20260826.0001");
        assertThat(command.sourceOrderNo()).isEqualTo("DH-1");
        assertThat(command.paymentSerialNo()).isEqualTo("252112_FP.20260826.0001");
        assertThat(command.bankAccountNo()).isEqualTo("11050171360000002801");
        assertThat(command.submittedAt()).isEqualTo(Instant.parse("2026-08-26T07:00:00Z"));
        assertThat(command.confirmedAt()).isEqualTo(Instant.parse("2026-08-26T07:01:00Z"));
        assertThat(command.sourceAttachmentKeys()).containsExactly("payment-proof.png");
    }

    @Test
    void mapsDhbBedUnitToInternalProductUnit() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "PROD-1"),
                mapping("PRODUCT_SPU", "PROD-1", "P-1", "ERP", "PRODUCT", 10L, "SP202608220001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "PROD-1::SKU-1"),
                mapping("PRODUCT_SKU", "PROD-1::SKU-1", "SKU-1",
                        "ERP", "PRODUCT_VARIANT", 11L, "SK202608220001"));
        DhbSyncStore store = storeWithMappings(mappings);
        DhbOrderSyncService service = service(store);

        List<SalesOrderLineCommand> lines = salesOrderLines(service, tenantId, connectorId,
                Map.of("OrderProduct", List.of(orderProductRow(
                        "LINE-1", "PROD-1", "SKU-1", "1", "298.00", "床"))));

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().unitCode()).isEqualTo("BED");
    }

    @Test
    void mapsDhbPairUnitToInternalProductUnit() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "PROD-1"),
                mapping("PRODUCT_SPU", "PROD-1", "P-1", "ERP", "PRODUCT", 10L, "SP202608220001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "PROD-1::SKU-1"),
                mapping("PRODUCT_SKU", "PROD-1::SKU-1", "SKU-1",
                        "ERP", "PRODUCT_VARIANT", 11L, "SK202608220001"));
        DhbSyncStore store = storeWithMappings(mappings);
        DhbOrderSyncService service = service(store);

        List<SalesOrderLineCommand> lines = salesOrderLines(service, tenantId, connectorId,
                Map.of("OrderProduct", List.of(orderProductRow(
                        "LINE-1", "PROD-1", "SKU-1", "2", "38.00", "副"))));

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().unitCode()).isEqualTo("PAIR");
    }

    @Test
    void mapsObservedBottleAndStripUnitsToInternalProductUnits() {
        assertThat(unitCode("瓶")).isEqualTo("BOTTLE");
        assertThat(unitCode("条")).isEqualTo("STRIP");
        assertThat(unitCode("颗")).isEqualTo("GRAIN");
    }

    @Test
    void doesNotTreatUnknownDhbUnitCodeAsInternalProductUnit() {
        assertThatThrownBy(() -> unitCode("EA"))
                .hasMessageContaining("订货宝订单明细单位未映射到我方PRODUCT_UNIT编码");
    }

    @Test
    void recordsRawDhbUnitSeparatelyFromInternalProductUnitMapping() {
        List<Observation> observations = sourceUnitObservations("orderLine.sourceUnit",
                Map.of("detail", Map.of("OrderProduct", List.of(
                                orderProductRow("LINE-1", "PROD-1", "SKU-1", "1", "10.00", "EA"),
                                orderProductRow("LINE-2", "PROD-1", "SKU-1", "1", "10.00", "箱"))),
                        "list", Map.of()));

        assertThat(observations).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("DHB_UNIT");
            assertThat(item.sourceValue()).isEqualTo("EA");
            assertThat(item.sourceName()).isEqualTo("EA");
        });
        assertThat(observations).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("DHB_UNIT");
            assertThat(item.sourceValue()).isEqualTo("箱");
            assertThat(item.sourceName()).isEqualTo("箱");
        });
        assertThat(observations).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("PRODUCT_UNIT");
            assertThat(item.sourceValue()).isEqualTo("BOX");
            assertThat(item.sourceName()).isEqualTo("箱");
        });
        assertThat(observations).noneSatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("PRODUCT_UNIT");
            assertThat(item.sourceValue()).isEqualTo("EA");
        });
    }

    @Test
    void classifiesDhbShipmentTypesForProjectionBoundaries() {
        assertThat(stockOutType("10", "销售出库")).isEqualTo("SALES");
        assertThat(stockOutType("18", "调拨出库")).isEqualTo("TRANSFER");
        assertThat(stockOutType("17", "其他出库")).isEqualTo("OTHER");
        assertThat(stockOutType("-2", "采购退货")).isEqualTo("PURCHASE_RETURN");
    }

    @Test
    void resolvesWarehouseMappingBySourceNumberWhenProjectingStockOut() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "WAREHOUSE", "WH-001"),
                mapping("WAREHOUSE", "WH-SOURCE-ID", "WH-001",
                        "ERP", "INVENTORY_WAREHOUSE", 301L, "WH202608250001"));
        DhbOrderSyncService service = service(storeWithMappings(mappings));

        Long warehouseId = warehouseId(service, tenantId, connectorId,
                Map.of("StockNum", "WH-001"));

        assertThat(warehouseId).isEqualTo(301L);
    }

    @Test
    void resolvesWarehouseMappingFromDhbSnakeCaseStockFields() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "WAREHOUSE", "WH-001"),
                mapping("WAREHOUSE", "WH-SOURCE-ID", "WH-001",
                        "ERP", "INVENTORY_WAREHOUSE", 301L, "WH202608250001"));
        DhbOrderSyncService service = service(storeWithMappings(mappings));

        Long warehouseId = warehouseId(service, tenantId, connectorId,
                Map.of("stock_num", "WH-001"));

        assertThat(warehouseId).isEqualTo(301L);
    }

    @Test
    void resolvesTransferTargetWarehouseFromDelayedInboundReceipt() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = transferMappings(tenantId, connectorId);
        DhbSyncStore store = storeWithMappings(mappings, List.of(transferInboundReceipt(
                "RK-1", "DST-WH", "2026-08-22T10:00:00Z")));
        DhbOrderSyncService service = service(store);

        Object prepared = prepareTransferStockOut(service, tenantId, connectorId,
                Map.of("StockID", "SRC-WH",
                        "ShipsDate", "2026-08-21 10:00:00",
                        "OperatorName", "出库经办",
                        "list", List.of(transferOutLine("PROD-1", "SKU-1", "5"))));

        assertThat(transferTargetWarehouseId(prepared)).isEqualTo(902L);
        assertThat(transferAffectStockBalance(prepared)).isFalse();
        assertThat(transferOutboundOperatorStaffName(prepared)).isEqualTo("出库经办");
        assertThat(transferInboundOperatorStaffName(prepared)).isEqualTo("入库经办");
        assertThat(transferRemark(prepared)).contains("匹配订货宝调拨入库 RK-1");
    }

    @Test
    void collapsesDuplicateTransferInboundReceiptCandidatesByReceiptNo() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = transferMappings(tenantId, connectorId);
        DhbSyncStore store = storeWithMappings(mappings, List.of(
                transferInboundReceipt("RK-1", "DST-WH", "2026-08-23T10:00:00Z"),
                transferInboundReceipt("RK-1", "DST-WH", "2026-08-22T10:00:00Z")));
        DhbOrderSyncService service = service(store);

        Object prepared = prepareTransferStockOut(service, tenantId, connectorId,
                Map.of("StockID", "SRC-WH",
                        "ShipsDate", "2026-08-21 10:00:00",
                        "list", List.of(transferOutLine("PROD-1", "SKU-1", "5"))));

        assertThat(transferTargetWarehouseId(prepared)).isEqualTo(902L);
        assertThat(transferRemark(prepared)).contains("匹配订货宝调拨入库 RK-1");
    }

    @Test
    void rejectsAmbiguousTransferInboundReceiptsInsteadOfGuessingByTime() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = transferMappings(tenantId, connectorId);
        DhbSyncStore store = storeWithMappings(mappings, List.of(
                transferInboundReceipt("RK-1", "DST-WH", "2026-08-22T10:00:00Z"),
                transferInboundReceipt("RK-2", "ALT-WH", "2026-08-23T10:00:00Z")));
        DhbOrderSyncService service = service(store);

        assertThatThrownBy(() -> prepareTransferStockOut(service, tenantId, connectorId,
                Map.of("StockID", "SRC-WH",
                        "ShipsDate", "2026-08-21 10:00:00",
                        "list", List.of(transferOutLine("PROD-1", "SKU-1", "5")))))
                .hasMessageContaining("匹配到多个调拨入库候选");
    }

    @Test
    void appliesManualResolutionForAmbiguousTransferInboundReceipt() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = transferMappings(tenantId, connectorId);
        DhbSyncStore store = storeWithMappings(mappings, List.of(
                transferInboundReceipt("RK-1", "DST-WH", "2026-08-22T10:00:00Z"),
                transferInboundReceipt("RK-2", "ALT-WH", "2026-08-23T10:00:00Z")),
                Map.of("FH-TEST", manualTransferInboundResolution("FH-TEST", "RK-2")));
        DhbOrderSyncService service = service(store);

        Object prepared = prepareTransferStockOut(service, tenantId, connectorId,
                Map.of("StockID", "SRC-WH",
                        "ShipsDate", "2026-08-21 10:00:00",
                        "list", List.of(transferOutLine("PROD-1", "SKU-1", "5"))));

        assertThat(transferTargetWarehouseId(prepared)).isEqualTo(903L);
        assertThat(transferRemark(prepared)).contains("人工确认匹配订货宝调拨入库 RK-2");
    }

    @Test
    void rejectsStaleManualResolutionWhenSelectedReceiptIsNotACandidate() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = transferMappings(tenantId, connectorId);
        DhbSyncStore store = storeWithMappings(mappings, List.of(
                transferInboundReceipt("RK-1", "DST-WH", "2026-08-22T10:00:00Z"),
                transferInboundReceipt("RK-2", "ALT-WH", "2026-08-23T10:00:00Z")),
                Map.of("FH-TEST", manualTransferInboundResolution("FH-TEST", "RK-9")));
        DhbOrderSyncService service = service(store);

        assertThatThrownBy(() -> prepareTransferStockOut(service, tenantId, connectorId,
                Map.of("StockID", "SRC-WH",
                        "ShipsDate", "2026-08-21 10:00:00",
                        "list", List.of(transferOutLine("PROD-1", "SKU-1", "5")))))
                .hasMessageContaining("人工裁决选择的入库单已不在当前候选集中");
    }

    @Test
    void resolvesCustomerMappingBySourceNumberWhenPreparingSalesOrder() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "CUSTOMER", "CUSTOMER-SOURCE-ID"),
                mapping("CUSTOMER", "CUSTOMER-SOURCE-ID", "C-001",
                        "CRM", "CUSTOMER", 401L, "CRM202608250001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "PROD-1"),
                mapping("PRODUCT_SPU", "PROD-1", "P-1", "ERP", "PRODUCT", 10L, "SP202608220001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "PROD-1::SKU-1"),
                mapping("PRODUCT_SKU", "PROD-1::SKU-1", "SKU-1",
                        "ERP", "PRODUCT_VARIANT", 11L, "SK202608220001"));
        DhbOrderSyncService service = service(storeWithMappings(mappings));

        SalesOrderCommand command = prepareSalesOrderCommand(service, tenantId, connectorId,
                Map.of("ClientNO", "C-001",
                        "ClientName", "上海客户",
                        "OrderDate", "2026-08-25 10:00:00",
                        "OrderProduct", List.of(orderProductRow(
                                "LINE-1", "PROD-1", "SKU-1", "2", "10.00"))));

        assertThat(command.customerId()).isEqualTo(401L);
        assertThat(command.customerCodeSnapshot()).isEqualTo("CRM202608250001");
        assertThat(command.customerNameSnapshot()).isEqualTo("上海客户");
    }

    @Test
    void rejectsSalesOrderWhenSourceBusinessTimeIsMissing() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "CUSTOMER", "CUSTOMER-SOURCE-ID"),
                mapping("CUSTOMER", "CUSTOMER-SOURCE-ID", "C-001",
                        "CRM", "CUSTOMER", 401L, "CRM202608250001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "PROD-1"),
                mapping("PRODUCT_SPU", "PROD-1", "P-1", "ERP", "PRODUCT", 10L, "SP202608220001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "PROD-1::SKU-1"),
                mapping("PRODUCT_SKU", "PROD-1::SKU-1", "SKU-1",
                        "ERP", "PRODUCT_VARIANT", 11L, "SK202608220001"));
        DhbOrderSyncService service = service(storeWithMappings(mappings));

        assertThatThrownBy(() -> prepareSalesOrderCommand(service, tenantId, connectorId,
                Map.of("ClientNO", "C-001",
                        "ClientName", "上海客户",
                        "OrderProduct", List.of(orderProductRow(
                                "LINE-1", "PROD-1", "SKU-1", "2", "10.00")))))
                .hasMessageContaining("缺少下单/创建时间");
    }

    @Test
    void resolvesProductAndSkuMappingsBySourceNumberWhenPreparingSalesOrderLines() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "PRODUCT-SOURCE-ID"),
                mapping("PRODUCT_SPU", "PRODUCT-SOURCE-ID", "P-001",
                        "ERP", "PRODUCT", 501L, "SP202608250001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "SKU-SOURCE-ID"),
                mapping("PRODUCT_SKU", "SKU-SOURCE-ID", "SKU-001",
                        "ERP", "PRODUCT_VARIANT", 601L, "SK202608250001"));
        DhbOrderSyncService service = service(storeWithMappings(mappings));

        List<SalesOrderLineCommand> lines = salesOrderLines(service, tenantId, connectorId,
                Map.of("OrderProduct", List.of(Map.<String, Object>of(
                        "Coding", "P-001",
                        "OptionsGoodsNo", "SKU-001",
                        "Name", "酸麻粉面菜蛋",
                        "multiName", "箱装",
                        "ContentNumber", "2",
                        "ContentPrice", "10.00",
                        "Units", "箱"))));

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().productId()).isEqualTo(501L);
        assertThat(lines.getFirst().productVariantId()).isEqualTo(601L);
        assertThat(lines.getFirst().productCodeSnapshot()).isEqualTo("SP202608250001");
        assertThat(lines.getFirst().skuCodeSnapshot()).isEqualTo("SK202608250001");
    }

    @Test
    void fillsShipmentLineUnitFromSalesOrderLineWhenDhbShipmentOmitsUnit() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "PROD-1"),
                mapping("PRODUCT_SPU", "PROD-1", "P-1", "ERP", "PRODUCT", 10L, "SP202608220001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "PROD-1::SKU-1"),
                mapping("PRODUCT_SKU", "PROD-1::SKU-1", "SKU-1",
                        "ERP", "PRODUCT_VARIANT", 11L, "SK202608220001"));
        DhbSyncStore store = storeWithMappings(mappings);
        DhbOrderSyncService service = service(store);

        List<SalesShipmentLineCommand> lines = shipmentLines(service, tenantId, connectorId,
                Map.of("list", List.of(Map.<String, Object>of(
                        "goods_id", "PROD-1",
                        "options_id", "SKU-1",
                        "goods_name", "酸麻粉面菜蛋",
                        "ships_number", "2"))),
                salesOrderDetail("SUBMITTED"));

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().salesOrderLineId()).isEqualTo(1L);
        assertThat(lines.getFirst().unitCode()).isEqualTo("BOX");
    }

    @Test
    void usesUniqueSalesOrderLineNameWhenShipmentMappingPointsToDuplicateLegacyProduct() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "OLD-PROD"),
                mapping("PRODUCT_SPU", "OLD-PROD", "100004", "ERP", "PRODUCT", 28L, "PRD-OLD"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "OLD-PROD::0"),
                mapping("PRODUCT_SKU", "OLD-PROD::0", "100004",
                        "ERP", "PRODUCT_VARIANT", 54L, "SKU-OLD"));
        DhbSyncStore store = storeWithMappings(mappings);
        DhbOrderSyncService service = service(store);

        SalesOrderLineView currentLine = new SalesOrderLineView(9L, 1, 52L, 113L,
                "PRD-NEW", "SKU-NEW", "油泼辣子拌面", "箱装", "BOX",
                new BigDecimal("12"), new BigDecimal("10.00"), null, BigDecimal.ZERO,
                new BigDecimal("120.00"), null);
        List<SalesShipmentLineCommand> lines = shipmentLines(service, tenantId, connectorId,
                Map.of("list", List.of(Map.<String, Object>of(
                        "goods_id", "OLD-PROD",
                        "options_id", "0",
                        "goods_name", "油泼辣子拌面",
                        "ships_number", "12"))),
                salesOrderDetail("SUBMITTED", currentLine));

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().salesOrderLineId()).isEqualTo(9L);
        assertThat(lines.getFirst().productId()).isEqualTo(52L);
        assertThat(lines.getFirst().productVariantId()).isEqualTo(113L);
        assertThat(lines.getFirst().productCodeSnapshot()).isEqualTo("PRD-NEW");
        assertThat(lines.getFirst().skuCodeSnapshot()).isEqualTo("SKU-NEW");
        assertThat(lines.getFirst().unitCode()).isEqualTo("BOX");
    }

    @Test
    void usesProductNameAndOptionNameWhenShipmentSkuResolvesToDifferentVariant() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "1167035"),
                mapping("PRODUCT_SPU", "1167035", "100005", "ERP", "PRODUCT", 29L, "PRD-CUE-TIP"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "721960"),
                mapping("PRODUCT_SKU", "721960", "100005-26",
                        "ERP", "PRODUCT_VARIANT", 96L, "SKU-OTHER-H"));
        DhbSyncStore store = storeWithMappings(mappings);
        DhbOrderSyncService service = service(store);

        SalesOrderLineView hLine = new SalesOrderLineView(175L, 1, 29L, 55L,
                "PRD-CUE-TIP", "SKU-H", "专业款皮头", "H", "PIECE",
                new BigDecimal("3"), new BigDecimal("10.00"), null, BigDecimal.ZERO,
                new BigDecimal("30.00"), null);
        SalesOrderLineView mLine = new SalesOrderLineView(176L, 2, 29L, 56L,
                "PRD-CUE-TIP", "SKU-M", "专业款皮头", "M", "PIECE",
                new BigDecimal("2"), new BigDecimal("10.00"), null, BigDecimal.ZERO,
                new BigDecimal("20.00"), null);
        List<SalesShipmentLineCommand> lines = shipmentLines(service, tenantId, connectorId,
                Map.of("list", List.of(Map.<String, Object>of(
                        "goods_id", "1167035",
                        "goods_num", "100005",
                        "goods_name", "专业款皮头",
                        "options_id", "721960",
                        "options_info", List.of(Map.of("options_name", "H")),
                        "ships_number", "3"))),
                salesOrderDetail("SUBMITTED", hLine, mLine));

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().salesOrderLineId()).isEqualTo(175L);
        assertThat(lines.getFirst().productVariantId()).isEqualTo(55L);
        assertThat(lines.getFirst().skuCodeSnapshot()).isEqualTo("SKU-H");
        assertThat(lines.getFirst().unitCode()).isEqualTo("PIECE");
    }

    @Test
    void doesNotUpdateSubmittedSalesOrderWhenSourcePayloadChanges() {
        AtomicBoolean updateCalled = new AtomicBoolean(false);
        OrderSalesOrderProjectionClient projection = proxy(OrderSalesOrderProjectionClient.class,
                (ignoredProxy, method, ignoredArgs) -> {
                    if ("updateSalesOrder".equals(method.getName())) {
                        updateCalled.set(true);
                        throw new AssertionError("submitted sales order must not be updated through draft API");
                    }
                    throw new UnsupportedOperationException("Unexpected projection call: " + method.getName());
                });
        DhbOrderSyncService service = new DhbOrderSyncService(proxy(DhbSyncStore.class),
                proxy(DhbClient.class), projection, proxy(IamDhbStaffSyncClient.class));
        SalesOrderDetailView current = salesOrderDetail("SUBMITTED");
        SalesOrderDetailView result = upsertSalesOrder(service, UUID.randomUUID(), current,
                salesOrderCommand(false), false);

        assertThat(result).isSameAs(current);
        assertThat(updateCalled).isFalse();
    }

    @Test
    void cancelsSubmittedSalesOrderThroughSourceCancellationWhenSourceIsCancelled() {
        AtomicBoolean sourceCancelCalled = new AtomicBoolean(false);
        SalesOrderDetailView current = salesOrderDetail("SUBMITTED");
        SalesOrderDetailView cancelled = salesOrderDetail("CANCELLED");
        OrderSalesOrderProjectionClient projection = proxy(OrderSalesOrderProjectionClient.class,
                (ignoredProxy, method, ignoredArgs) -> {
                    if ("cancelSalesOrderBySource".equals(method.getName())) {
                        sourceCancelCalled.set(true);
                        return cancelled;
                    }
                    if ("cancelSalesOrder".equals(method.getName())
                            || "updateSalesOrder".equals(method.getName())) {
                        throw new AssertionError("source cancellation must not use manual mutation API");
                    }
                    throw new UnsupportedOperationException("Unexpected projection call: " + method.getName());
                });
        DhbOrderSyncService service = new DhbOrderSyncService(proxy(DhbSyncStore.class),
                proxy(DhbClient.class), projection, proxy(IamDhbStaffSyncClient.class));

        SalesOrderDetailView result = upsertSalesOrder(service, UUID.randomUUID(), current,
                salesOrderCommand(false), true);

        assertThat(result.orderStatusCode()).isEqualTo("CANCELLED");
        assertThat(sourceCancelCalled).isTrue();
    }

    private static DhbOrderSyncService service(DhbSyncStore store) {
        return new DhbOrderSyncService(store, proxy(DhbClient.class),
                proxy(OrderSalesOrderProjectionClient.class), proxy(IamDhbStaffSyncClient.class));
    }

    @SuppressWarnings("unchecked")
    private static List<SalesOrderLineCommand> salesOrderLines(
            DhbOrderSyncService service, UUID tenantId, UUID connectorId, Map<String, Object> content) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod("salesOrderLines",
                    UUID.class, UUID.class, String.class, Map.class);
            method.setAccessible(true);
            return (List<SalesOrderLineCommand>) method.invoke(service, tenantId, connectorId,
                    "DHB-TEST", content);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String paymentFundBusinessType(String sourceBusinessType, Map<String, Object> attributes) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod(
                    "paymentFundBusinessTypeCode", String.class, Map.class);
            method.setAccessible(true);
            return (String) method.invoke(null, sourceBusinessType, attributes);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String receiptFundBusinessType(
            String sourceBusinessType, Map<String, Object> attributes, String sourceOrderNo) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod(
                    "receiptFundBusinessTypeCode", String.class, Map.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, sourceBusinessType, attributes, sourceOrderNo);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static FundDocumentCommand prepareReceiptFundDocument(
            DhbOrderSyncService service, UUID tenantId, UUID connectorId,
            String sourceReceiptNo, DhbClient.Receipt receipt, Map<String, Object> attributes) {
        return prepareFundDocument(service, "prepareReceiptFundDocument",
                tenantId, connectorId, sourceReceiptNo, receipt, attributes, DhbClient.Receipt.class);
    }

    private static FundDocumentCommand preparePaymentFundDocument(
            DhbOrderSyncService service, UUID tenantId, UUID connectorId,
            String sourcePaymentNo, DhbClient.Payment payment, Map<String, Object> attributes) {
        return prepareFundDocument(service, "preparePaymentFundDocument",
                tenantId, connectorId, sourcePaymentNo, payment, attributes, DhbClient.Payment.class);
    }

    private static FundDocumentCommand prepareFundDocument(
            DhbOrderSyncService service, String methodName, UUID tenantId, UUID connectorId,
            String sourceDocumentNo, Object document, Map<String, Object> attributes,
            Class<?> documentType) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod(methodName,
                    UUID.class, UUID.class, String.class, documentType, Map.class);
            method.setAccessible(true);
            Object prepared = method.invoke(service, tenantId, connectorId, sourceDocumentNo, document, attributes);
            Method command = prepared.getClass().getDeclaredMethod("command");
            command.setAccessible(true);
            return (FundDocumentCommand) command.invoke(prepared);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String unitCode(String sourceUnit) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod("unitCode", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, sourceUnit);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Observation> sourceUnitObservations(
            String fieldCode, Map<String, Object> payload) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod(
                    "sourceUnitDictionaryObservations", String.class, Map.class);
            method.setAccessible(true);
            return (List<Observation>) method.invoke(null, fieldCode, payload);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String stockOutType(String sourceTypeId, String sourceTypeName) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod(
                    "stockOutTypeCode", DhbClient.Shipment.class, Map.class);
            method.setAccessible(true);
            return (String) method.invoke(null, null, Map.of(
                    "detail", Map.of("type_id", sourceTypeId, "type_name", sourceTypeName),
                    "list", Map.of()));
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Long warehouseId(DhbOrderSyncService service, UUID tenantId, UUID connectorId,
                                    Map<String, Object> content) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod("warehouseId",
                    UUID.class, UUID.class, String.class, DhbClient.Shipment.class, Map.class, Map.class);
            method.setAccessible(true);
            return (Long) method.invoke(service, tenantId, connectorId, "DHB-SHIPMENT-TEST",
                    null, content, Map.of());
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<SalesShipmentLineCommand> shipmentLines(
            DhbOrderSyncService service, UUID tenantId, UUID connectorId,
            Map<String, Object> content, SalesOrderDetailView salesOrder) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod("shipmentLines",
                    UUID.class, UUID.class, String.class, Map.class, SalesOrderDetailView.class);
            method.setAccessible(true);
            return (List<SalesShipmentLineCommand>) method.invoke(service, tenantId, connectorId,
                    "DHB-SHIPMENT-TEST", content, salesOrder);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static SalesOrderDetailView upsertSalesOrder(
            DhbOrderSyncService service, UUID tenantId, SalesOrderDetailView current,
            SalesOrderCommand command, boolean cancelled) {
        try {
            Class<?> preparedType = preparedSalesOrderType();
            var constructor = preparedType
                    .getDeclaredConstructor(String.class, String.class, SalesOrderCommand.class, boolean.class);
            constructor.setAccessible(true);
            Object prepared = constructor.newInstance("DHB-ORDER-1", "stockup", command, cancelled);
            Method method = DhbOrderSyncService.class.getDeclaredMethod("upsertSalesOrder",
                    UUID.class, ExternalObjectMapping.class, SalesOrderDetailView.class, preparedType);
            method.setAccessible(true);
            return (SalesOrderDetailView) method.invoke(service, tenantId, null, current, prepared);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (exception.getCause() instanceof AssertionError assertionError) {
                throw assertionError;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static SalesOrderCommand prepareSalesOrderCommand(
            DhbOrderSyncService service, UUID tenantId, UUID connectorId, Map<String, Object> content) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod("prepareSalesOrder",
                    UUID.class, UUID.class, String.class, DhbClient.OrderSummary.class,
                    DhbClient.OrderDetail.class, Map.class);
            method.setAccessible(true);
            Object prepared = method.invoke(service, tenantId, connectorId, "DHB-ORDER-TEST",
                    null, new DhbClient.OrderDetail("DHB-ORDER-TEST", "stockup",
                            new BigDecimal("20.00"), content),
                    Map.of());
            Method command = prepared.getClass().getDeclaredMethod("command");
            command.setAccessible(true);
            return (SalesOrderCommand) command.invoke(prepared);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (exception.getCause() instanceof AssertionError assertionError) {
                throw assertionError;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object prepareTransferStockOut(
            DhbOrderSyncService service, UUID tenantId, UUID connectorId, Map<String, Object> content) {
        try {
            Method method = DhbOrderSyncService.class.getDeclaredMethod("prepareTransferStockOut",
                    UUID.class, UUID.class, String.class, DhbClient.Shipment.class,
                    DhbClient.ShipmentDetail.class, Map.class, Map.class);
            method.setAccessible(true);
            return method.invoke(service, tenantId, connectorId, "FH-TEST",
                    null, new DhbClient.ShipmentDetail("FH-TEST", content),
                    Map.of("detail", content, "list", Map.of()), new HashMap<String, Object>());
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Long transferTargetWarehouseId(Object prepared) {
        return (Long) transferCommandValue(prepared, "targetWarehouseId");
    }

    private static Boolean transferAffectStockBalance(Object prepared) {
        return (Boolean) transferCommandValue(prepared, "affectStockBalance");
    }

    private static String transferOutboundOperatorStaffName(Object prepared) {
        return (String) transferCommandValue(prepared, "outboundOperatorStaffName");
    }

    private static String transferInboundOperatorStaffName(Object prepared) {
        return (String) transferCommandValue(prepared, "inboundOperatorStaffName");
    }

    private static Object transferCommandValue(Object prepared, String methodName) {
        try {
            Method commandMethod = prepared.getClass().getDeclaredMethod("command");
            commandMethod.setAccessible(true);
            Object command = commandMethod.invoke(prepared);
            Method target = command.getClass().getDeclaredMethod(methodName);
            target.setAccessible(true);
            return target.invoke(command);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String transferRemark(Object prepared) {
        return (String) transferCommandValue(prepared, "remark");
    }

    private static Class<?> preparedSalesOrderType() {
        for (Class<?> type : DhbOrderSyncService.class.getDeclaredClasses()) {
            if ("PreparedSalesOrder".equals(type.getSimpleName())) {
                return type;
            }
        }
        throw new AssertionError("PreparedSalesOrder type not found");
    }

    private static SalesOrderCommand salesOrderCommand(boolean submit) {
        return new SalesOrderCommand(1L, "C001", "客户", null, null, null,
                null, null, null, null, Instant.parse("2026-08-22T00:00:00Z"),
                null, null, null, null, "remark", List.of(salesOrderLineCommand()), submit, null);
    }

    private static SalesOrderDetailView salesOrderDetail(String status) {
        return salesOrderDetail(status, salesOrderLineView());
    }

    private static SalesOrderDetailView salesOrderDetail(String status, SalesOrderLineView... lines) {
        return new SalesOrderDetailView(100L, "SO202608220001", 1L, "C001", "客户",
                null, null, null, null, null, null, null,
                Instant.parse("2026-08-22T00:00:00Z"), status, null, null,
                "UNPAID", "PENDING", new BigDecimal("2"), new BigDecimal("20.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20.00"), BigDecimal.ZERO,
                new BigDecimal("20.00"), null, 3, "DHB_SYNC",
                Instant.parse("2026-08-22T00:00:00Z"), "DHB_SYNC",
                Instant.parse("2026-08-22T00:00:00Z"), List.of(lines));
    }

    private static SalesOrderLineCommand salesOrderLineCommand() {
        return new SalesOrderLineCommand(10L, 11L, "P001", "SKU001", "商品",
                "规格", "BOX", new BigDecimal("2"), new BigDecimal("10.00"),
                null, BigDecimal.ZERO, null);
    }

    private static SalesOrderLineView salesOrderLineView() {
        return new SalesOrderLineView(1L, 1, 10L, 11L, "P001", "SKU001",
                "商品", "规格", "BOX", new BigDecimal("2"), new BigDecimal("10.00"),
                null, BigDecimal.ZERO, new BigDecimal("20.00"), null);
    }

    private static Map<String, Object> orderProductRow(String sourceLineId,
            String productSourceId, String skuSourceId, String quantity, String unitPrice) {
        return orderProductRow(sourceLineId, productSourceId, skuSourceId, quantity, unitPrice, "箱");
    }

    private static Map<String, Object> orderProductRow(String sourceLineId,
            String productSourceId, String skuSourceId, String quantity, String unitPrice, String unit) {
        return Map.of(
                "orders_list_id", sourceLineId,
                "Guid", productSourceId,
                "OptionsGoodsNo", skuSourceId,
                "Name", "酸麻粉面菜蛋",
                "Coding", "P-1",
                "multiName", "箱装",
                "ContentNumber", quantity,
                "ContentPrice", unitPrice,
                "Units", unit);
    }

    private static Map<String, Object> transferOutLine(String productSourceId,
                                                       String skuSourceId,
                                                       String quantity) {
        return Map.of(
                "goods_id", productSourceId,
                "options_id", skuSourceId,
                "goods_name", "调拨商品",
                "ships_number", quantity,
                "Units", "条");
    }

    private static Map<String, Object> transferInboundLine(String productSourceId,
                                                           String skuSourceId,
                                                           String quantity) {
        return Map.of(
                "goods_id", productSourceId,
                "options_id", skuSourceId,
                "goods_name", "调拨商品",
                "warehousing_number", quantity,
                "Units", "条");
    }

    private static Map<MappingKey, ExternalObjectMapping> transferMappings(UUID tenantId, UUID connectorId) {
        Map<MappingKey, ExternalObjectMapping> mappings = new HashMap<>();
        mappings.put(key(tenantId, connectorId, "WAREHOUSE", "SRC-WH"),
                mapping("WAREHOUSE", "SRC-WH", "SRC-WH", "ERP",
                        "INVENTORY_WAREHOUSE", 901L, "WH-SRC"));
        mappings.put(key(tenantId, connectorId, "WAREHOUSE", "DST-WH"),
                mapping("WAREHOUSE", "DST-WH", "DST-WH", "ERP",
                        "INVENTORY_WAREHOUSE", 902L, "WH-DST"));
        mappings.put(key(tenantId, connectorId, "WAREHOUSE", "ALT-WH"),
                mapping("WAREHOUSE", "ALT-WH", "ALT-WH", "ERP",
                        "INVENTORY_WAREHOUSE", 903L, "WH-ALT"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SPU", "PROD-1"),
                mapping("PRODUCT_SPU", "PROD-1", "P-1", "ERP",
                        "PRODUCT", 10L, "SP202608220001"));
        mappings.put(key(tenantId, connectorId, "PRODUCT_SKU", "SKU-1"),
                mapping("PRODUCT_SKU", "SKU-1", "SKU-1", "ERP",
                        "PRODUCT_VARIANT", 11L, "SK202608220001"));
        return mappings;
    }

    private static DhbSyncStore.TransferInboundReceiptCandidate transferInboundReceipt(
            String receiptNo, String warehouseSourceId, String receivedAt) {
        return new DhbSyncStore.TransferInboundReceiptCandidate(UUID.randomUUID(),
                receiptNo, receiptNo, Instant.parse(receivedAt), Instant.parse(receivedAt),
                Map.of("type_id", "8",
                        "type_name", "调拨入库",
                        "warehousing_num", receiptNo,
                        "stock_id", warehouseSourceId,
                        "OperatorName", "入库经办",
                        "storage_date", receivedAt,
                        "list_detail", List.of(transferInboundLine("PROD-1", "SKU-1", "5"))));
    }

    private static ManualResolution manualTransferInboundResolution(String sourceShipmentNo,
                                                                    String selectedReceiptNo) {
        return new ManualResolution(UUID.randomUUID(), "TRANSFER_INBOUND_RECEIPT",
                "ERP_STOCK_OUT", sourceShipmentNo, "WAREHOUSING_RECEIPT", selectedReceiptNo,
                null, null, Map.of("confirmedBy", "unit-test"), "人工核对调拨入库单");
    }

    private static ExternalObjectMapping mapping(String sourceObjectType, String sourceObjectId,
                                                 String sourceObjectNo, String internalDomain,
                                                 String internalObjectType, Long internalObjectId,
                                                 String internalObjectNo) {
        return new ExternalObjectMapping(UUID.randomUUID(), sourceObjectType, sourceObjectId,
                sourceObjectNo, internalDomain, internalObjectType, internalObjectId,
                internalObjectNo, "ACTIVE", "hash");
    }

    private static DhbSyncStore storeWithMappings(Map<MappingKey, ExternalObjectMapping> mappings) {
        return storeWithMappings(mappings, List.of());
    }

    private static DhbSyncStore storeWithMappings(
            Map<MappingKey, ExternalObjectMapping> mappings,
            List<DhbSyncStore.TransferInboundReceiptCandidate> inboundReceipts) {
        return storeWithMappings(mappings, inboundReceipts, Map.of());
    }

    private static DhbSyncStore storeWithMappings(
            Map<MappingKey, ExternalObjectMapping> mappings,
            List<DhbSyncStore.TransferInboundReceiptCandidate> inboundReceipts,
            Map<String, ManualResolution> manualResolutions) {
        return proxy(DhbSyncStore.class, (proxy, method, args) -> {
            if ("findActiveMapping".equals(method.getName()) && args != null && args.length == 4) {
                UUID tenantId = (UUID) args[0];
                UUID connectorId = (UUID) args[1];
                String sourceObjectType = (String) args[2];
                String sourceObjectId = (String) args[3];
                ExternalObjectMapping exact = mappings.get(key(tenantId, connectorId,
                        sourceObjectType, sourceObjectId));
                if (exact != null) return exact;
                for (Map.Entry<MappingKey, ExternalObjectMapping> entry : mappings.entrySet()) {
                    if (entry.getKey().tenantId().equals(tenantId)
                            && entry.getKey().connectorId().equals(connectorId)
                            && entry.getKey().sourceObjectType().equals(sourceObjectType)
                            && sourceObjectId.equals(entry.getValue().sourceObjectNo())) {
                        return entry.getValue();
                    }
                }
                return null;
            }
            if ("findTransferInboundReceiptCandidates".equals(method.getName())) {
                return inboundReceipts;
            }
            if ("findActiveManualResolution".equals(method.getName())) {
                return manualResolutions.get((String) args[4]);
            }
            if ("resolveProjectionIssues".equals(method.getName())) {
                return null;
            }
            if ("resolveRecoveredProjectionIssues".equals(method.getName())) {
                return null;
            }
            throw new UnsupportedOperationException("Unexpected store call: " + method.getName());
        });
    }

    private static MappingKey key(UUID tenantId, UUID connectorId,
                                  String sourceObjectType, String sourceObjectId) {
        return new MappingKey(tenantId, connectorId, sourceObjectType, sourceObjectId);
    }

    private static <T> T proxy(Class<T> type) {
        return proxy(type, (ignoredProxy, method, ignoredArgs) -> {
            throw new UnsupportedOperationException("Unexpected call: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private record MappingKey(UUID tenantId, UUID connectorId,
                              String sourceObjectType, String sourceObjectId) {
    }
}
