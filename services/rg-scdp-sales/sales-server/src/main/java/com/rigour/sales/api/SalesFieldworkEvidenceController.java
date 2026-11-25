package com.rigour.sales.api;

import com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.*;
import com.rigour.sales.application.service.SalesFieldworkEvidenceService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class SalesFieldworkEvidenceController {
    private final SalesFieldworkEvidenceService service;
    public SalesFieldworkEvidenceController(SalesFieldworkEvidenceService service) {this.service=service;}
    @GetMapping("/api/v1/sales/management/profiles/{profile}/work-days/{day}/devices")
    public ApiResponse<DeviceEvidence> devices(@PathVariable UUID profile,@PathVariable UUID day) {return ApiResponse.success(service.devices(profile,day));}
    @GetMapping("/api/v1/sales/management/visits/{visit}/history")
    public ApiResponse<VisitHistory> history(@PathVariable UUID visit,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
            @RequestParam(defaultValue="false") boolean throughVisit, @RequestParam(required=false) Boolean fromFieldwork) {
        return ApiResponse.success(service.history(visit,page,pageSize,throughVisit,fromFieldwork != null ? fromFieldwork : !throughVisit));
    }
}
