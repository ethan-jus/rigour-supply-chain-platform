package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProcurementContractApi;
import com.rigour.erp.application.service.supply.ProcurementContractService;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
public final class ErpProcurementContractController implements ErpProcurementContractApi {
    private final ProcurementContractService service;

    public ErpProcurementContractController(ProcurementContractService service) {
        this.service = service;
    }

    public ApiResponse<String> uploadNew(MultipartFile file) {
        return upload(null, file);
    }

    public ApiResponse<String> uploadExisting(Long id, MultipartFile file) {
        return upload(id, file);
    }

    public ApiResponse<String> url(Long id) {
        return ApiResponse.success(service.url(id));
    }

    private ApiResponse<String> upload(Long id, MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "合同文件不能为空且不得超过10MB", List.of());
        try {
            return ApiResponse.success(
                    service.upload(
                            id,
                            file.getOriginalFilename(),
                            file.getContentType(),
                            file.getBytes()));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "合同文件读取失败", List.of());
        }
    }
}
