package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 货拉拉控制面只返回凭据就绪状态，绝不序列化应用密钥或令牌。 */
public final class HuolalaModels {
    private HuolalaModels() {}

    public record Connection(
            String id,
            String enterpriseId,
            String enterpriseName,
            String secretRef,
            boolean sandbox,
            String redirectUri,
            boolean enabled,
            int intervalMinutes,
            LocalDate startDate,
            String authStatus,
            Instant expiresAt,
            boolean credentialsReady,
            long revision,
            Instant lastSuccessAt,
            String lastError) {}

    public record SaveConnection(
            String enterpriseId,
            String enterpriseName,
            String secretRef,
            boolean sandbox,
            String redirectUri,
            boolean enabled,
            int intervalMinutes,
            LocalDate startDate,
            long revision) {}

    public record AuthorizationStart(String url, String state, Instant expiresAt) {}

    public record AuthorizationFinish(String credential, String state) {
        @Override
        public String toString() {
            return "AuthorizationFinish[REDACTED]";
        }
    }

    public record SyncCommand(String scope, LocalDate from, LocalDate to, String orderNo) {}

    public record Run(
            String id,
            String scope,
            String status,
            long fetched,
            long accepted,
            long unchanged,
            long rejected,
            Instant startedAt,
            Instant finishedAt,
            String message,
            StageCounts stages) {
        public Run(
                String id,
                String scope,
                String status,
                long fetched,
                long accepted,
                long unchanged,
                long rejected,
                Instant startedAt,
                Instant finishedAt,
                String message) {
            this(
                    id,
                    scope,
                    status,
                    fetched,
                    accepted,
                    unchanged,
                    rejected,
                    startedAt,
                    finishedAt,
                    message,
                    null);
        }
    }

    public record StageCounts(
            long listSaved, long detailSaved, long detailPending, long orderFailed) {}

    public record SyncIssue(
            String sourceType, String sourceId, String message, Instant occurredAt) {}

    public record Employee(
            String staffId,
            String userId,
            String name,
            String phone,
            String staffNo,
            String externalDepartmentId,
            String employeeCode,
            String employeeName,
            String departmentName,
            String state,
            long revision,
            String externalDepartmentName) {
        public Employee(
                String staffId,
                String userId,
                String name,
                String phone,
                String staffNo,
                String externalDepartmentId,
                String employeeCode,
                String employeeName,
                String departmentName,
                String state,
                long revision) {
            this(
                    staffId,
                    userId,
                    name,
                    phone,
                    staffNo,
                    externalDepartmentId,
                    employeeCode,
                    employeeName,
                    departmentName,
                    state,
                    revision,
                    null);
        }
    }

    public record EmployeeCandidate(
            Long id,
            String employeeCode,
            String employeeName,
            String mobile,
            String departmentName,
            String employmentStatus) {}

    public record BindEmployee(String staffId, Long employeeId, long revision, String reason) {}

    public record Page<T>(long total, List<T> items) {}
}
