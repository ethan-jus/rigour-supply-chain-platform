package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.ErpCostAttachmentView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface CostAttachmentApi {
    @GetMapping("/api/v1/erp/costs/{category}/{entry}/attachments")
    ApiResponse<List<ErpCostAttachmentView>> list(
            @PathVariable String category, @PathVariable String entry);

    @GetMapping("/api/v1/erp/costs/{category}/{entry}/attachments/{file}/url")
    ApiResponse<String> url(
            @PathVariable String category, @PathVariable String entry, @PathVariable String file);

    @PostMapping(
            value = "/api/v1/erp/costs/{category}/{entry}/attachments",
            consumes = "multipart/form-data")
    ApiResponse<ErpCostAttachmentView> upload(
            @PathVariable String category,
            @PathVariable String entry,
            @RequestParam int revision,
            @RequestPart("file") MultipartFile file);
}
