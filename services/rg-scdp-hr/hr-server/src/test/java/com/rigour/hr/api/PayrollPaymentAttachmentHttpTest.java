package com.rigour.hr.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.rigour.hr.api.v1.model.PayrollPaymentAttachmentView;
import com.rigour.hr.api.v1.model.PayrollPaymentAttachmentLinkView;
import com.rigour.hr.application.service.PayrollPaymentAttachmentService;
import com.rigour.shared.core.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;

class PayrollPaymentAttachmentHttpTest {
    final PayrollPaymentAttachmentService service=mock(PayrollPaymentAttachmentService.class);
    final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new PayrollPaymentAttachmentController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    final String path="/api/v1/hr/payroll/PAYROLL/payments/PAYMENT/attachments";
    final byte[] pdf="%PDF-1.7\nactual payment evidence".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    final PayrollPaymentAttachmentView metadata=new PayrollPaymentAttachmentView("ATTACHMENT","工资回单.pdf","application/pdf",pdf.length,"USER","李四",Instant.EPOCH);

    @Test void multipartBindsBothParentsRevisionAndOriginalFileName() throws Exception {
        when(service.upload("PAYROLL","PAYMENT",4,"工资回单.pdf","application/pdf",pdf)).thenReturn(metadata);
        mvc.perform(multipart(path).file(new MockMultipartFile("file","工资回单.pdf","application/pdf",pdf)).param("revision","4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fileName").value("工资回单.pdf"));
        verify(service).upload("PAYROLL","PAYMENT",4,"工资回单.pdf","application/pdf",pdf);
    }

    @Test void missingRequiredUploadFieldsFailBeforeService() throws Exception {
        mvc.perform(multipart(path).param("revision","4")).andExpect(status().isBadRequest());
        mvc.perform(multipart(path).file(new MockMultipartFile("file","回单.pdf","application/pdf",pdf))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void contentReturnsProtectedRealBytesAndKeepsPdfDistinctFromImages() throws Exception {
        when(service.content("PAYROLL","PAYMENT","ATTACHMENT")).thenReturn(new PayrollPaymentAttachmentService.Content(metadata,pdf));
        var result=mvc.perform(get(path+"/ATTACHMENT/content")).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(content().bytes(pdf)).andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition")).startsWith("inline;").contains("filename*=");
        verify(service).content("PAYROLL","PAYMENT","ATTACHMENT");
    }

    @Test void attachmentListBindsPayrollAndPayment() throws Exception {
        when(service.list("PAYROLL","PAYMENT")).thenReturn(List.of(metadata));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].createdByName").value("李四"));
        verify(service).list("PAYROLL","PAYMENT");
    }

    @Test void exportLinkBindsBothParentsAndAttachmentAndCannotBeCached() throws Exception {
        when(service.downloadLink("PAYROLL","PAYMENT","ATTACHMENT")).thenReturn(
                new PayrollPaymentAttachmentLinkView("https://bucket.cos.example/private.pdf?signature=test", Instant.parse("2026-10-30T08:00:00Z")));
        mvc.perform(get(path+"/ATTACHMENT/download-link")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.data.url").value("https://bucket.cos.example/private.pdf?signature=test"));
        verify(service).downloadLink("PAYROLL","PAYMENT","ATTACHMENT");
    }
}
