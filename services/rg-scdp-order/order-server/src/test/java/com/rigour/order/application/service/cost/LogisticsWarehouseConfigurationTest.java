package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.LogisticsLedgerPartyView;
import com.rigour.order.application.port.out.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class LogisticsWarehouseConfigurationTest {
    final OrderLogisticsReferences refs = mock(OrderLogisticsReferences.class);
    final OrderLogisticsLedgerService service =
            new OrderLogisticsLedgerService(refs, mock(OrderLedgerSourceStore.class));
    static final String ACTION = "order:logistics-cost:read";

    LogisticsLedgerPartyView warehouse(String code, String city) {
        return new LogisticsLedgerPartyView(
                "1", code, "仓库-" + code, city, "地址", null, null, "WAREHOUSE");
    }

    @Test
    void authorizedWarehouseChoicesExplainDirtyMissingAndUnauthorizedCitiesSeparately() {
        var rows =
                List.of(
                        warehouse("W1", "DIRTY"),
                        warehouse("W2", null),
                        warehouse("W3", "BJ"),
                        warehouse("W4", "WH"));
        when(refs.ledgerParties("WAREHOUSE", null, null, "", null, 0, ACTION)).thenReturn(rows);
        when(refs.cityChecks(List.of("DIRTY", "BJ", "WH"), ACTION))
                .thenReturn(
                        List.of(
                                new OrderLogisticsReferences.CityCheck(
                                        "DIRTY",
                                        false,
                                        false,
                                        "业务归属地区“散客”（DIRTY）不是经营城市，请在仓库信息中修改业务归属地区"),
                                new OrderLogisticsReferences.CityCheck("BJ", true, true),
                                new OrderLogisticsReferences.CityCheck("WH", true, false)));
        var result = service.parties("WAREHOUSE", null, null, "", null, 0, ACTION);
        assertThat(result)
                .extracting(LogisticsLedgerPartyView::code)
                .containsExactly("W1", "W2", "W3", "W4");
        assertThat(result.get(0).referenceIssue()).contains("W1", "归属地区", "仓库信息");
        assertThat(result.get(1).referenceIssue()).contains("W2", "归属地区");
        assertThat(result.get(2).referenceIssue()).isNull();
        assertThat(result.get(3).referenceIssue()).contains("授权范围").doesNotContain("未配置");
    }

    @Test
    void searchContainingOnlyWarehousesWithoutRegionsReturnsExplicitHints() {
        when(refs.ledgerParties("WAREHOUSE", null, null, "仓", null, 0, ACTION))
                .thenReturn(List.of(warehouse("W1", null), warehouse("W2", " ")));
        assertThat(service.parties("WAREHOUSE", null, null, "仓", null, 0, ACTION))
                .allSatisfy(row -> assertThat(row.referenceIssue())
                        .contains(row.name(), row.code(), "未维护业务归属地区", "仓库信息"));
        verify(refs, never()).cityChecks(any(), any());
    }

    @Test
    void noWarehouseOutsideTheOriginalAuthorizedResultCanBeAdded() {
        when(refs.ledgerParties("WAREHOUSE", null, null, "", null, 0, ACTION))
                .thenReturn(List.of());
        assertThat(service.parties("WAREHOUSE", null, null, "", null, 0, ACTION)).isEmpty();
        verify(refs).ledgerParties("WAREHOUSE", null, null, "", null, 0, ACTION);
        verifyNoMoreInteractions(refs);
    }

    com.rigour.order.api.v1.model.LogisticsCostCommand command() {
        return tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readValue(
                        """
                        {"cityCode":"BJ","ledger":{"schemaVersion":2,"transportPurposeCode":"OTHER",
                        "nonSalesPurposeCode":"运输测试","deliveryModeCode":"SELF","costMonth":"2026-11",
                        "sender":{"partyTypeCode":"WAREHOUSE","partyCode":"W1","cityCode":"DIRTY"},
                        "receivingPoints":[{"pointCode":"TO1","partyTypeCode":"OTHER","manualName":"收货点","cityCode":"BJ"}],
                        "feeItems":[{"feeKey":"F1","feeTypeCode":"FREIGHT","feeAmount":1,"ourAmount":1,"bearerCode":"COMPANY","allocationMethodCode":"MANUAL"}],
                        "sources":[{"orderTypeCode":"NONE","linkRoleCode":"COST","noSourceReason":"人工配送","allocations":[{"feeKey":"F1","receivingPointCode":"TO1","cityCode":"BJ","amount":1}]}]}}
                        """,
                        com.rigour.order.api.v1.model.LogisticsCostCommand.class);
    }

    @Test
    void saveExplainsWhichWarehouseAndRegionFailed() {
        when(refs.ledgerParties("WAREHOUSE", "W1", null, null, "DIRTY", 0, ACTION))
                .thenReturn(List.of(warehouse("W1", "DIRTY")));
        when(refs.city("DIRTY", ACTION))
                .thenThrow(
                        new com.rigour.shared.core.exception.BusinessException(
                                com.rigour.shared.core.api.ErrorCode.BAD_REQUEST,
                                "业务归属地区“散客”（DIRTY）不是经营城市，请在仓库信息中修改业务归属地区",
                                List.of()));
        assertThatThrownBy(() -> service.normalize(command(), ACTION, false))
                .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class)
                .hasMessageContaining("仓库-W1")
                .hasMessageContaining("W1")
                .hasMessageContaining("散客")
                .hasMessageContaining("DIRTY");
    }

    @Test
    void saveChecksWarehouseAuthorizationBeforeReportingConfiguration() {
        when(refs.ledgerParties("WAREHOUSE", "W1", null, null, "DIRTY", 0, ACTION))
                .thenThrow(new com.rigour.shared.context.AuthorizationDeniedException(ACTION));
        assertThatThrownBy(() -> service.normalize(command(), ACTION, false))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        verify(refs, never()).city(any(), any());
    }
}
