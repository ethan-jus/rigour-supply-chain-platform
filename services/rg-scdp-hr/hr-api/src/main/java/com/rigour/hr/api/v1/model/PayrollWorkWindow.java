package com.rigour.hr.api.v1.model;

import java.time.LocalTime;

public record PayrollWorkWindow(LocalTime start, LocalTime end, boolean nextDay, String outside) {}
