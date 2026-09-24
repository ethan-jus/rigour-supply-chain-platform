package com.rigour.hr.api;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.HrDhbBindingReviewService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/hr/employees/dhb-link-risks")
public final class HrDhbBindingReviewController {
    private final HrDhbBindingReviewService service;
    public HrDhbBindingReviewController(HrDhbBindingReviewService service) { this.service=service; }
    @GetMapping
    public ApiResponse<List<DhbBindingReviewView>> pending() { return ApiResponse.success(service.pending()); }
    @PostMapping("/{id}/confirm")
    public ApiResponse<Boolean> confirm(@PathVariable long id, @RequestBody DhbBindingReviewCommand command) {
        service.confirm(id,command); return ApiResponse.success(true);
    }
}
