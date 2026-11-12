package com.rigour.integration.api.controller.thirdparty;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.integration.application.service.thirdparty.huolala.HuolalaService;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HuolalaSettingsControllerTest {
    @Test
    void businessOnlyPayloadDoesNotRequireHiddenTechnicalFields() throws Exception {
        var service = mock(HuolalaService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HuolalaController(service)).build();
        var result =
                mvc.perform(
                                put("/api/v1/integration/huolala/connection")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"enabled\":false,\"intervalMinutes\":15,\"startDate\":\"2026-11-01\",\"revision\":0}"))
                        .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(String.valueOf(result.getResolvedException()))
                .isEqualTo(200);
        verify(service)
                .save(
                        argThat(
                                c ->
                                        !c.enabled()
                                                && c.intervalMinutes() == 15
                                                && c.revision() == 0
                                                && c.enterpriseId() == null
                                                && c.secretRef() == null
                                                && c.redirectUri() == null));
    }

    @Test
    void incompleteBusinessPayloadStillFailsValidation() throws Exception {
        var service = mock(HuolalaService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HuolalaController(service)).build();
        var result =
                mvc.perform(
                                put("/api/v1/integration/huolala/connection")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"intervalMinutes\":15,\"startDate\":\"2026-11-01\",\"revision\":0}"))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(service);
    }
}
