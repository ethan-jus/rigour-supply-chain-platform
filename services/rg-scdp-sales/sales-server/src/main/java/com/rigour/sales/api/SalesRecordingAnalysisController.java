package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesRecordingAnalysisApi;
import com.rigour.sales.api.v1.model.SalesRecordingAnalysisModels.Visit;
import com.rigour.sales.application.service.SalesRecordingAnalysisService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SalesRecordingAnalysisController implements SalesRecordingAnalysisApi {
    private final SalesRecordingAnalysisService service;
    public SalesRecordingAnalysisController(SalesRecordingAnalysisService service) { this.service=service; }
    @Override public ApiResponse<Visit> own(UUID visitId) { return ApiResponse.success(service.read(visitId,false)); }
    @Override public ApiResponse<Visit> management(UUID visitId) { return ApiResponse.success(service.read(visitId,true)); }
    @Override public ApiResponse<Visit> retryOwn(UUID visitId, UUID clipId) { return ApiResponse.success(service.retry(visitId,clipId,false)); }
    @Override public ApiResponse<Visit> retryManagement(UUID visitId, UUID clipId) { return ApiResponse.success(service.retry(visitId,clipId,true)); }
}
