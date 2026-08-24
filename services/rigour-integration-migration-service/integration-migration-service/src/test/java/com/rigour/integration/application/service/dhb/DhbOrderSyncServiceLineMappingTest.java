package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.port.out.DhbSyncStore.ExternalObjectMapping;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesShipmentLineCommand;
import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static ExternalObjectMapping mapping(String sourceObjectType, String sourceObjectId,
                                                 String sourceObjectNo, String internalDomain,
                                                 String internalObjectType, Long internalObjectId,
                                                 String internalObjectNo) {
        return new ExternalObjectMapping(UUID.randomUUID(), sourceObjectType, sourceObjectId,
                sourceObjectNo, internalDomain, internalObjectType, internalObjectId,
                internalObjectNo, "ACTIVE", "hash");
    }

    private static DhbSyncStore storeWithMappings(Map<MappingKey, ExternalObjectMapping> mappings) {
        return proxy(DhbSyncStore.class, (proxy, method, args) -> {
            if ("findActiveMapping".equals(method.getName()) && args != null && args.length == 4) {
                return mappings.get(key((UUID) args[0], (UUID) args[1],
                        (String) args[2], (String) args[3]));
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
