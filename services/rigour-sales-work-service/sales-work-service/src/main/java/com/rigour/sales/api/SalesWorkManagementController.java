package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkManagementApi;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementDashboardView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementRecordingSessionView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ReviewVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ReviewVisitResultView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.VisitReviewQueueView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayTrackView;
import com.rigour.sales.application.service.SalesWorkAttendanceService;
import com.rigour.sales.application.service.SalesWorkManagementService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 供应链 Portal 销售管控台查询入口。 */
@RestController
public final class SalesWorkManagementController {

    private final SalesWorkManagementService service;
    private final SalesWorkAttendanceService attendanceService;

    public SalesWorkManagementController(SalesWorkManagementService service,
                                         SalesWorkAttendanceService attendanceService) {
        this.service = service;
        this.attendanceService = attendanceService;
    }

    @GetMapping(SalesWorkManagementApi.DASHBOARD_PATH)
    public ApiResponse<ManagementDashboardView> dashboard(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to) {
        return ApiResponse.success(service.dashboard(from, to));
    }

    @GetMapping(SalesWorkManagementApi.REVIEW_QUEUE_PATH)
    public ApiResponse<VisitReviewQueueView> reviewQueue(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.reviewQueue(from, to, page, pageSize));
    }

    @PutMapping(SalesWorkManagementApi.REVIEW_VISIT_PATH)
    public ApiResponse<ReviewVisitResultView> reviewVisit(
            @PathVariable("visitId") UUID visitId,
            @RequestBody ReviewVisitCommand command) {
        return ApiResponse.success(service.reviewVisit(visitId, command));
    }

    @GetMapping(SalesWorkManagementApi.REVIEW_RECORDINGS_PATH)
    public ApiResponse<ManagementRecordingSessionView> reviewRecordings(
            @PathVariable("visitId") UUID visitId) {
        return ApiResponse.success(service.reviewRecordings(visitId));
    }

    @GetMapping(SalesWorkManagementApi.REVIEW_RECORDING_CLIP_PATH)
    public ResponseEntity<byte[]> reviewRecordingClip(
            @PathVariable("visitId") UUID visitId,
            @PathVariable("clipId") UUID clipId) {
        var content = service.reviewRecordingClip(visitId, clipId);
        byte[] bytes = content.bytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .contentLength(bytes.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"recording-" + clipId + "\"")
                .body(bytes);
    }

    @GetMapping(SalesWorkManagementApi.MANAGEMENT_TRACK_PATH)
    public ApiResponse<WorkDayTrackView> managementTrack(
            @PathVariable("salesProfileId") UUID salesProfileId,
            @RequestParam("date") LocalDate date) {
        return ApiResponse.success(attendanceService.managementTrack(salesProfileId, date));
    }
}
