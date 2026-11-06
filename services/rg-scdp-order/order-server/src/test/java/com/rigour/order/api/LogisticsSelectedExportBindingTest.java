package com.rigour.order.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rigour.order.application.service.cost.OrderLogisticsCostService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LogisticsSelectedExportBindingTest {
    @Test void selectedIdsAreBoundToTheDedicatedExportOperation() throws Exception {
        var service = mock(OrderLogisticsCostService.class);
        when(service.exportSelected(List.of("LC2", "LC1"))).thenReturn(List.of());
        MockMvcBuilders.standaloneSetup(new OrderLogisticsCostController(service)).build()
                .perform(post("/api/v1/orders/logistics-costs/export-selected")
                        .contentType(MediaType.APPLICATION_JSON).content("[\"LC2\",\"LC1\"]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
        verify(service).exportSelected(List.of("LC2", "LC1"));
        verifyNoMoreInteractions(service);
    }

    @Test void omittedSelectionBodyCannotFallThroughToAnAllRowsExport() throws Exception {
        var service = mock(OrderLogisticsCostService.class);
        MockMvcBuilders.standaloneSetup(new OrderLogisticsCostController(service)).build()
                .perform(post("/api/v1/orders/logistics-costs/export-selected")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
