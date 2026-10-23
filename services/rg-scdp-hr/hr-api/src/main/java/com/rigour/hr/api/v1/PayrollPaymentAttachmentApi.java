package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.PayrollPaymentAttachmentView;
import com.rigour.hr.api.v1.model.PayrollPaymentAttachmentLinkView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface PayrollPaymentAttachmentApi {
    String PATH = "/api/v1/hr/payroll/{payrollId}/payments/{paymentId}/attachments";

    @GetMapping(PATH)
    ApiResponse<List<PayrollPaymentAttachmentView>> list(
            @PathVariable("payrollId") String payrollId, @PathVariable("paymentId") String paymentId);

    @PostMapping(value = PATH, consumes = "multipart/form-data")
    ApiResponse<PayrollPaymentAttachmentView> upload(
            @PathVariable("payrollId") String payrollId, @PathVariable("paymentId") String paymentId,
            @RequestParam("revision") int revision, @RequestPart("file") MultipartFile file);

    @GetMapping(PATH + "/{attachmentId}/content")
    ResponseEntity<byte[]> content(
            @PathVariable("payrollId") String payrollId, @PathVariable("paymentId") String paymentId,
            @PathVariable("attachmentId") String attachmentId);

    @GetMapping(PATH + "/{attachmentId}/download-link")
    ResponseEntity<ApiResponse<PayrollPaymentAttachmentLinkView>> downloadLink(
            @PathVariable("payrollId") String payrollId, @PathVariable("paymentId") String paymentId,
            @PathVariable("attachmentId") String attachmentId);
}
