package com.rigour.erp.api;

import com.rigour.erp.api.v1.CostAttachmentApi;
import com.rigour.erp.api.v1.model.ErpCostAttachmentView;
import com.rigour.erp.application.service.cost.CostAttachmentService;
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

    public ApiResponse<List<ErpCostAttachmentView>> list(String category, String entry) {
        return ApiResponse.success(service.list(category, entry));
    }

    public ApiResponse<String> url(String category, String entry, String file) {
        return ApiResponse.success(service.url(category, entry, file));
    }

    public ApiResponse<ErpCostAttachmentView> upload(
            String category, String entry, int revision, MultipartFile file) {
        if (file.getSize() > 10 * 1024 * 1024)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "附件不能超过10MB", List.of());
        try {
            return ApiResponse.success(
                    service.upload(
                            category,
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
