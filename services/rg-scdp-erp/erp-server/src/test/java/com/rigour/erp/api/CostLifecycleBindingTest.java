package com.rigour.erp.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rigour.erp.api.v1.model.ErpCostActionCommand;
import com.rigour.erp.application.service.cost.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CostLifecycleBindingTest {
    @Test
    void revokeAndDeleteCarryTheExactRevisionToDistinctActions() throws Exception {
        var service = mock(ErpCostService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ErpCostController(service)).build();
        for (var action : java.util.List.of("revoke", "delete")) {
            mvc.perform(
                            post("/api/v1/erp/costs/marketing/bill/" + action)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"revision\":7,\"reason\":\"业务原因\"}"))
                    .andExpect(status().isOk());
            verify(service)
                    .lifecycle(
                            "marketing",
                            "bill",
                            new ErpCostActionCommand(7, "业务原因"),
                            "delete".equals(action));
        }
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingActionBodyCannotReachBusinessMutation() throws Exception {
        var service = mock(ErpCostService.class);
        MockMvcBuilders.standaloneSetup(new ErpCostController(service))
                .build()
                .perform(
                        post("/api/v1/erp/costs/marketing/bill/delete")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void attachmentReplacementAndRemovalBindTheParentVersion() throws Exception {
        var service = mock(CostAttachmentService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CostAttachmentController(service)).build();
        byte[] bytes = "%PDF-1.7 sample".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(
                        multipart("/api/v1/erp/costs/marketing/bill/attachments/old/replacement")
                                .file(
                                        new MockMultipartFile(
                                                "file", "新凭证.pdf", "application/pdf", bytes))
                                .param("revision", "7"))
                .andExpect(status().isOk());
        verify(service).replace("marketing", "bill", "old", 7, "新凭证.pdf", "application/pdf", bytes);
        mvc.perform(
                        post("/api/v1/erp/costs/marketing/bill/attachments/old/remove")
                                .param("revision", "8"))
                .andExpect(status().isOk());
        verify(service).remove("marketing", "bill", "old", 8);
        verifyNoMoreInteractions(service);
    }
}
