package com.rigour.erp.api.v1;

import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 合同文件上传与按采购单授权读取；不建立独立附件表。 */
public interface ErpProcurementContractApi {
    @PostMapping(
            value = ErpProcurementOrderApi.BASE_PATH + "/contracts",
            consumes = "multipart/form-data")
    ApiResponse<String> uploadNew(@RequestPart("file") MultipartFile file);

    @PostMapping(
            value = ErpProcurementOrderApi.BASE_PATH + "/{id}/contract",
            consumes = "multipart/form-data")
    ApiResponse<String> uploadExisting(
            @PathVariable Long id, @RequestPart("file") MultipartFile file);

    @GetMapping(ErpProcurementOrderApi.BASE_PATH + "/{id}/contract-url")
    ApiResponse<String> url(@PathVariable Long id);
}
