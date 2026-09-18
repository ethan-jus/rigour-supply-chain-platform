package com.rigour.sales.api.v1;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CreateVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.InterruptionCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchResult;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.NearbyStorePageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.RecordingClipView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.RecordingSessionView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.SalesContextView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetPageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitActivitySummaryView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitResultCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitEvidenceSummaryView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPhotoEvidenceView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPlanListView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayTrackView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.AttendanceMonthView;
import com.rigour.shared.core.api.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/** Sales Work V1 的 H5 销售作业契约；不暴露跨域数据库模型。 */
public interface SalesWorkApi {

    String BASE_PATH = "/api/v1/sales";
    String CONTEXT_PATH = BASE_PATH + "/me/context";
    String VISIT_TARGETS_PATH = BASE_PATH + "/me/visit-targets";
    String CHECK_IN_PATH = BASE_PATH + "/work-days/check-in";
    String LOCATION_BATCH_PATH = BASE_PATH + "/work-days/{workDayId}/location-points:batch";
    String INTERRUPTION_PATH = BASE_PATH + "/work-days/{workDayId}/interruptions";
    String CHECK_OUT_PATH = BASE_PATH + "/work-days/{workDayId}/check-out";
    String WORK_DAY_PATH = BASE_PATH + "/me/work-days/{date}";
    String WORK_DAY_MONTH_PATH = BASE_PATH + "/me/work-days/month";
    String WORK_DAY_TRACK_PATH = WORK_DAY_PATH + "/track";
    String NEARBY_STORES_PATH = BASE_PATH + "/me/nearby-stores";
    String VISITS_PATH = BASE_PATH + "/me/visits";
    String VISIT_PLANS_PATH = BASE_PATH + "/me/visit-plans";
    String ACTIVITY_SUMMARY_PATH = BASE_PATH + "/me/activity-summary";
    String VISIT_PATH = BASE_PATH + "/me/visits/{visitId}";
    String VISIT_CHECK_OUT_PATH = BASE_PATH + "/me/visits/{visitId}/check-out";
    String VISIT_RESULT_PATH = BASE_PATH + "/me/visits/{visitId}/result";
    String VISIT_RECORDINGS_PATH = BASE_PATH + "/me/visits/{visitId}/recordings";
    String VISIT_EVIDENCE_PATH = BASE_PATH + "/me/visits/{visitId}/evidence";

    @GetMapping(CONTEXT_PATH)
    ApiResponse<SalesContextView> context();

    @GetMapping(VISIT_TARGETS_PATH)
    ApiResponse<VisitTargetPageView> visitTargets(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize);

    @PostMapping(CHECK_IN_PATH)
    ApiResponse<WorkDayView> checkIn(@RequestBody CheckInCommand command);

    @PostMapping(LOCATION_BATCH_PATH)
    ApiResponse<LocationBatchResult> uploadLocationPoints(@PathVariable("workDayId") UUID workDayId,
                                                          @RequestBody LocationBatchCommand command);

    @PostMapping(CHECK_OUT_PATH)
    ApiResponse<WorkDayView> checkOut(@PathVariable("workDayId") UUID workDayId, @RequestBody CheckOutCommand command);

    @PostMapping(INTERRUPTION_PATH)
    ApiResponse<WorkDayView> reportInterruption(@PathVariable("workDayId") UUID workDayId,
                                                @RequestBody InterruptionCommand command);

    @GetMapping(WORK_DAY_PATH)
    ApiResponse<WorkDayView> workDay(@PathVariable("date") LocalDate date);

    @GetMapping(WORK_DAY_MONTH_PATH)
    ApiResponse<AttendanceMonthView> attendanceMonth(@RequestParam("month") String month);

    @GetMapping(WORK_DAY_TRACK_PATH)
    ApiResponse<WorkDayTrackView> workDayTrack(@PathVariable("date") LocalDate date);

    @GetMapping(NEARBY_STORES_PATH)
    ApiResponse<NearbyStorePageView> nearbyStores(
            @RequestParam("longitude") BigDecimal longitude,
            @RequestParam("latitude") BigDecimal latitude,
            @RequestParam(name = "radiusMeters", required = false) Integer radiusMeters,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize);

    @PostMapping(VISITS_PATH)
    ApiResponse<VisitView> createVisit(@RequestBody CreateVisitCommand command);

    @GetMapping(VISIT_PLANS_PATH)
    ApiResponse<VisitPlanListView> visitPlans(@RequestParam("date") LocalDate date);

    @GetMapping(VISITS_PATH)
    ApiResponse<VisitPageView> visits(
            @RequestParam(name = "date", required = false) LocalDate date,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize);

    @GetMapping(ACTIVITY_SUMMARY_PATH)
    ApiResponse<VisitActivitySummaryView> activitySummary(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to);

    @GetMapping(VISIT_PATH)
    ApiResponse<VisitView> visit(@PathVariable("visitId") UUID visitId);

    @PostMapping(VISIT_CHECK_OUT_PATH)
    ApiResponse<VisitView> checkOutVisit(@PathVariable("visitId") UUID visitId,
                                         @RequestBody CheckOutVisitCommand command);

    @PutMapping(VISIT_RESULT_PATH)
    ApiResponse<VisitView> submitVisitResult(@PathVariable("visitId") UUID visitId,
                                             @RequestBody VisitResultCommand command);

    @PostMapping(path = VISIT_RECORDINGS_PATH + "/clips",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<RecordingClipView> uploadRecordingClip(
            @PathVariable("visitId") UUID visitId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("clientClipId") String clientClipId,
            @RequestParam("durationMs") Long durationMs,
            @RequestParam("recordedFrom")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedFrom,
            @RequestParam("recordedTo")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedTo);

    @PostMapping(VISIT_RECORDINGS_PATH + "/discarded-clips")
    ApiResponse<DiscardRecordingClipView> discardRecordingClip(
            @PathVariable("visitId") UUID visitId,
            @RequestBody DiscardRecordingClipCommand command);

    @GetMapping(VISIT_RECORDINGS_PATH)
    ApiResponse<RecordingSessionView> recordings(@PathVariable("visitId") UUID visitId);

    @PostMapping(path = VISIT_EVIDENCE_PATH + "/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<VisitPhotoEvidenceView> uploadStorefrontPhoto(
            @PathVariable("visitId") UUID visitId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("clientEvidenceId") String clientEvidenceId,
            @RequestParam("captureSource") String captureSource,
            @RequestParam("capturedAt")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedAt,
            @RequestParam("longitude") BigDecimal longitude,
            @RequestParam("latitude") BigDecimal latitude,
            @RequestParam("accuracyMeters") BigDecimal accuracyMeters);

    @GetMapping(VISIT_EVIDENCE_PATH)
    ApiResponse<VisitEvidenceSummaryView> visitEvidence(@PathVariable("visitId") UUID visitId);
}
