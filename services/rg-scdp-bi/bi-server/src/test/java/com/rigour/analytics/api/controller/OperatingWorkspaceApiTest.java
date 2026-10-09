package com.rigour.analytics.api.controller;
import com.rigour.analytics.application.service.OperatingWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class OperatingWorkspaceApiTest {
    @Test void retiredTargetRoutesAreAbsentAndActionsStillBindScope() throws Exception {
        var service=mock(OperatingWorkspaceService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AnalyticsOperatingWorkspaceController(service)).build();
        mvc.perform(get("/api/v1/analytics/supply/dashboard/targets")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/analytics/supply/dashboard/targets").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/analytics/supply/dashboard/actions").param("cityCode","BJ").param("employeeCode","E1")).andExpect(status().isOk());
        verify(service).actions(null,null,"BJ","E1",null,null,1,20);
    }
}
