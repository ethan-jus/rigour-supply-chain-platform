package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LogisticsLedgerReadPerformanceTest {
    private static final String READ = "order:logistics-cost:read";
    private final OrderLogisticsReferences refs = mock(OrderLogisticsReferences.class);
    private final OrderLogisticsCostStore store = mock(OrderLogisticsCostStore.class);
    private final OrderLogisticsLedgerService ledger =
            new OrderLogisticsLedgerService(refs, mock(OrderLedgerSourceStore.class));
    private final OrderLogisticsCostService costs = new OrderLogisticsCostService(store, refs);
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach void setup() {
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user,
                null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of(READ, "order:logistics-cost:export")));
        costs.ledgerService(ledger);
        when(refs.ledgerParties(any(), any(), isNull(), isNull(), any(), eq(0), any()))
                .thenAnswer(call -> List.of(new LogisticsLedgerPartyView("1", call.getArgument(1),
                        "档案-" + call.getArgument(1), call.getArgument(4), null, null, null, call.getArgument(0))));
    }
    @AfterEach void cleanup() { TestAuthorizationContext.clear(); }

    private LogisticsCostDetailView row(String id, String city) {
        var command = json.readValue("""
                {"cityCode":"%s","amount":20,"ledger":{"schemaVersion":2,
                "sender":{"partyTypeCode":"WAREHOUSE","partyCode":"WH1","cityCode":"%s"},
                "receivingPoints":[{"partyTypeCode":"CUSTOMER","partyCode":"CUS1","cityCode":"%s"}],
                "scopeWarehouseIds":[1],"scopeCityCodes":["%s"],"sources":[]}}
                """.formatted(city, city, city, city), LogisticsCostCommand.class);
        return new LogisticsCostDetailView(id, id, "LOGISTICS", "CONFIRMED", 1, command,
                List.of(), null, null, Instant.EPOCH, Instant.EPOCH);
    }

    @ParameterizedTest @ValueSource(strings = {"read", "export"})
    void repeatedPartiesAcrossTwentyRowsAreResolvedOnceAndScopedTotalsArePreserved(String action) {
        var rows = IntStream.range(0, 20).mapToObj(i -> row("LC" + i, "WH")).toList();
        var page = new LogisticsCostPageView(80, 0, 20, new BigDecimal("1600.00"), BigDecimal.ZERO,
                0, rows, new BigDecimal("1600.00"), 0L, BigDecimal.ZERO, 0L);
        String permission = "order:logistics-cost:" + action;
        when(store.entries(eq(tenant.toString()), eq("LOGISTICS"), any(), eq(permission))).thenReturn(page);
        var query = json.readValue("{\"begin\":0,\"step\":20}", LogisticsCostQuery.class);
        var result = action.equals("export") ? costs.export("logistics", query) : costs.entries("logistics", query);
        assertThat(result.total()).isEqualTo(80);
        assertThat(result.confirmedAmount()).isEqualByComparingTo("1600.00");
        assertThat(result.items()).hasSize(20).allSatisfy(r -> {
            assertThat(r.data().ledger().sender().displayName()).isEqualTo("档案-WH1");
            assertThat(r.data().ledger().receivingPoints().getFirst().displayName()).isEqualTo("档案-CUS1");
            assertThat(r.data().ledger().scopeWarehouseIds()).containsExactly(1L);
        });
        verify(refs).ledgerParties("WAREHOUSE", "WH1", null, null, "WH", 0, permission);
        verify(refs).ledgerParties("CUSTOMER", "CUS1", null, null, "WH", 0, permission);
        verifyNoMoreInteractions(refs);
    }

    @Test void differentCityScopesAreNotMergedAndEachRequestReadsFreshNames() {
        ledger.enrich(List.of(row("LC1", "WH"), row("LC2", "BJ")), READ);
        verify(refs).ledgerParties("CUSTOMER", "CUS1", null, null, "WH", 0, READ);
        verify(refs).ledgerParties("CUSTOMER", "CUS1", null, null, "BJ", 0, READ);
        when(refs.ledgerParties("CUSTOMER", "CUS1", null, null, "WH", 0, READ))
                .thenReturn(List.of(new LogisticsLedgerPartyView("1", "CUS1", "新名称", "WH", null, null, null, "CUSTOMER")));
        assertThat(ledger.enrich(row("LC3", "WH").data(), READ).ledger().receivingPoints().getFirst().displayName())
                .isEqualTo("新名称");
        verify(refs, times(2)).ledgerParties("CUSTOMER", "CUS1", null, null, "WH", 0, READ);
    }

    @Test void missingReferencesUseCodesWithoutRepeatingFailedLookups() {
        when(refs.ledgerParties(any(), any(), isNull(), isNull(), any(), eq(0), eq(READ))).thenReturn(List.of());
        var rows = ledger.enrich(List.of(row("LC1", "WH"), row("LC2", "WH")), READ);
        assertThat(rows.getFirst().data().ledger().receivingPoints().getFirst().displayName()).isEqualTo("CUS1");
        verify(refs).ledgerParties("CUSTOMER", "CUS1", null, null, "WH", 0, READ);
    }

    @Test void referencePermissionFailuresAreNotConvertedIntoCachedNames() {
        when(refs.ledgerParties("CUSTOMER", "CUS1", null, null, "WH", 0, READ))
                .thenThrow(new AuthorizationDeniedException(READ));
        assertThatThrownBy(() -> ledger.enrich(List.of(row("LC1", "WH")), READ))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test void interactivePartySelectionStillValidatesTheOperatingCity() {
        ledger.parties("CUSTOMER", "CUS1", null, null, "WH", 0, READ);
        verify(refs).city("WH", READ);
        verify(refs).ledgerParties("CUSTOMER", "CUS1", null, null, "WH", 0, READ);
    }

    @Test void selectedExportUsesExportScopeAndReturnsOnlyRequestedRowsInSelectionOrder() {
        String action = "order:logistics-cost:export";
        when(store.selectedEntries(tenant.toString(), List.of("LC2", "LC1"), action))
                .thenReturn(List.of(row("LC1", "WH"), row("LC2", "WH")));
        var result = costs.exportSelected(List.of("LC2", "LC1"));
        assertThat(result).extracting(LogisticsCostDetailView::id).containsExactly("LC2", "LC1");
        verify(refs).ledgerParties("CUSTOMER", "CUS1", null, null, "WH", 0, action);
        verify(store, never()).entries(any(), any(), any(), any());
    }

    @Test void missingOrUnauthorizedSelectedRowsFailInsteadOfFallingBackToExportAll() {
        when(store.selectedEntries(any(), any(), any())).thenReturn(List.of(row("LC1", "WH")));
        assertThatThrownBy(() -> costs.exportSelected(List.of("LC1", "HIDDEN")))
                .hasMessageContaining("无导出权限", "重新勾选");
        verify(store, never()).entries(any(), any(), any(), any());
        verifyNoInteractions(refs);
    }

    @Test void invalidSelectionNeverExpandsToAllRows() {
        for (List<String> ids : List.of(List.<String>of(), List.of("LC1", "LC1"), List.of(" "),
                IntStream.range(0, 201).mapToObj(i -> "LC" + i).toList())) {
            assertThatThrownBy(() -> costs.exportSelected(ids)).hasMessageContaining("1～200");
        }
        verifyNoInteractions(store, refs);
    }

    @Test void readPermissionCannotBeUsedForSelectedExport() {
        var caller = AuthorizationContext.requireCurrent();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", caller.principalId(), tenant, caller.userId(),
                null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of(READ)));
        assertThatThrownBy(() -> costs.exportSelected(List.of("LC1")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, refs);
    }
}
