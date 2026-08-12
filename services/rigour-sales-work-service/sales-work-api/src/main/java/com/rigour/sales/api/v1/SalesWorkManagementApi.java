package com.rigour.sales.api.v1;

import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementDashboardView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementRecordingSessionView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementVisitEvidenceView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementVisitPlanPageView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementVisitPlanView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.CancelVisitPlanCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ReviewVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ReviewVisitResultView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.VisitReviewQueueView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.UpsertVisitPlanCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.VisitPlanProfileOptionView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetPageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayTrackView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;

/** 供应链 Portal 销售管理查询契约；不对移动销售端开放。 */
public interface SalesWorkManagementApi {

    String MANAGEMENT_BASE_PATH = SalesWorkApi.BASE_PATH + "/management";
    String DASHBOARD_PATH = MANAGEMENT_BASE_PATH + "/dashboard";
    String REVIEW_QUEUE_PATH = MANAGEMENT_BASE_PATH + "/review-queue";
    String REVIEW_VISIT_PATH = MANAGEMENT_BASE_PATH + "/visits/{visitId}/review";
    String REVIEW_RECORDINGS_PATH = MANAGEMENT_BASE_PATH + "/visits/{visitId}/recordings";
    String REVIEW_RECORDING_CLIP_PATH = REVIEW_RECORDINGS_PATH + "/clips/{clipId}";
    String REVIEW_EVIDENCE_PATH = MANAGEMENT_BASE_PATH + "/visits/{visitId}/evidence";
    String REVIEW_EVIDENCE_PHOTO_PATH = REVIEW_EVIDENCE_PATH + "/photos/{evidenceId}";
    String MANAGEMENT_TRACK_PATH = MANAGEMENT_BASE_PATH + "/profiles/{salesProfileId}/track";
    String VISIT_PLANS_PATH = MANAGEMENT_BASE_PATH + "/visit-plans";
    String VISIT_PLAN_PATH = VISIT_PLANS_PATH + "/{planId}";
    String CANCEL_VISIT_PLAN_PATH = VISIT_PLAN_PATH + "/cancel";
    String VISIT_PLAN_PROFILES_PATH = VISIT_PLANS_PATH + "/profiles";
    String VISIT_PLAN_TARGETS_PATH = VISIT_PLANS_PATH + "/profiles/{salesProfileId}/targets";

    @GetMapping(DASHBOARD_PATH)
    ApiResponse<ManagementDashboardView> dashboard(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to);

    @GetMapping(REVIEW_QUEUE_PATH)
    ApiResponse<VisitReviewQueueView> reviewQueue(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize);

    @PutMapping(REVIEW_VISIT_PATH)
    ApiResponse<ReviewVisitResultView> reviewVisit(
            @PathVariable("visitId") UUID visitId,
            @RequestBody ReviewVisitCommand command);

    @GetMapping(REVIEW_RECORDINGS_PATH)
    ApiResponse<ManagementRecordingSessionView> reviewRecordings(
            @PathVariable("visitId") UUID visitId);

    @GetMapping(REVIEW_RECORDING_CLIP_PATH)
    ResponseEntity<byte[]> reviewRecordingClip(
            @PathVariable("visitId") UUID visitId,
            @PathVariable("clipId") UUID clipId);

    @GetMapping(REVIEW_EVIDENCE_PATH)
    ApiResponse<ManagementVisitEvidenceView> reviewEvidence(
            @PathVariable("visitId") UUID visitId);

    @GetMapping(REVIEW_EVIDENCE_PHOTO_PATH)
    ResponseEntity<byte[]> reviewEvidencePhoto(
            @PathVariable("visitId") UUID visitId,
            @PathVariable("evidenceId") UUID evidenceId);

    @GetMapping(MANAGEMENT_TRACK_PATH)
    ApiResponse<WorkDayTrackView> managementTrack(
            @PathVariable("salesProfileId") UUID salesProfileId,
            @RequestParam("date") LocalDate date);

    @GetMapping(VISIT_PLANS_PATH)
    ApiResponse<ManagementVisitPlanPageView> visitPlans(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize);

    @PostMapping(VISIT_PLANS_PATH)
    ApiResponse<ManagementVisitPlanView> createVisitPlan(@RequestBody UpsertVisitPlanCommand command);

    @PutMapping(VISIT_PLAN_PATH)
    ApiResponse<ManagementVisitPlanView> updateVisitPlan(
            @PathVariable("planId") UUID planId,
            @RequestBody UpsertVisitPlanCommand command);

    @PutMapping(CANCEL_VISIT_PLAN_PATH)
    ApiResponse<ManagementVisitPlanView> cancelVisitPlan(
            @PathVariable("planId") UUID planId,
            @RequestBody CancelVisitPlanCommand command);

    @GetMapping(VISIT_PLAN_PROFILES_PATH)
    ApiResponse<java.util.List<VisitPlanProfileOptionView>> visitPlanProfiles();

    @GetMapping(VISIT_PLAN_TARGETS_PATH)
    ApiResponse<VisitTargetPageView> visitPlanTargets(
            @PathVariable("salesProfileId") UUID salesProfileId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize);
}
