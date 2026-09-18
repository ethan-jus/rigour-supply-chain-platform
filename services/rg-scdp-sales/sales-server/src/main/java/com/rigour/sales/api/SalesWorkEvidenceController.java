package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkApi;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitEvidenceSummaryView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPhotoEvidenceView;
import com.rigour.sales.application.service.SalesWorkEvidenceService;
import com.rigour.shared.core.api.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 销售本人现场门头照上传与证据摘要。 */
@RestController
public final class SalesWorkEvidenceController {

    private final SalesWorkEvidenceService service;

    public SalesWorkEvidenceController(SalesWorkEvidenceService service) {
        this.service = service;
    }

    @PostMapping(path = SalesWorkApi.VISIT_EVIDENCE_PATH + "/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<VisitPhotoEvidenceView> uploadStorefrontPhoto(
            @PathVariable("visitId") UUID visitId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("clientEvidenceId") String clientEvidenceId,
            @RequestParam("captureSource") String captureSource,
            @RequestParam("capturedAt")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedAt,
            @RequestParam("longitude") BigDecimal longitude,
            @RequestParam("latitude") BigDecimal latitude,
            @RequestParam("accuracyMeters") BigDecimal accuracyMeters) {
        return ApiResponse.success(service.uploadStorefrontPhoto(visitId, file, clientEvidenceId,
                captureSource, capturedAt, longitude, latitude, accuracyMeters));
    }

    @GetMapping(SalesWorkApi.VISIT_EVIDENCE_PATH)
    public ApiResponse<VisitEvidenceSummaryView> evidence(@PathVariable("visitId") UUID visitId) {
        return ApiResponse.success(service.evidence(visitId));
    }
}
