package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.analytics.application.service.OperatingWorkspaceService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 新契约真实MVC路由绑定，防止抽屉调用参数与后端方法漂移。 */
class OperatingWorkspaceApiTest {
    @Test void targetAndActionRoutesBindRevisionAndFilters() throws Exception {
        var service = mock(OperatingWorkspaceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AnalyticsOperatingWorkspaceController(service)).build();
        when(service.targets("2026-09", "CITY", "BJ")).thenReturn(List.of());
        mvc.perform(get("/api/v1/analytics/supply/dashboard/targets").param("month","2026-09").param("dimensionType","CITY").param("dimensionCode","BJ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        mvc.perform(delete("/api/v1/analytics/supply/dashboard/targets/42").param("revision","2")).andExpect(status().isOk());
        verify(service).deleteTarget("42", 2);
        mvc.perform(get("/api/v1/analytics/supply/dashboard/actions").param("cityCode","BJ").param("employeeCode","E1")).andExpect(status().isOk());
        verify(service).actions(null, null, "BJ", "E1", null, null, 1, 20);
    }
    @Test void putTargetKeepsBoundedDecimalAndExpectedRevision() throws Exception {
        var service = mock(OperatingWorkspaceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AnalyticsOperatingWorkspaceController(service)).build();
        when(service.saveTarget(any())).thenReturn(new TargetView("9", "2026-09", "CITY", "BJ", "北京",
                "SALES_AMOUNT", new BigDecimal("100.12"), null, 2, Instant.parse("2026-09-12T00:00:00Z")));
        mvc.perform(put("/api/v1/analytics/supply/dashboard/targets").contentType("application/json")
                .content("""
                        {"month":"2026-09","dimensionType":"CITY","dimensionCode":"BJ","dimensionName":"北京",
                         "metricCode":"SALES_AMOUNT","targetValue":"100.12","expectedRevision":1}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("9")).andExpect(jsonPath("$.data.revision").value(2));
        verify(service).saveTarget(argThat(c -> c.targetValue().equals(new BigDecimal("100.12")) && c.expectedRevision() == 1));
    }
}
