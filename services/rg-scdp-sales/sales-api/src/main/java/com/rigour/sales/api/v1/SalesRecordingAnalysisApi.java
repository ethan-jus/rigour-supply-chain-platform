package com.rigour.sales.api.v1;

import com.rigour.sales.api.v1.model.SalesRecordingAnalysisModels.Visit;
import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

public interface SalesRecordingAnalysisApi {
    String OWN = "/api/v1/sales/me/visits/{visitId}/recording-analysis";
    String MANAGEMENT = "/api/v1/sales/management/visits/{visitId}/recording-analysis";
    @GetMapping(OWN) ApiResponse<Visit> own(@PathVariable("visitId") UUID visitId);
    @GetMapping(MANAGEMENT) ApiResponse<Visit> management(@PathVariable("visitId") UUID visitId);
    @PostMapping(OWN+"/{clipId}/retry") ApiResponse<Visit> retryOwn(@PathVariable("visitId") UUID visitId,@PathVariable("clipId") UUID clipId);
    @PostMapping(MANAGEMENT+"/{clipId}/retry") ApiResponse<Visit> retryManagement(@PathVariable("visitId") UUID visitId,@PathVariable("clipId") UUID clipId);
}
