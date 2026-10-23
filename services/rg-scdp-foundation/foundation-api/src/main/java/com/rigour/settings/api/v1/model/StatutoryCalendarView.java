package com.rigour.settings.api.v1.model;

import java.time.Instant;
import java.util.List;

public record StatutoryCalendarView(
        int year,
        String status,
        String version,
        Instant fetchedAt,
        List<String> sourceUrls,
        List<StatutoryCalendarDayView> days,
        boolean stale,
        String message) {}
