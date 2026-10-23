package com.rigour.hr.api.v1.model;

public record PayrollComponentActionCommand(
        String requestId, String action, Integer revision, Integer stateRevision, String reason) {}
