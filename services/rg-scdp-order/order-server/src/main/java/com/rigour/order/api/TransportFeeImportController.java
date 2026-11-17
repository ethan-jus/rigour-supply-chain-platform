package com.rigour.order.api;

import com.rigour.order.api.v1.model.TransportFeeImportModels.*;
import com.rigour.order.application.service.cost.TransportFeeImportService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/orders/transport-records/fee-import")
public class TransportFeeImportController {
    private final TransportFeeImportService service;

    public TransportFeeImportController(TransportFeeImportService service) {
        this.service = service;
    }

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    public ApiResponse<Preview> preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String carrierCode)
            throws java.io.IOException {
        return ApiResponse.success(service.preview(file.getBytes(), carrierCode));
    }

    @PostMapping(value = "/apply", consumes = "multipart/form-data")
    public ApiResponse<Preview> apply(
            @RequestPart("file") MultipartFile file, @RequestPart("command") Apply command)
            throws java.io.IOException {
        return ApiResponse.success(
                service.apply(file.getOriginalFilename(), file.getBytes(), command));
    }
}
