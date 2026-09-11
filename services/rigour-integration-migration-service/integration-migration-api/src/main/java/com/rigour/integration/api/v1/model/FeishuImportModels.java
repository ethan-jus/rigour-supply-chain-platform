package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 飞书导出文件导入中心 API 模型。 */
public final class FeishuImportModels {
    private FeishuImportModels() {
    }

    public record FeishuImportPreflightResult(
            UUID batchId,
            String status,
            String sourceSystem,
            String originalFileName,
            long fileSizeBytes,
            String fileSha256,
            String sourceUrl,
            int totalSheets,
            long totalRows,
            long duplicateRows,
            long attachmentReferenceCount,
            Instant createdAt,
            List<FeishuImportTablePreview> tables,
            List<FeishuImportIssueView> issues) {
        public FeishuImportPreflightResult {
            tables = tables == null ? List.of() : List.copyOf(tables);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record FeishuImportBatchSummary(
            UUID batchId,
            String status,
            String sourceSystem,
            String originalFileName,
            long fileSizeBytes,
            String fileSha256,
            String sourceUrl,
            int totalSheets,
            long totalRows,
            long duplicateRows,
            long attachmentReferenceCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record FeishuImportTablePreview(
            String sheetName,
            String tableCode,
            String domainCode,
            String objectType,
            String mappingStatus,
            int headerRowNumber,
            long rowCount,
            long duplicateRows,
            int columnCount,
            long attachmentReferenceCount,
            List<String> headers,
            List<String> attachmentFields,
            List<Map<String, String>> sampleRows) {
        public FeishuImportTablePreview {
            headers = headers == null ? List.of() : List.copyOf(headers);
            attachmentFields = attachmentFields == null ? List.of() : List.copyOf(attachmentFields);
            sampleRows = sampleRows == null ? List.of() : List.copyOf(sampleRows);
        }
    }

    public record FeishuImportIssueView(
            String severity,
            String issueType,
            String tableName,
            Integer rowNumber,
            String fieldName,
            String message,
            String issueCategory,
            boolean blocking,
            String resolutionAction,
            String resolutionHint) {
        public FeishuImportIssueView(String severity, String issueType, String tableName,
                                     Integer rowNumber, String fieldName, String message) {
            this(severity, issueType, tableName, rowNumber, fieldName, message,
                    "OTHER", "ERROR".equals(severity), "REVIEW", message);
        }
    }

    public record FeishuImportRunCommand(
            Integer maxRows,
            Boolean dryRun,
            Boolean replayProjected,
            Boolean async) {
        public FeishuImportRunCommand(Integer maxRows, Boolean dryRun) {
            this(maxRows, dryRun, false, false);
        }

        public FeishuImportRunCommand(Integer maxRows, Boolean dryRun, Boolean replayProjected) {
            this(maxRows, dryRun, replayProjected, false);
        }
    }

    public record FeishuImportRunResult(
            UUID batchId,
            String status,
            boolean dryRun,
            int totalRows,
            int projectedRows,
            int skippedRows,
            int waitingMappingRows,
            int failedRows,
            Instant executedAt,
            int uploadedAttachmentCount,
            int failedAttachmentRows,
            List<FeishuImportRunIssueSummaryView> issueSummaries,
            List<FeishuImportRunRowView> rows) {
        public FeishuImportRunResult(UUID batchId, String status, boolean dryRun, int totalRows,
                                     int projectedRows, int skippedRows, int waitingMappingRows,
                                     int failedRows, Instant executedAt,
                                     List<FeishuImportRunRowView> rows) {
            this(batchId, status, dryRun, totalRows, projectedRows, skippedRows,
                    waitingMappingRows, failedRows, executedAt, 0, 0, List.of(), rows);
        }

        public FeishuImportRunResult(UUID batchId, String status, boolean dryRun, int totalRows,
                                     int projectedRows, int skippedRows, int waitingMappingRows,
                                     int failedRows, Instant executedAt,
                                     int uploadedAttachmentCount, int failedAttachmentRows,
                                     List<FeishuImportRunRowView> rows) {
            this(batchId, status, dryRun, totalRows, projectedRows, skippedRows,
                    waitingMappingRows, failedRows, executedAt, uploadedAttachmentCount,
                    failedAttachmentRows, List.of(), rows);
        }

        public FeishuImportRunResult {
            issueSummaries = issueSummaries == null ? List.of() : List.copyOf(issueSummaries);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record FeishuImportRunIssueSummaryView(
            String issueCategory,
            String projectionStatus,
            String targetDomain,
            String targetObjectType,
            String message,
            long rowCount) {
    }

    public record FeishuImportRunRowView(
            UUID rawRowId,
            String sheetName,
            Integer rowNumber,
            String tableCode,
            String sourceDocumentNo,
            String projectionStatus,
            String targetDomain,
            String targetObjectType,
            String targetId,
            String message) {
    }

    public record FeishuImportTemplateView(
            String templateCode,
            String templateName,
            String sourceSystem,
            String domainCode,
            String objectType,
            List<String> aliases,
            List<String> requiredHeaders,
            List<String> sourceDocumentFields,
            List<String> sourceCreatedFields,
            String deduplicationStrategy,
            List<String> deduplicationFields,
            boolean readyByDefault,
            List<FeishuImportTemplateDependencyView> dependencies) {
        public FeishuImportTemplateView {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            requiredHeaders = requiredHeaders == null ? List.of() : List.copyOf(requiredHeaders);
            sourceDocumentFields = sourceDocumentFields == null ? List.of() : List.copyOf(sourceDocumentFields);
            sourceCreatedFields = sourceCreatedFields == null ? List.of() : List.copyOf(sourceCreatedFields);
            deduplicationFields = deduplicationFields == null ? List.of() : List.copyOf(deduplicationFields);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    public record FeishuImportTemplateDependencyView(
            String dependsOnTemplateCode,
            String relationKind,
            List<String> sourceReferenceFields,
            List<String> targetReferenceFields,
            boolean required) {
        public FeishuImportTemplateDependencyView {
            sourceReferenceFields = sourceReferenceFields == null ? List.of() : List.copyOf(sourceReferenceFields);
            targetReferenceFields = targetReferenceFields == null ? List.of() : List.copyOf(targetReferenceFields);
        }
    }
}
