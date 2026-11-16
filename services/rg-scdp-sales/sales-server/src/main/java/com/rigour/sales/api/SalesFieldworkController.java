package com.rigour.sales.api;
import com.rigour.sales.application.service.SalesFieldworkService;
import com.rigour.sales.api.v1.model.SalesFieldworkModels.Page;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
@RestController
public class SalesFieldworkController {
    private final SalesFieldworkService service;
    public SalesFieldworkController(SalesFieldworkService service) { this.service=service; }
    @GetMapping("/api/v1/sales/management/fieldwork/departments")
    public ApiResponse<java.util.List<com.rigour.sales.api.v1.model.SalesFieldworkModels.Department>> departments() {return ApiResponse.success(service.departments());}
    @GetMapping("/api/v1/sales/management/fieldwork")
    public ApiResponse<Page> page(@RequestParam LocalDate date,@RequestParam(required=false) String status,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String departmentId,
            @RequestParam(defaultValue="false") boolean includeDescendants,@RequestParam(defaultValue="false") boolean pendingOnly,
            @RequestParam(required=false) String riskKind,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(service.page(date,status,keyword,departmentId,includeDescendants,pendingOnly,riskKind,page,pageSize));
    }
}
