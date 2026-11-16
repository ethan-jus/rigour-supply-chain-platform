package com.rigour.sales.api;
import com.rigour.sales.api.v1.SalesWorkAdminApi;
import com.rigour.sales.api.v1.model.SalesVisitPolicyModels.*;
import com.rigour.sales.application.service.SalesVisitPolicyService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController
public class SalesVisitPolicyController {
    private final SalesVisitPolicyService service;
    public SalesVisitPolicyController(SalesVisitPolicyService service) { this.service=service; }
    @GetMapping(SalesWorkAdminApi.VISIT_POLICY_PATH) public ApiResponse<List<Version>> versions() { return ApiResponse.success(service.versions()); }
    @GetMapping("/api/v1/sales/me/visit-policy") public ApiResponse<Snapshot> current() { return ApiResponse.success(service.current()); }
}
