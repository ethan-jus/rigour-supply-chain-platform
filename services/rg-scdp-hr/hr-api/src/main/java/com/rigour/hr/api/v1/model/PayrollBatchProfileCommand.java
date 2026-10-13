package com.rigour.hr.api.v1.model;

import java.util.List;

public record PayrollBatchProfileCommand(
        List<PayrollProfilePatchCommand> employees, String reason) {}
