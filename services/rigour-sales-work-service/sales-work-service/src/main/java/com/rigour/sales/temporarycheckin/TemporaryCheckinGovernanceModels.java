package com.rigour.sales.temporarycheckin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 后台销售目录与受控物理删除的请求、响应模型。 */
public final class TemporaryCheckinGovernanceModels {

    private TemporaryCheckinGovernanceModels() { }

    public record AdminSalespersonView(
            UUID id,
            String name,
            String city,
            String position,
            String employmentStatus,
            String status,
            int sortOrder,
            boolean credentialConfigured,
            Instant createdAt,
            Instant updatedAt) { }

    public record AdminSalespersonPage(
            List<AdminSalespersonView> items,
            long total,
            int page,
            int size,
            int totalPages) { }

    public record SaveSalespersonRequest(
            String name,
            String city,
            String position,
            String employmentStatus,
            String status,
            Integer sortOrder) { }

    /** 个人码只在创建或重置成功的这一次响应中返回。 */
    public record SalespersonCredentialView(
            AdminSalespersonView salesperson,
            String temporaryCheckinCode) {
        @Override public String toString() {
            return "SalespersonCredentialView[salesperson=" + salesperson.id()
                    + ", temporaryCheckinCode=***]";
        }
    }

    public record SalespersonBootstrapCredential(
            UUID salespersonId,
            String name,
            String city,
            String temporaryCheckinCode) {
        @Override public String toString() {
            return "SalespersonBootstrapCredential[salespersonId=" + salespersonId
                    + ", temporaryCheckinCode=***]";
        }
    }

    public record SalespersonBootstrapResponse(
            List<SalespersonBootstrapCredential> credentials) {
        @Override public String toString() {
            return "SalespersonBootstrapResponse[count=" + credentials.size() + "]";
        }
    }

    public record ResetSalespersonCredentialRequest(String reason) { }

    public record SubmissionDeletionRequest(
            UUID requestId,
            List<UUID> ids,
            String reason,
            String confirmation) { }

    public record SubmissionDeletionFailure(UUID id, String code, String message) { }

    public record SubmissionDeletionView(
            UUID requestId,
            String status,
            int requestedCount,
            int deletedCount,
            int failedCount,
            List<SubmissionDeletionFailure> failures,
            Instant completedAt) { }
}
