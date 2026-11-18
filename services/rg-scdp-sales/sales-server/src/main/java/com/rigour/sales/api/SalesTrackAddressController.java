package com.rigour.sales.api;

import com.rigour.sales.application.service.SalesTrackAddressService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class SalesTrackAddressController {
    private final SalesTrackAddressService service;
    public SalesTrackAddressController(SalesTrackAddressService service) { this.service = service; }
    @GetMapping("/api/v1/sales/me/work-days/{date}/punch-addresses")
    public ApiResponse<List<SalesTrackAddressService.PunchAddress>> addresses(@PathVariable("date") LocalDate date) {
        return ApiResponse.success(service.ownPunchAddresses(date));
    }
}
