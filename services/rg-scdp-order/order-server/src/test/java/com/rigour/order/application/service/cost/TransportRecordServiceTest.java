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
                        "{\"id\":\"C1\",\"revision\":1,\"statusCode\":\"DRAFT\",\"data\":{\"requestId\":\""
                                + UUID.randomUUID()
                                + "\",\"amount\":137.64},\"sources\":[]}",
                        LogisticsCostDetailView.class);
        when(costs.entry("logistics", "C1")).thenReturn(record);
        when(refs.city(any(), any())).thenReturn("武汉");
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
                "3624071813921329726",
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
                UUID.randomUUID().toString());
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
    void editedPlatformAmountIsRejectedAtTheServiceBoundary() {
        when(platform.detail(any(), any())).thenReturn(source("137.64"));
        assertThatThrownBy(() -> service.save("C1", draft("1.00", List.of())))
                .hasMessageContaining("只读");
        verify(platform, never()).saveDraft(any(), any(), any(), any(), any());
    }

    @Test
    void confirmOnlyPermissionUsesLatestBillAndExactCentAllocationWithoutCallingEdit() {
        actor("order:logistics-cost:read", "order:logistics-cost:confirm");
        var p = source("142.64");
        var d = draft("137.64", List.of("SO002", "SO001", "SO003"));
        when(platform.detail(any(), any()))
                .thenReturn(new PlatformRecord(p.source(), d, List.of()));
        when(ledgers.sources(any(), eq("order:logistics-cost:confirm")))
                .thenAnswer(
                        i -> {
                            var query = (LogisticsLedgerSourceQuery) i.getArgument(0);
                            return List.of(
                                    json.readValue(
                                            "{\"documentNo\":\""
                                                    + query.documentNo()
                                                    + "\",\"sourceType\":\"SALES_ORDER\",\"cityCode\":\"WH\",\"receiverCode\":\"CUS1\",\"receiverName\":\"星河门店\",\"contactName\":\"王先生\",\"contactPhone\":\"13900000001\",\"destinationAddress\":\"沿江大道18号\"}",
                                            LogisticsCostSourceView.class));
                        });
        when(costs.preparePlatformConfirmation(eq("C1"), any())).thenReturn(record);
        service.confirm("C1", new LogisticsCostActionCommand(1, "已核对平台账单"));
        var captured = org.mockito.ArgumentCaptor.forClass(LogisticsCostCommand.class);
        verify(costs).preparePlatformConfirmation(eq("C1"), captured.capture());
        var command = captured.getValue();
        assertThat(command.amount()).isEqualByComparingTo("142.64");
        assertThat(command.ledger().sender().address()).isEqualTo("武汉市仓储路18号");
        assertThat(
                        command.ledger().sources().stream()
                                .map(s -> s.allocations().getFirst().amount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("142.64");
        assertThat(command.ledger().sources().getFirst().orderCode()).isEqualTo("SO001");
        verify(costs, never()).save(any(), any(), any());
        verify(costs).action(eq("logistics"), eq("C1"), any(), eq(true));
    }

    @Test
    void draftReadCannotGrantVoucherAccessAcrossTenants() {
        var p = source("137.64");
        when(platform.detail(any(), any())).thenReturn(p);
        assertThatThrownBy(() -> service.voucher("C1", "unknown")).hasMessageContaining("稍后重试");
        verifyNoInteractions(files);
    }
}
