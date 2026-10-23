package com.rigour.settings.api.v1.model;

import java.time.LocalDate;

public record StatutoryCalendarDayView(LocalDate date, String name, boolean workday, String type) {}
