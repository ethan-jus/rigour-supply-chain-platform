package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.HrExpenseAttachmentView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface CostAttachmentApi {
    @GetMapping("/api/v1/hr/expenses/{entry}/attachments")
    ApiResponse<List<HrExpenseAttachmentView>> list(@PathVariable String entry);

    @GetMapping("/api/v1/hr/expenses/{entry}/attachments/{file}/url")
    ApiResponse<String> url(@PathVariable String entry, @PathVariable String file);

    @PostMapping(
            value = "/api/v1/hr/expenses/{entry}/attachments",
            consumes = "multipart/form-data")
    ApiResponse<HrExpenseAttachmentView> upload(
            @PathVariable String entry,
            @RequestParam int revision,
            @RequestPart("file") MultipartFile file);
}
