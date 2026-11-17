package com.rigour.order.api;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.service.cost.CostAttachmentService;
import com.rigour.order.application.service.cost.TransportRecordService;
import com.rigour.order.application.service.cost.TransportSubmissionService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/v1/orders/transport-records")
public class TransportRecordController {
    private final TransportRecordService service;
    private final TransportSubmissionService submissions;
    private final CostAttachmentService evidence;

    public TransportRecordController(
            TransportRecordService service,
            TransportSubmissionService submissions,
            CostAttachmentService evidence) {
        this.service = service;
        this.submissions = submissions;
        this.evidence = evidence;
    }

    @PostMapping(value = "/with-evidence", consumes = "multipart/form-data")
    public ApiResponse<Detail> createWithEvidence(
            @RequestPart("command") TransportSubmission command,
            @RequestPart(value = "files", required = false) List<MultipartFile> files)
            throws java.io.IOException {
        return ApiResponse.success(submissions.save(null, command, contents(files)));
    }

    @PostMapping(value = "/{id}/with-evidence", consumes = "multipart/form-data")
    public ApiResponse<Detail> saveWithEvidence(
            @PathVariable String id,
            @RequestPart("command") TransportSubmission command,
            @RequestPart(value = "files", required = false) List<MultipartFile> files)
            throws java.io.IOException {
        return ApiResponse.success(submissions.save(id, command, contents(files)));
    }

    @GetMapping("/{id}/attachments/{file}/download-url")
    public ApiResponse<String> download(@PathVariable String id, @PathVariable String file) {
        return ApiResponse.success(evidence.downloadUrl(id, file));
    }

    @GetMapping("/{id}/attachments/{file}/content")
    public org.springframework.http.ResponseEntity<byte[]> attachmentContent(
            @PathVariable String id, @PathVariable String file) {
        return fileResponse(evidence.downloadContent(id, file));
    }

    @GetMapping("/{id}/vouchers/{voucherId}/content")
    public org.springframework.http.ResponseEntity<byte[]> voucherContent(
            @PathVariable String id, @PathVariable String voucherId) {
        return fileResponse(service.voucherContent(id, voucherId));
    }

    private org.springframework.http.ResponseEntity<byte[]> fileResponse(
            CostAttachmentService.FileContent file) {
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                        .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .body(file.bytes());
    }

    private List<TransportSubmissionService.Content> contents(List<MultipartFile> files)
            throws java.io.IOException {
        if (files == null) return List.of();
        if (files.size() > 20
                || files.stream().anyMatch(f -> f.isEmpty() || f.getSize() > 10 * 1024 * 1024))
            throw new com.rigour.shared.core.exception.BusinessException(
                    com.rigour.shared.core.api.ErrorCode.BAD_REQUEST,
                    "附件须为非空且单个不超过10MB，最多20份",
                    List.of());
        var result = new ArrayList<TransportSubmissionService.Content>();
        for (var file : files)
            result.add(
                    new TransportSubmissionService.Content(
                            file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        return result;
    }

    @GetMapping
    public ApiResponse<Page> page(@ModelAttribute LogisticsCostQuery query) {
        return ApiResponse.success(service.page(query, false));
    }

    @GetMapping("/export")
    public ApiResponse<Page> export(@ModelAttribute LogisticsCostQuery query) {
        return ApiResponse.success(service.page(query, true));
    }

    @PostMapping("/export-selected")
    public ApiResponse<List<Detail>> selected(@RequestBody List<String> ids) {
        return ApiResponse.success(service.selected(ids));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable String id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Detail> create(@RequestBody Draft draft) {
        return ApiResponse.success(service.save(null, draft));
    }

    @PutMapping("/{id}")
    public ApiResponse<Detail> save(@PathVariable String id, @RequestBody Draft draft) {
        return ApiResponse.success(service.save(id, draft));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Detail> confirm(
            @PathVariable String id, @RequestBody LogisticsCostActionCommand action) {
        return ApiResponse.success(service.confirm(id, action));
    }

    @GetMapping("/{id}/vouchers/{voucherId}")
    public ApiResponse<Map<String, String>> voucher(
            @PathVariable String id,
            @PathVariable String voucherId,
            @RequestParam(defaultValue = "false") boolean download) {
        return ApiResponse.success(Map.of("url", service.voucher(id, voucherId, download)));
    }
}
