package com.rigour.order.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rigour.order.api.v1.model.TransportSubmission;
import com.rigour.order.application.service.cost.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

class TransportSubmissionBindingTest {
    @Test
    void pdfPreviewReturnsPrivateBinaryWithoutCacheOrRedirect() throws Exception {
        var service = mock(CostAttachmentService.class);
        when(service.previewPdf("C1", "F1")).thenReturn("%PDF-1.7".getBytes());
        var mvc = MockMvcBuilders.standaloneSetup(new CostAttachmentController(service)).build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/orders/logistics-costs/C1/attachments/F1/preview"))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .contentType("application/pdf"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .string("Cache-Control", "no-store"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .bytes("%PDF-1.7".getBytes()));
    }

    @Test
    void newAndEditBindJsonCommandAndActualMultipartBytes() throws Exception {
        var service = mock(TransportSubmissionService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new TransportRecordController(
                                        mock(TransportRecordService.class),
                                        service,
                                        mock(CostAttachmentService.class)))
                        .build();
        for (String path :
                List.of(
                        "/api/v1/orders/transport-records/with-evidence",
                        "/api/v1/orders/transport-records/C1/with-evidence")) {
            mvc.perform(
                            multipart(path)
                                    .file(
                                            new MockMultipartFile(
                                                    "command",
                                                    "command.json",
                                                    "application/json",
                                                    ("{\"submissionId\":\"4f8a182a-2cc4-4be5-bbb6-21505d616fa3\","
                                                         + "\"draft\":{\"carrierCode\":\"SF\",\"date\":\"2026-11-12\"},"
                                                         + "\"uploads\":[{\"categoryCode\":\"RECEIPT\"}],\"removedIds\":[]}")
                                                            .getBytes()))
                                    .file(
                                            new MockMultipartFile(
                                                    "files",
                                                    "回单.pdf",
                                                    "application/pdf",
                                                    "%PDF-1.7 data".getBytes())))
                    .andExpect(status().isOk());
        }
        var command = org.mockito.ArgumentCaptor.forClass(TransportSubmission.class);
        @SuppressWarnings("unchecked")
        var contents =
                (org.mockito.ArgumentCaptor<List<TransportSubmissionService.Content>>)
                        (Object) org.mockito.ArgumentCaptor.forClass(List.class);
        verify(service, times(2))
                .save(nullable(String.class), command.capture(), contents.capture());
        assertThat(command.getValue().draft().date())
                .isEqualTo(java.time.LocalDate.of(2026, 11, 12));
        assertThat(command.getValue().uploads().getFirst().categoryCode()).isEqualTo("RECEIPT");
        assertThat(contents.getValue().getFirst().bytes()).isEqualTo("%PDF-1.7 data".getBytes());
    }
}
