package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.StoreVisitQueryModels.Page;
import com.rigour.merchant.application.service.StoreVisitQueryService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/crm/store-visits/me")
public class StoreVisitQueryController {
    private final StoreVisitQueryService service;
    public StoreVisitQueryController(StoreVisitQueryService service){this.service=service;}
    @GetMapping public ApiResponse<Page> list(@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(defaultValue="all") String kind,@RequestParam(defaultValue="ALL") String status,
        @RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.list(from,to,kind,status,keyword,page,size));
    }
}
