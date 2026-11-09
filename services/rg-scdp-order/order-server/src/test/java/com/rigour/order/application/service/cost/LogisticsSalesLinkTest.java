package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.*;

class LogisticsSalesLinkTest {
    final OrderLogisticsReferences refs = mock(OrderLogisticsReferences.class);
    final OrderLedgerSourceStore sources = mock(OrderLedgerSourceStore.class);
    final OrderLogisticsLedgerService service = new OrderLogisticsLedgerService(refs, sources);
    final JsonMapper json = JsonMapper.builder().build();
    final String action = "order:logistics-cost:write";

    @BeforeEach
    void setup() {
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.randomUUID(),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(action)));
        when(refs.validCodes(any(), eq("COST_LOGISTICS_FEE_TYPE"))).thenReturn(Set.of("FREIGHT"));
        when(refs.cities(action)).thenReturn(Map.of("WH", "武汉市"));
        when(refs.ledgerParties(eq("WAREHOUSE"), any(), any(), any(), any(), anyInt(), eq(action)))
                .thenAnswer(
                        call ->
                                List.of(
                                        new LogisticsLedgerPartyView(
                                                "1",
                                                call.getArgument(1) == null
                                                        ? "WH1"
                                                        : call.getArgument(1),
                                                "武汉仓",
                                                "WH",
                                                "仓库地址",
                                                null,
                                                null,
                                                "WAREHOUSE")));
        when(refs.ledgerParties(eq("CUSTOMER"), any(), any(), any(), any(), anyInt(), eq(action)))
                .thenAnswer(call -> List.of(customer(call.getArgument(1), true)));
        when(refs.ledgerCustomers(eq("WH"), anyList(), eq(action)))
                .thenReturn(List.of(customer("CUS1", true)));
        when(sources.sales(any(), any(), eq(action), isNull()))
                .thenReturn(
                        List.of(
                                new OrderDeliveryCostSourceView(
                                        1L,
                                        "SO001",
                                        "WH",
                                        1L,
                                        "CUS1",
                                        "星河门店",
                                        "CONFIRMED",
                                        "DHB",
                                        "DH.001",
                                        LocalDate.of(2026, 11, 6),
                                        null,
                                        "E1",
                                        "张三",
                                        10L,
                                        "武汉销售部",
                                        null,
                                        null)));
    }

    @AfterEach
    void cleanup() {
        TestAuthorizationContext.clear();
    }

    LogisticsLedgerPartyView customer(String code, boolean complete) {
        return new LogisticsLedgerPartyView(
                "10",
                code,
                "星河门店",
                "WH",
                complete ? "客户档案地址" : null,
                complete ? "档案收货人" : null,
                complete ? "13800000000" : null,
                "CUSTOMER");
    }

    LogisticsCostCommand command() {
        return json.readValue(
                """
{"cityCode":"WH","costDate":"2026-11-06","paymentStatusCode":"UNPAID","ownerEmployeeCode":"FEE_OWNER","departmentId":999,
"ledger":{"schemaVersion":2,"transportPurposeCode":"DELIVERY","deliveryModeCode":"SELF","costMonth":"2026-11",
"sender":{"pointCode":"S1","partyTypeCode":"WAREHOUSE","partyCode":"WH1","cityCode":"WH","address":"仓库地址"},
"receivingPoints":[{"pointCode":"P1","partyTypeCode":"CUSTOMER","partyCode":"CUS1","cityCode":"WH","address":"伪造地址","contactName":"伪造收货人","phone":"13000000000"}],
"feeItems":[{"feeKey":"F","feeTypeCode":"FREIGHT","feeAmount":40.5,"bearerCode":"COMPANY","ourAmount":40.5,"allocationMethodCode":"MANUAL"}],
"sources":[{"orderTypeCode":"SALES_ORDER","orderCode":"SO001","linkRoleCode":"COST","employeeCode":"FORGED","departmentId":999,"allocations":[{"feeKey":"F","receivingPointCode":"P1","cityCode":"WH","amount":40.5}]}]}}
""",
                LogisticsCostCommand.class);
    }

    @Test
    void savedContactsAndSalesAttributionComeFromTrustedOrderAndCustomerData() {
        var normalized = service.normalize(command(), action, false);
        var link = normalized.ledger().sources().getFirst();
        assertThat(link.employeeCode()).isEqualTo("E1");
        assertThat(link.departmentId()).isEqualTo(10L);
        assertThat(normalized.ownerEmployeeCode()).isEqualTo("FEE_OWNER");
        var point = normalized.ledger().receivingPoints().getFirst();
        assertThat(point.address()).isEqualTo("客户档案地址");
        assertThat(point.contactName()).isEqualTo("档案收货人");
        assertThat(point.phone()).isEqualTo("13800000000");
    }

    @Test
    void mismatchedWarehouseOrCustomerCannotBeSaved() {
        var wrongWarehouse =
                json.readValue(
                        json.writeValueAsString(command()).replace("WH1", "WH2"),
                        LogisticsCostCommand.class);
        assertThatThrownBy(() -> service.normalize(wrongWarehouse, action, false))
                .hasMessageContaining("发货仓库必须");
        var wrongCustomer =
                json.readValue(
                        json.writeValueAsString(command()).replace("CUS1", "CUS2"),
                        LogisticsCostCommand.class);
        assertThatThrownBy(() -> service.normalize(wrongCustomer, action, false))
                .hasMessageContaining("收货方必须");
    }

    @Test
    void orderWithoutWarehouseAllowsAnExplicitAuthorizedLogisticsWarehouse() {
        var original = sources.sales("", null, action, null).getFirst();
        var withoutWarehouse =
                new OrderDeliveryCostSourceView(
                        original.id(),
                        original.documentNo(),
                        original.cityCode(),
                        null,
                        original.customerCode(),
                        original.customerName(),
                        original.statusCode(),
                        original.sourceSystem(),
                        original.originalNo(),
                        original.documentDate(),
                        original.destinationAddress(),
                        original.employeeCode(),
                        original.employeeName(),
                        original.departmentId(),
                        original.departmentName(),
                        original.contactName(),
                        original.contactPhone());
        when(sources.sales(any(), any(), eq(action), isNull()))
                .thenReturn(List.of(withoutWarehouse));
        var result = service.normalize(command(), action, false);
        assertThat(result.ledger().sender().partyCode()).isEqualTo("WH1");
        assertThat(result.ledger().scopeWarehouseIds()).contains(1L);
        assertThat(result.ledger().sources().getFirst().sourceWarehouseCode()).isNull();
        assertThat(result.ledger().sources().getFirst().departmentId()).isEqualTo(10L);
        assertThat(result.ledger().receivingPoints().getFirst().address()).isEqualTo("客户档案地址");
    }

    @Test
    void manualWarehouseStillRequiresTheExistingWarehousePermission() {
        when(refs.ledgerParties(
                        eq("WAREHOUSE"), eq("WH1"), any(), any(), any(), anyInt(), eq(action)))
                .thenThrow(new AuthorizationDeniedException(action));
        assertThatThrownBy(() -> service.normalize(command(), action, false))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void incompleteCustomerArchiveRequiresMaintenanceBeforeSave() {
        when(refs.ledgerCustomers(eq("WH"), anyList(), eq(action)))
                .thenReturn(List.of(customer("CUS1", false)));
        assertThatThrownBy(() -> service.normalize(command(), action, false))
                .hasMessageContaining("客户收货资料不完整", "维护客户档案");
    }
}
