package com.rigour.hr.api;

import com.rigour.hr.api.v1.CostAttachmentApi;
import com.rigour.hr.api.v1.model.HrExpenseAttachmentView;
import com.rigour.hr.application.service.CostAttachmentService;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public final class CostAttachmentController implements CostAttachmentApi {
    private final CostAttachmentService service;

    public CostAttachmentController(CostAttachmentService service) {
        this.service = service;
    }

    public ApiResponse<List<HrExpenseAttachmentView>> list(String entry) {
        return ApiResponse.success(service.list(entry));
    }

    public ApiResponse<String> url(String entry, String file) {
        return ApiResponse.success(service.url(entry, file));
    }

    public ApiResponse<HrExpenseAttachmentView> upload(
            String entry, int revision, MultipartFile file) {
        if (file.getSize() > 10 * 1024 * 1024)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "附件不能超过10MB", List.of());
        try {
            return ApiResponse.success(
                    service.upload(
                            entry,
                            revision,
                            file.getOriginalFilename(),
                            file.getContentType(),
                            file.getBytes()));
        } catch (java.io.IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "附件读取失败", List.of());
        }
    }
}
