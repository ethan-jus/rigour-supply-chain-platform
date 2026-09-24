package com.rigour.hr.api.v1.model;
/** targetEmployeeId is required; using the current employee confirms the link. */
public record DhbBindingReviewCommand(long expectedVersion, Long targetEmployeeId) {}
