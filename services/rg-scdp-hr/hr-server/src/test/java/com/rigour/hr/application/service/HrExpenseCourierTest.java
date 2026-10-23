package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.api.v1.model.HrExpenseDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDate;

class HrExpenseCourierTest {
    private HrExpenseCommand command(String type, HrExpenseDetails.Courier courier) {
        return new HrExpenseCommand("request", null, "寄送合作合同", type, "EMP1",
                "COST_HEADQUARTERS", "总部", LocalDate.of(2026, 10, 23), new BigDecimal("18.00"),
                "UNPAID", null, null, "2026-10",
                new HrExpenseDetails("expense", "寄送双方盖章合同", "张三", null, null, null,
                        null, null, null, null, null, null, null, "POST-18", null, null, null, courier));
    }

    @Test
    void courierSurvivesClassificationAndJsonRoundTripAsReimbursement() throws Exception {
        var courier = new HrExpenseDetails.Courier("双方盖章合作合同", "武汉合作门店", "顺丰速运", "SF0000123456");
        var result = HrExpenseRules.classify(command("COURIER", courier));
        assertThat(result.expense().costCategory()).isEqualTo("expense");
        assertThat(result.expense().courier()).isEqualTo(courier);
        assertThatCode(() -> HrExpenseRules.validate(result, false, 0)).doesNotThrowAnyException();
        assertThatCode(() -> HrExpenseRules.validate(result, true, 0)).doesNotThrowAnyException();
        var mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(mapper.readValue(mapper.writeValueAsString(result.expense()), HrExpenseDetails.class))
                .isEqualTo(result.expense());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void eachCourierBusinessFieldIsRequiredAtSaveAndConfirmation(int missing) {
        String[] fields = {"合同", "合作门店", "快递公司", "000012345"};
        fields[missing] = "   ";
        var command = command("COURIER", new HrExpenseDetails.Courier(fields[0], fields[1], fields[2], fields[3]));
        for (boolean confirm : new boolean[] {false, true})
            assertThatThrownBy(() -> HrExpenseRules.validate(command, confirm, 0))
                    .hasMessageContaining(new String[] {"寄送内容", "收件客户 / 合作方", "快递公司", "运单号"}[missing]);
    }

    @Test
    void absentCourierDetailsAreRejectedOnlyForCourierAndLengthsRemainBounded() throws Exception {
        assertThatThrownBy(() -> HrExpenseRules.validate(command("COURIER", null), false, 0))
                .hasMessageContaining("寄送内容");
        assertThatCode(() -> HrExpenseRules.validate(command("OTHER", null), true, 0))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> HrExpenseRules.validate(command("COURIER",
                new HrExpenseDetails.Courier("合同", "合作门店", "公司", "x".repeat(101))), false, 0))
                .hasMessageContaining("运单号");
        var legacy = new ObjectMapper().findAndRegisterModules().readValue(
                "{\"costCategory\":\"expense\",\"purpose\":\"历史费用\"}", HrExpenseDetails.class);
        assertThat(legacy.courier()).isNull();
        assertThat(legacy.purpose()).isEqualTo("历史费用");
    }
}
