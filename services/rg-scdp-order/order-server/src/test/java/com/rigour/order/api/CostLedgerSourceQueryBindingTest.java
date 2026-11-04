package com.rigour.order.api;

import com.rigour.erp.api.v1.model.CostLedgerSourceQuery;
import com.rigour.order.api.v1.model.LogisticsLedgerSourceQuery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CostLedgerSourceQueryBindingTest {
    @RestController
    static class BindingController {
        @GetMapping("/order")
        LogisticsLedgerSourceQuery order(@ModelAttribute LogisticsLedgerSourceQuery query) { return query; }
        @GetMapping("/erp")
        CostLedgerSourceQuery erp(@ModelAttribute CostLedgerSourceQuery query) { return query; }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/order", "/erp"})
    void omittedOptionalParametersUseNormalFirstPage(String path) throws Exception {
        MockMvcBuilders.standaloneSetup(new BindingController()).build()
                .perform(get(path).param("orderTypeCode", "PURCHASE_ORDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.exact").value(false))
                .andExpect(jsonPath("$.historical").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/order", "/erp"})
    void explicitFlagsAndOffsetArePreserved(String path) throws Exception {
        MockMvcBuilders.standaloneSetup(new BindingController()).build()
                .perform(get(path).param("orderTypeCode", "SALES_ORDER").param("begin", "50")
                        .param("exact", "true").param("historical", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.begin").value(50))
                .andExpect(jsonPath("$.exact").value(true))
                .andExpect(jsonPath("$.historical").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/order", "/erp"})
    void invalidBooleanIsRejectedRatherThanSilentlyCoerced(String path) throws Exception {
        MockMvcBuilders.standaloneSetup(new BindingController()).build()
                .perform(get(path).param("orderTypeCode", "SALES_ORDER").param("exact", "invalid"))
                .andExpect(status().isBadRequest());
    }
}
