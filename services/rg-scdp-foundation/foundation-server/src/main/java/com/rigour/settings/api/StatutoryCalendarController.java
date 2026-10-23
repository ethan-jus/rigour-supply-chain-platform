package com.rigour.settings.api;

import com.rigour.settings.api.v1.StatutoryCalendarApi;
import com.rigour.settings.api.v1.model.StatutoryCalendarView;
import com.rigour.settings.application.service.StatutoryCalendarService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(StatutoryCalendarApi.BASE_PATH)
public class StatutoryCalendarController implements StatutoryCalendarApi {
    private final StatutoryCalendarService service;

    public StatutoryCalendarController(StatutoryCalendarService service) {
        this.service = service;
    }

    @GetMapping("/{year}")
    public ApiResponse<StatutoryCalendarView> year(@PathVariable int year) {
        AuthorizationContext.requirePermission("business-settings:dict:read");
        return ApiResponse.success(service.year(year));
    }
}
