package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.model.ReceivablesView;
import com.rigour.analytics.application.service.ReceivablesArchive;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics/supply/dashboard/receivables/archives")
public class ReceivablesArchiveController {
    private final ReceivablesArchive archives;

    public ReceivablesArchiveController(ReceivablesArchive archives) {
        this.archives = archives;
    }

    @GetMapping
    public ApiResponse<List<ReceivablesArchive.Revision>> list(
            @RequestParam LocalDate from, @RequestParam LocalDate asOf) {
        return ApiResponse.success(archives.revisions(from, asOf));
    }

    @PostMapping
    public ApiResponse<String> save(@RequestParam LocalDate from, @RequestParam LocalDate asOf)
            throws IOException {
        return ApiResponse.success(archives.save(from, asOf));
    }

    @GetMapping("/{version}")
    public ApiResponse<ReceivablesView> read(
            @PathVariable String version,
            @RequestParam LocalDate from,
            @RequestParam LocalDate asOf,
            @RequestParam(required = false) String aging,
            @RequestParam(required = false) String collector,
            @RequestParam(required = false) Boolean disputed,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size)
            throws IOException {
        return ApiResponse.success(
                archives.read(from, asOf, version, aging, page, size, collector, disputed));
    }
}
