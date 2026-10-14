package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesAttendanceSourceApi;
import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;
import com.rigour.sales.application.service.SalesAttendanceSourceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SalesAttendanceSourceController implements SalesAttendanceSourceApi {
    private final SalesAttendanceSourceService service;

    public SalesAttendanceSourceController(SalesAttendanceSourceService service) {
        this.service = service;
    }

    public ApiResponse<List<Evidence>> attendanceEvidence(Query query) {
        return ApiResponse.success(service.evidence(query));
    }
}
