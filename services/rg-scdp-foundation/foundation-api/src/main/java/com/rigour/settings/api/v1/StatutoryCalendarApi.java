package com.rigour.settings.api.v1;

import com.rigour.settings.api.v1.model.StatutoryCalendarView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

public interface StatutoryCalendarApi {
    String BASE_PATH = "/api/v1/business-settings/statutory-calendars";

    @GetMapping("/{year}")
    ApiResponse<StatutoryCalendarView> year(@PathVariable int year);
}
