package com.rigour.hr.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.HrExpenseRules;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;

class HrExpenseCostSourceTest {
    static final String ID = "00000000-0000-0000-0000-000000000001";
    final JsonMapper json = new JsonMapper();

    HrExpenseCommand command(String category, HrExpenseCostReference ref, BigDecimal quantity) {
        return new HrExpenseCommand(
                "test",
                null,
                "费用",
                "FREIGHT",
                "E1",
                "4201",
                "武汉",
                LocalDate.of(2026, 10, 1),
                new BigDecimal("320.00"),
                "UNPAID",
                "",
                "",
                "2026-10",
                new HrExpenseDetails(
                        category,
                        "配送",
                        "",
                        "",
                        quantity,
                        "件",
                        null,
                        "",
                        "",
                        null,
                        null,
                        "",
                        "",
                        "TEST-001",
                        "",
                        "DELIVERY",
                        ref));
    }

    ObjectNode original() {
        return (ObjectNode)
                json.readTree(
                        """
                        {"statusCode":"CONFIRMED","revision":7,"data":{
                          "cityCode":"4201","costDate":"2026-10-01","amount":320,
                          "logistics":{"payerCode":"COMPANY"},"rental":{"premisesType":"OFFICE"}
                        }}
                        """);
    }

    @Test
    void onlyCurrentSameCitySameMonthFullCompanyCostCanBeLinked() {
        var c = command("freight", new HrExpenseCostReference("ORDER", "LOGISTICS", ID, 7), null);
        assertThatCode(() -> HttpHrExpenseCostSource.validateOriginal(c, original()))
                .doesNotThrowAnyException();
        var revision = original();
        revision.put("revision", 8);
        assertThatThrownBy(() -> HttpHrExpenseCostSource.validateOriginal(c, revision))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本");
        var city = original();
        ((ObjectNode) city.path("data")).put("cityCode", "3101");
        assertThatThrownBy(() -> HttpHrExpenseCostSource.validateOriginal(c, city))
                .hasMessageContaining("同城市");
        var month = original();
        ((ObjectNode) month.path("data")).put("costDate", "2026-09-30");
        assertThatThrownBy(() -> HttpHrExpenseCostSource.validateOriginal(c, month))
                .hasMessageContaining("同月份");
        var amount = original();
        ((ObjectNode) amount.path("data")).put("amount", 319);
        assertThatThrownBy(() -> HttpHrExpenseCostSource.validateOriginal(c, amount))
                .hasMessageContaining("金额");
        var payer = original();
        ((ObjectNode) payer.path("data").path("logistics")).put("payerCode", "CUSTOMER");
        assertThatThrownBy(() -> HttpHrExpenseCostSource.validateOriginal(c, payer))
                .hasMessageContaining("公司承担");
        var draft = original();
        draft.put("statusCode", "DRAFT");
        assertThatThrownBy(() -> HttpHrExpenseCostSource.validateOriginal(c, draft))
                .hasMessageContaining("状态");
    }

    @Test
    void unknownRentalPurposeMustNotSilentlyBecomeOfficeCost() {
        var c = command("office", new HrExpenseCostReference("ERP", "RENTAL", ID, 7), null);
        assertThatCode(() -> HttpHrExpenseCostSource.validateOriginal(c, original()))
                .doesNotThrowAnyException();
        var row = original();
        ((ObjectNode) row.path("data").path("rental")).put("premisesType", "UNKNOWN");
        assertThatThrownBy(() -> HttpHrExpenseCostSource.validateOriginal(c, row))
                .hasMessageContaining("用途未明确");
        ((ObjectNode) row.path("data").path("rental")).put("premisesType", "WAREHOUSE");
        assertThatThrownBy(() -> HttpHrExpenseCostSource.validateOriginal(c, row))
                .hasMessageContaining("用途一致");
    }

    @Test
    void malformedReferencesAndUnboundedNumbersAreBusinessErrors() {
        for (var ref :
                new HrExpenseCostReference[] {
                    new HrExpenseCostReference(null, "RENTAL", ID, 7),
                    new HrExpenseCostReference("ERP", null, ID, 7),
                    new HrExpenseCostReference("ERP", "RENTAL", "1-1-1-1-1", 7)
                }) {
            assertThatThrownBy(
                            () -> HrExpenseRules.validate(command("office", ref, null), false, 0))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(
                        () ->
                                HrExpenseRules.validate(
                                        command("expense", null, new BigDecimal("1E+100")),
                                        false,
                                        0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数量");
    }
}
