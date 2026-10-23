package com.rigour.hr.api;

import com.rigour.hr.api.v1.PayrollPaymentAttachmentApi;
import com.rigour.hr.api.v1.model.PayrollPaymentAttachmentView;
import com.rigour.hr.api.v1.model.PayrollPaymentAttachmentLinkView;
import com.rigour.hr.application.service.PayrollPaymentAttachmentService;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public final class PayrollPaymentAttachmentController implements PayrollPaymentAttachmentApi {
    private final PayrollPaymentAttachmentService service;
    public PayrollPaymentAttachmentController(PayrollPaymentAttachmentService service) { this.service = service; }
    public ApiResponse<List<PayrollPaymentAttachmentView>> list(String payrollId, String paymentId) {
        return ApiResponse.success(service.list(payrollId, paymentId));
    }
    public ApiResponse<PayrollPaymentAttachmentView> upload(String payrollId, String paymentId, int revision, MultipartFile file) {
        if (file == null || file.getSize() > PayrollPaymentAttachmentService.MAX_FILE_SIZE)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "凭证文件不能超过10MB", List.of());
        try {
            return ApiResponse.success(service.upload(payrollId, paymentId, revision,
                    file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        } catch (java.io.IOException failure) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "凭证文件读取失败，请重新选择", List.of());
        }
    }
    public ResponseEntity<byte[]> content(String payrollId, String paymentId, String attachmentId) {
        var content = service.content(payrollId, paymentId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.metadata().contentType()))
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(content.metadata().fileName(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }

    public ResponseEntity<ApiResponse<PayrollPaymentAttachmentLinkView>> downloadLink(
            String payrollId, String paymentId, String attachmentId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.success(service.downloadLink(payrollId, paymentId, attachmentId)));
    }
}
