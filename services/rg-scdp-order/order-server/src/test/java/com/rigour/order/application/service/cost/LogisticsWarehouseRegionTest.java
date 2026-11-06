package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.LogisticsCostCommand;
import com.rigour.order.api.v1.model.LogisticsLedgerPartyView;
import com.rigour.order.application.port.out.OrderLedgerSourceStore;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogisticsWarehouseRegionTest {
    private final OrderLogisticsReferences refs = mock(OrderLogisticsReferences.class);
    private final OrderLogisticsLedgerService service = new OrderLogisticsLedgerService(refs, mock(OrderLedgerSourceStore.class));
    private static final String ACTION = "order:logistics-cost:write";
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void setup() {
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, UUID.randomUUID(), user,
                null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of(ACTION)));
        when(refs.validCodes(any(), eq("COST_LOGISTICS_FEE_TYPE"))).thenReturn(Set.of("FREIGHT"));
    }
    @AfterEach void cleanup() { TestAuthorizationContext.clear(); }

    private LogisticsCostCommand command(boolean warehouseSender) {
        String warehouse = """
                {"pointCode":"P1","partyTypeCode":"WAREHOUSE","partyCode":"WH1","cityCode":"WH","contactName":"王先生","phone":"13800000000","address":"仓库地址"}
                """;
        String other = warehouse.replace("\"WAREHOUSE\",\"partyCode\":\"WH1\"", "\"OTHER\",\"manualName\":\"门店\"");
        return json.readValue("""
                {"cityCode":"WH","costDate":"2026-11-04","paymentStatusCode":"UNPAID","ledger":{
                "schemaVersion":2,"transportPurposeCode":"DELIVERY","deliveryModeCode":"SELF","costMonth":"2026-11",
                "sender":%s,"receivingPoints":[%s],
                "feeItems":[{"feeKey":"F1","feeTypeCode":"FREIGHT","feeAmount":20,"bearerCode":"COMPANY","ourAmount":20,"allocationMethodCode":"MANUAL"}],
                "sources":[{"orderTypeCode":"NONE","linkRoleCode":"COST","noSourceReason":"独立运输","allocations":[{"feeKey":"F1","receivingPointCode":"P1","cityCode":"WH","amount":20}]}]}}
                """.formatted(warehouseSender ? warehouse : other, warehouseSender ? other : warehouse), LogisticsCostCommand.class);
    }
    private void warehouse(String city) {
        when(refs.ledgerParties(eq("WAREHOUSE"), eq("WH1"), isNull(), isNull(), eq("WH"), eq(0), eq(ACTION)))
                .thenReturn(List.of(new LogisticsLedgerPartyView("1", "WH1", "武汉仓", city, null, null, null, "WAREHOUSE")));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" "})
    void missingBusinessRegionCannotBeReplacedWithTheCityEnteredOnTheForm(String city) {
        warehouse(city);
        for (boolean sender : List.of(true, false)) {
            assertThatThrownBy(() -> service.normalize(command(sender), ACTION, false))
                    .hasMessageContaining("武汉仓（WH1）", "未维护业务归属地区", "仓库信息", "所在地区不能替代");
            assertThatThrownBy(() -> service.normalize(command(sender), ACTION, true))
                    .hasMessageContaining("未维护业务归属地区");
        }
    }
    @Test void changingTheArchiveThenRetryingUsesTheFreshRegionAndKeepsWarehouseScope() {
        warehouse(null);
        var draft = command(false);
        assertThatThrownBy(() -> service.normalize(draft, ACTION, false)).hasMessageContaining("未维护");
        warehouse("WH");
        var saved = service.normalize(draft, ACTION, false);
        assertThat(saved.ledger().receivingPoints().getFirst().cityCode()).isEqualTo("WH");
        assertThat(saved.ledger().scopeWarehouseIds()).containsExactly(1L);
        assertThat(saved.ledger().scopeCityCodes()).containsExactly("WH");
        verify(refs, atLeastOnce()).city("WH", ACTION);
    }
    @Test void cityMismatchIsRejectedInsteadOfOverwritingTheWarehouseRegion() {
        warehouse("BJ");
        assertThatThrownBy(() -> service.normalize(command(false), ACTION, false))
                .hasMessageContaining("业务归属地区与所填运输城市不一致");
    }
}
