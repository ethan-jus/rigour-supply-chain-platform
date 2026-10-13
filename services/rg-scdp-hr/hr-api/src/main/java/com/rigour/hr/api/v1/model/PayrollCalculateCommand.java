package com.rigour.hr.api.v1.model;

import java.util.List;

public record PayrollCalculateCommand(String month, List<String> employeeCodes) {}
