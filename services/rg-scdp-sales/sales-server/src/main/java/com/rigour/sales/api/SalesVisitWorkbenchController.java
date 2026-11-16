package com.rigour.sales.api;

import com.rigour.merchant.api.v1.model.StoreVisitQueryModels.Page;
import com.rigour.sales.application.service.SalesVisitWorkbenchService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sales/me/visit-workbench")
public class SalesVisitWorkbenchController {
    private final SalesVisitWorkbenchService service;
    public SalesVisitWorkbenchController(SalesVisitWorkbenchService service){this.service=service;}
    @GetMapping public ApiResponse<Page> visits(@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(defaultValue="all") String kind,@RequestParam(defaultValue="ALL") String status,
        @RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.visits(from,to,kind,status,keyword,page,size));
    }
}
