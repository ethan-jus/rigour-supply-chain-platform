package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

public record PayrollSchemeStopCommand(int revision, LocalDate stopFrom, String reason) {}
