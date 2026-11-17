package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class TransportRecordServiceTest {
    final OrderLogisticsCostService costs = mock(OrderLogisticsCostService.class);
    final OrderLogisticsLedgerService ledgers = mock(OrderLogisticsLedgerService.class);
    final OrderLogisticsReferences refs = mock(OrderLogisticsReferences.class);
    final PlatformTransportStore platform = mock(PlatformTransportStore.class);
    final CostAttachmentStorage files = mock(CostAttachmentStorage.class);
    final TransportRecordService service =
            new TransportRecordService(costs, ledgers, refs, platform, files);
    final JsonMapper json = JsonMapper.builder().build();
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    LogisticsCostDetailView record;

    @BeforeEach
    void setup() {
        actor(
                "order:logistics-cost:write",
                "order:logistics-cost:read",
                "order:logistics-cost:confirm");
        record =
                json.readValue(
                        "{\"id\":\"C1\",\"revision\":1,\"statusCode\":\"ACTIVE\",\"data\":{\"requestId\":\""
                                + UUID.randomUUID()
                                + "\",\"amount\":137.64},\"sources\":[]}",
                        LogisticsCostDetailView.class);
        when(costs.entry("logistics", "C1")).thenReturn(record);
        when(refs.city(any(), any())).thenReturn("武汉");
        lenient().when(costs.save(any(), any(), any())).thenReturn(record);
        when(refs.validCodes(anyString(), eq("COST_DELIVERY_MODE")))
                .thenReturn(Set.of("SELF", "THIRD_PARTY"));
    }

    void actor(String... permissions) {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(permissions)));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    PlatformRecord source(String amount) {
        var tree =
                json.readTree(
                        "{\"enterpriseId\":\"1001\",\"orderNo\":\"3624071813921329726\",\"useTime\":\"2026-11-10"
                            + " 10:00:00\",\"raw\":{},\"salesOrderNos\":[],\"line\":0,\"facts\":{\"billAmount\":"
                                + amount
                                + ",\"billState\":\"FINAL\",\"fees\":[],\"stops\":[{\"name\":\"武汉仓\",\"address\":\"武汉市仓储路18号\"}]}}");
        var order = json.treeToValue(tree, CarrierOrder.class);
        var row =
                new Row(
                        "R1",
                        1,
                        "GENERATED",
                        "C1",
                        "接口",
                        order,
                        "API",
                        false,
                        order,
                        Instant.now(),
                        null,
                        null);
        return new PlatformRecord(row, null, List.of());
    }

    Draft draft(String amount, List<String> codes) {
        return new Draft(
                1,
                "DELIVERY",
                LocalDate.of(2026, 11, 10),
                "HLL",
                null,
                "3624071813921329726",
                null,
                null,
                null,
                null,
                null,
                "WH",
                new BigDecimal(amount),
                null,
                "到仓配送",
                "",
                "",
                codes,
                UUID.randomUUID().toString(),
                null,
                null);
    }

    @Test
    void platformDraftCanSaveNotesWithoutInventingAnOrderOrCost() {
        var p = source("137.64");
        when(platform.detail(any(), any())).thenReturn(p);
        var draft = draft("137.64", List.of());
        service.save("C1", draft);
        verify(platform).saveDraft(tenant.toString(), "C1", draft, List.of(), user.toString());
        verify(costs, never()).save(any(), any(), any());
        verify(costs, never()).action(any(), any(), any(), anyBoolean());
    }

    @Test
    void platformAllocationsAreValidatedBeforeSavingAndUnknownBillRemainsUnallocated() {
        when(platform.detail(any(), any())).thenReturn(source("137.64"));
        when(ledgers.salesSources(anyList(), anyString(), eq(false)))
                .thenReturn(
                        List.of(
                                json.readValue(
                                        "{\"documentNo\":\"SO1\",\"sourceType\":\"SALES_ORDER\",\"documentAmount\":6000}",
                                        LogisticsCostSourceView.class),
                                json.readValue(
                                        "{\"documentNo\":\"SO2\",\"sourceType\":\"SALES_ORDER\",\"documentAmount\":4000}",
                                        LogisticsCostSourceView.class)));
        var input = draft("137.64", List.of("SO2", "SO1"));
        var manual = Map.of("SO1", new BigDecimal("80"), "SO2", new BigDecimal("57.64"));
        service.saveWithPendingEvidence("C1", input.withAllocations(manual));
        var saved = org.mockito.ArgumentCaptor.forClass(Draft.class);
        verify(platform).saveAssociations(any(), eq("C1"), saved.capture(), any(), any());
        assertThat(saved.getValue().orderAllocations().get("SO1")).isEqualByComparingTo("80");
        clearInvocations(platform);
        assertThatThrownBy(
                        () ->
                                service.saveWithPendingEvidence(
                                        "C1", input.withAllocations(Map.of("SO1", BigDecimal.ONE))))
                .hasMessageContaining("全部关联");
        verify(platform, never()).saveAssociations(any(), any(), any(), any(), any());
        when(platform.detail(any(), any())).thenReturn(source("null"));
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(input);
        node.putNull("amount");
        service.saveWithPendingEvidence("C1", json.treeToValue(node, Draft.class));
        verify(platform).saveAssociations(any(), eq("C1"), saved.capture(), any(), any());
        assertThat(saved.getValue().orderAllocations()).isNull();
    }

    @Test
    void businessCostMayBeEditedWithoutChangingThePlatformBill() {
        var p = source("137.64");
        when(platform.detail(any(), any())).thenReturn(p);
        service.save("C1", draft("1.00", List.of()));
        var captured = org.mockito.ArgumentCaptor.forClass(Draft.class);
        verify(platform).saveDraft(any(), eq("C1"), captured.capture(), any(), any());
        assertThat(captured.getValue().amount()).isEqualByComparingTo("1.00");
        assertThat(p.source().order().facts().billAmount()).isEqualByComparingTo("137.64");
    }

    @Test
    void oldConfirmationEndpointCannotRestoreTheRemovedWorkflow() {
        assertThatThrownBy(() -> service.confirm("C1", new LogisticsCostActionCommand(1, "")))
                .hasMessageContaining("无需确认");
        verify(costs, never()).action(any(), any(), any(), anyBoolean());
    }

    @Test
    void draftReadCannotGrantVoucherAccessAcrossTenants() {
        var p = source("137.64");
        when(platform.detail(any(), any())).thenReturn(p);
        assertThatThrownBy(() -> service.voucher("C1", "unknown")).hasMessageContaining("稍后重试");
        verifyNoInteractions(files);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"HLL", "SF", "JT_NEW"})
    void manualWaybillAndThirdPartyOrderNumberArePersistedIndependently(String carrier) {
        var input = draft("100.01", List.of("SO1", "SO2", "SO3"));
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(input);
        node.put("carrierCode", carrier);
        node.put("trackingNo", "WAYBILL-001");
        node.put("shippingTime", "2026-11-10T10:22:33");
        node.put("paymentStatusCode", "PAID");
        node.set(
                "manualBillItems",
                json.readTree(
                        "[{\"name\":\"运费\",\"amount\":100},{\"name\":\"保价费\",\"amount\":5.01},{\"name\":\"优惠\",\"amount\":-5}]"));
        node.put("amount", 999);
        node.put("senderName", "武汉仓");
        node.put("senderAddress", "武汉市仓储路18号");
        var points = json.createArrayNode();
        for (int index = 0; index < 3; index++)
            points.add(
                    json.readTree(
                            "{\"pointCode\":\"TO"
                                    + index
                                    + "\",\"partyTypeCode\":\"CUSTOMER\",\"partyCode\":null,\"manualName\":\"星河门店\",\"contactName\":\"王先生\",\"phone\":\"13900000000\",\"address\":\"武汉市收货路1号\",\"cityCode\":\"WH\"}"));
        node.set("receivingPoints", points);
        var d = json.treeToValue(node, Draft.class);
        when(ledgers.salesSources(anyList(), anyString(), anyBoolean()))
                .thenAnswer(
                        i -> {
                            List<String> codes = i.getArgument(0);
                            return codes.stream()
                                    .map(
                                            code ->
                                                    json.readValue(
                                                            "{\"documentNo\":\""
                                                                    + code
                                                                    + "\",\"sourceType\":\"SALES_ORDER\",\"cityCode\":\"WH\",\"receiverName\":\"星河门店\",\"documentAmount\":800.50,\"documentDate\":\"2026-11-09\"}",
                                                            LogisticsCostSourceView.class))
                                    .toList();
                        });
        when(costs.save(eq("logistics"), isNull(), any())).thenReturn(record);
        service.saveWithPendingEvidence(null, d);
        var capture = org.mockito.ArgumentCaptor.forClass(LogisticsCostCommand.class);
        verify(costs).save(eq("logistics"), isNull(), capture.capture());
        var command =
                json.readValue(
                        json.writeValueAsString(capture.getValue()), LogisticsCostCommand.class);
        var ledger =
                command.ledger()
                        .withScopeCities(List.of("WH"))
                        .withSources(command.ledger().sources())
                        .withSourceSync(null)
                        .withTransportType("DELIVERY");
        assertThat(command.paymentStatusCode()).isEqualTo("PAID");
        assertThat(command.amount()).isEqualByComparingTo("100.01");
        assertThat(ledger.manualBillItems()).hasSize(3);
        assertThat(ledger.shippingTime()).isEqualTo(LocalDateTime.of(2026, 11, 10, 10, 22, 33));
        assertThat(ledger.trackingNo()).isEqualTo("WAYBILL-001");
        assertThat(ledger.carrierCode()).isEqualTo(carrier);
        assertThat(ledger.platformOrderNo()).isEqualTo("3624071813921329726");
        assertThat(ledger.sources())
                .allSatisfy(
                        source -> {
                            assertThat(source.documentAmount()).isEqualByComparingTo("800.50");
                            assertThat(source.documentDate()).isEqualTo(LocalDate.of(2026, 11, 9));
                        });
        assertThat(
                        ledger.sources().stream()
                                .flatMap(source -> source.allocations().stream())
                                .map(a -> a.amount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.01");
    }

    @Test
    void manualShippingTimeMustMatchItsBusinessDateAndHaveSecondPrecision() {
        var node =
                (tools.jackson.databind.node.ObjectNode)
                        json.valueToTree(draft("3", List.of("SO1")));
        node.put("shippingTime", "2026-11-11T10:22:33");
        assertThatThrownBy(
                        () ->
                                service.saveWithPendingEvidence(
                                        null, json.treeToValue(node, Draft.class)))
                .hasMessageContaining("发货时间");
        node.put("shippingTime", "2026-11-10T10:22:33.123");
        assertThatThrownBy(
                        () ->
                                service.saveWithPendingEvidence(
                                        null, json.treeToValue(node, Draft.class)))
                .hasMessageContaining("精确到秒");
        verify(costs, never()).save(anyString(), any(), any());
    }

    @Test
    void suppliedWaybillCannotMasqueradeAsThePlatformOrderWhenEditingApiData() {
        when(platform.detail(any(), any())).thenReturn(source("137.64"));
        var tree =
                (tools.jackson.databind.node.ObjectNode)
                        json.valueToTree(draft("137.64", List.of()));
        tree.put("trackingNo", "invented-waybill");
        assertThatThrownBy(() -> service.save("C1", json.treeToValue(tree, Draft.class)))
                .hasMessageContaining("只读");
        verify(platform, never()).saveDraft(any(), any(), any(), any(), any());
    }

    @Test
    void platformBillAndPaymentCannotBeChangedThroughManualDraft() {
        when(platform.detail(any(), any())).thenReturn(source("137.64"));
        var node =
                (tools.jackson.databind.node.ObjectNode)
                        json.valueToTree(draft("137.64", List.of()));
        node.put("paymentStatusCode", "PAID");
        assertThatThrownBy(() -> service.save("C1", json.treeToValue(node, Draft.class)))
                .hasMessageContaining("付款信息只读");
        node.remove("paymentStatusCode");
        node.set("manualBillItems", json.readTree("[{\"name\":\"手改运费\",\"amount\":137.64}]"));
        assertThatThrownBy(() -> service.save("C1", json.treeToValue(node, Draft.class)))
                .hasMessageContaining("账单明细只读");
        verify(platform, never()).saveDraft(any(), any(), any(), any(), any());
    }

    @Test
    void receiptAndLoadingPhotosUseOnlyAuthorizedOwnCosReferencesForPreviewAndDownload() {
        var p = source("137.64");
        var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(p);
        tree.withObject("source").putNull("latestOrder");
        tree.withObject("source")
                .withObject("order")
                .withObject("facts")
                .set(
                        "stopPhotos",
                        json.readTree(
                                "[{\"stopIndex\":0,\"name\":\"武汉仓\",\"phase\":\"装货照片\",\"vouchers\":[{\"id\":\"P1\",\"name\":\"装货照片.jpg\",\"state\":\"READY\",\"fileKey\":\""
                                        + tenant
                                        + "/integration-huolala-vouchers/C/O/P1.jpg\"}]}]"));
        when(platform.detail(any(), any()))
                .thenReturn(json.treeToValue(tree, PlatformRecord.class));
        when(files.url(anyString())).thenReturn("https://own-cos/preview");
        when(files.downloadUrl(anyString(), anyString())).thenReturn("https://own-cos/download");
        assertThat(service.voucher("C1", "P1")).isEqualTo("https://own-cos/preview");
        assertThat(service.voucher("C1", "P1", true)).isEqualTo("https://own-cos/download");
        verify(files).downloadUrl(tenant + "/integration-huolala-vouchers/C/O/P1.jpg", "装货照片.jpg");
        ((tools.jackson.databind.node.ObjectNode)
                        tree.at("/source/order/facts/stopPhotos/0/vouchers/0"))
                .put("fileKey", UUID.randomUUID() + "/integration-huolala-vouchers/C/O/P1.jpg");
        when(platform.detail(any(), any()))
                .thenReturn(json.treeToValue(tree, PlatformRecord.class));
        assertThatThrownBy(() -> service.voucher("C1", "P1", true)).hasMessageContaining("稍后重试");
        verify(costs, never()).save(any(), any(), any());
    }
}
