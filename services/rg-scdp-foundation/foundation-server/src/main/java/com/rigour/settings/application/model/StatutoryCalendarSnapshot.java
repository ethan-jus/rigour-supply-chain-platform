package com.rigour.settings.application.model;

import com.rigour.settings.domain.calendar.StatutorySourceYear;

import java.time.Instant;

public record StatutoryCalendarSnapshot(
        int year,
        String status,
        StatutorySourceYear content,
        String version,
        Instant fetchedAt,
        Instant attemptedAt) {}
