package com.rigour.analytics.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 已同步的范围摘要，不返回 HR 私人资料或认证信息。 */
public record BiScopeSyncResultView(UUID userId, String employeeCode, List<String> regionCodes,
                                    Instant verifiedAt, Instant expiresAt, String status) { }
