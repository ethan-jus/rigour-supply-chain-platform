package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.LogisticsCostAttachmentView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface CostAttachmentApi {
    @GetMapping("/api/v1/orders/logistics-costs/{entry}/attachments")
    ApiResponse<List<LogisticsCostAttachmentView>> list(@PathVariable String entry);

    @GetMapping("/api/v1/orders/logistics-costs/{entry}/attachments/{file}/url")
    ApiResponse<String> url(@PathVariable String entry, @PathVariable String file);

    @PostMapping(
            value = "/api/v1/orders/logistics-costs/{entry}/attachments",
            consumes = "multipart/form-data")
    ApiResponse<LogisticsCostAttachmentView> upload(
            @PathVariable String entry,
            @RequestParam int revision,
            @RequestPart("file") MultipartFile file);
}
