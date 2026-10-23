package com.rigour.settings.domain.calendar;

import java.time.LocalDate;

public record StatutorySourceDay(String name, LocalDate date, Boolean isOffDay) {}
