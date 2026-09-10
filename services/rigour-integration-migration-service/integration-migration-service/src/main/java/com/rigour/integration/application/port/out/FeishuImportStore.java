package com.rigour.integration.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 飞书导入批次持久化端口；Integration 只保存导入事实和审计状态。 */
public interface FeishuImportStore {
    void savePreflight(PreflightBatch batch);

    Optional<StoredBatch> batch(UUID tenantId, UUID batchId);

    List<StoredBatch> recentBatches(UUID tenantId, int limit);

    List<StoredRawRow> rawRowsForRun(UUID tenantId, UUID batchId, int limit, boolean replayProjected);

    default List<StoredRawRow> rawRowsForBatch(UUID tenantId, UUID batchId, int limit) {
        return rawRowsForRun(tenantId, batchId, limit, true);
    }

    default Map<String, Long> rawRowProjectionStatusCounts(UUID tenantId, UUID batchId) {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        for (StoredRawRow row : rawRowsForBatch(tenantId, batchId, 100_000)) {
            result.merge(row.projectionStatus() == null ? "PENDING" : row.projectionStatus(), 1L, Long::sum);
        }
        return Map.copyOf(result);
    }

    default List<ProjectionIssueSummary> rawRowProjectionIssueSummaries(UUID tenantId, UUID batchId) {
        Map<String, ProjectionIssueSummaryAccumulator> summaries = new java.util.LinkedHashMap<>();
        for (StoredRawRow row : rawRowsForBatch(tenantId, batchId, 100_000)) {
            String status = row.projectionStatus() == null || row.projectionStatus().isBlank()
                    ? "PENDING" : row.projectionStatus();
            if ("PROJECTED".equals(status) || "SKIPPED".equals(status)) continue;
            String targetDomain = firstNonBlank(row.targetDomain(), row.domainCode());
            String targetObjectType = firstNonBlank(row.targetObjectType(), row.objectType());
            String errorCode = firstNonBlank(row.errorCode(), "FEISHU_PROJECTION_PENDING");
            String errorMessage = firstNonBlank(row.errorMessage(), "等待内部领域投影处理");
            String key = status + "|" + targetDomain + "|" + targetObjectType + "|" + errorCode + "|" + errorMessage;
            summaries.computeIfAbsent(key, ignored -> new ProjectionIssueSummaryAccumulator(
                            status, targetDomain, targetObjectType, errorCode, errorMessage))
                    .increment();
        }
        return summaries.values().stream()
                .map(ProjectionIssueSummaryAccumulator::summary)
                .toList();
    }

    default RawRowAttachmentStatus rawRowAttachmentStatus(UUID tenantId, UUID batchId) {
        int uploaded = 0;
        int failedRows = 0;
        String tenantPrefix = tenantId == null ? "" : tenantId.toString() + "/feishu-attachments/";
        for (StoredRawRow row : rawRowsForBatch(tenantId, batchId, 100_000)) {
            if (row.attachmentRefs().isEmpty()) continue;
            boolean hasAttachment = false;
            boolean hasUploaded = false;
            for (List<String> refs : row.attachmentRefs().values()) {
                if (refs == null || refs.isEmpty()) continue;
                hasAttachment = true;
                for (String ref : refs) {
                    if (!hasText(ref)) continue;
                    String value = ref.strip();
                    if (value.startsWith(tenantPrefix) || value.startsWith("feishu-attachments/")
                            || value.contains("/feishu-attachments/")) {
                        uploaded++;
                        hasUploaded = true;
                    }
                }
            }
            if (hasAttachment && !hasUploaded && isAttachmentFailure(row)) failedRows++;
        }
        return new RawRowAttachmentStatus(uploaded, failedRows);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isAttachmentFailure(StoredRawRow row) {
        String code = row.errorCode();
        if (code != null && code.startsWith("FEISHU_ATTACHMENT_")) return true;
        String message = row.errorMessage();
        return message != null && (message.contains("附件未入库") || message.contains("不能下载附件")
                || message.contains("附件下载") || message.contains("COS上传"));
    }

    default List<ImportTemplate> importTemplates(UUID tenantId) {
        return List.of();
    }

    default Map<String, ExistingDeduplicationRow> existingDeduplicationRows(UUID tenantId, Iterable<String> keys) {
        return Map.of();
    }

    void updateRawRowProjection(RowProjectionUpdate update);

    default void updateRawRowProjections(List<RowProjectionUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;
        for (RowProjectionUpdate update : updates) {
            updateRawRowProjection(update);
        }
    }

    default void updateRawRowAttachments(RowAttachmentUpdate update) {
    }

    default void updateRawRowAttachments(List<RowAttachmentUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;
        for (RowAttachmentUpdate update : updates) {
            updateRawRowAttachments(update);
        }
    }

    void updateBatchStatus(UUID tenantId, UUID batchId, String status, UUID updatedBy,
                           Instant updatedAt);

    record PreflightBatch(
            UUID id,
            UUID tenantId,
            UUID createdBy,
            String sourceSystem,
            String sourceUrl,
            String originalFileName,
            long fileSizeBytes,
            String fileSha256,
            String status,
            int totalSheets,
            long totalRows,
            long duplicateRows,
            long attachmentReferenceCount,
            Instant createdAt,
            List<PreflightTable> tables,
            List<PreflightIssue> issues,
            List<PreflightRawRow> rawRows) {
        public PreflightBatch(UUID id, UUID tenantId, UUID createdBy, String sourceSystem,
                              String sourceUrl, String originalFileName, long fileSizeBytes,
                              String fileSha256, String status, int totalSheets, long totalRows,
                              long attachmentReferenceCount, Instant createdAt,
                              List<PreflightTable> tables, List<PreflightIssue> issues,
                              List<PreflightRawRow> rawRows) {
            this(id, tenantId, createdBy, sourceSystem, sourceUrl, originalFileName, fileSizeBytes,
                    fileSha256, status, totalSheets, totalRows, 0L, attachmentReferenceCount,
                    createdAt, tables, issues, rawRows);
        }

        public PreflightBatch {
            tables = tables == null ? List.of() : List.copyOf(tables);
            issues = issues == null ? List.of() : List.copyOf(issues);
            rawRows = rawRows == null ? List.of() : List.copyOf(rawRows);
        }
    }

    record PreflightTable(
            UUID id,
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
            List<String> attachmentFields) {
        public PreflightTable(UUID id, String sheetName, String tableCode, String domainCode,
                              String objectType, String mappingStatus, int headerRowNumber,
                              long rowCount, int columnCount, long attachmentReferenceCount,
                              List<String> headers, List<String> attachmentFields) {
            this(id, sheetName, tableCode, domainCode, objectType, mappingStatus, headerRowNumber,
                    rowCount, 0L, columnCount, attachmentReferenceCount, headers, attachmentFields);
        }

        public PreflightTable {
            headers = headers == null ? List.of() : List.copyOf(headers);
            attachmentFields = attachmentFields == null ? List.of() : List.copyOf(attachmentFields);
        }
    }

    record PreflightIssue(
            UUID id,
            String severity,
            String issueType,
            String tableName,
            Integer rowNumber,
            String fieldName,
            String message) {
    }

    record PreflightRawRow(
            UUID id,
            UUID tableId,
            String sheetName,
            String tableCode,
            String domainCode,
            String objectType,
            int rowNumber,
            String sourceDocumentNo,
            Instant sourceCreatedAt,
            String rowHash,
            Map<String, String> values,
            Map<String, List<String>> attachmentRefs,
            String deduplicationKey,
            String duplicateScope,
            UUID duplicateOfRawRowId,
            String importStatus,
            String projectionStatus) {
        public PreflightRawRow(UUID id, UUID tableId, String sheetName, String tableCode,
                               String domainCode, String objectType, int rowNumber,
                               String sourceDocumentNo, Instant sourceCreatedAt, String rowHash,
                               Map<String, String> values, Map<String, List<String>> attachmentRefs) {
            this(id, tableId, sheetName, tableCode, domainCode, objectType, rowNumber,
                    sourceDocumentNo, sourceCreatedAt, rowHash, values, attachmentRefs,
                    null, null, null, "IMPORTED", "PENDING");
        }

        public PreflightRawRow {
            values = values == null ? Map.of() : Map.copyOf(values);
            attachmentRefs = attachmentRefs == null ? Map.of() : Map.copyOf(attachmentRefs);
            importStatus = importStatus == null || importStatus.isBlank() ? "IMPORTED" : importStatus;
            projectionStatus = projectionStatus == null || projectionStatus.isBlank()
                    ? "PENDING" : projectionStatus;
        }
    }

    record StoredBatch(
            UUID id,
            UUID tenantId,
            String sourceSystem,
            String status,
            String originalFileName,
            String fileSha256,
            String sourceUrl,
            long fileSizeBytes,
            int totalSheets,
            long totalRows,
            long duplicateRows,
            long attachmentReferenceCount,
            Instant createdAt,
            Instant updatedAt) {
        public StoredBatch(UUID id, UUID tenantId, String sourceSystem, String status,
                           String originalFileName, String fileSha256, String sourceUrl,
                           long fileSizeBytes, int totalSheets, long totalRows,
                           long attachmentReferenceCount, Instant createdAt, Instant updatedAt) {
            this(id, tenantId, sourceSystem, status, originalFileName, fileSha256, sourceUrl,
                    fileSizeBytes, totalSheets, totalRows, 0L, attachmentReferenceCount,
                    createdAt, updatedAt);
        }
    }

    record StoredRawRow(
            UUID id,
            UUID batchId,
            UUID tableId,
            UUID tenantId,
            String sheetName,
            String tableCode,
            String domainCode,
            String objectType,
            int rowNumber,
            String sourceDocumentNo,
            Instant sourceCreatedAt,
            String projectionStatus,
            Map<String, String> values,
            Map<String, List<String>> attachmentRefs,
            String deduplicationKey,
            String duplicateScope,
            UUID duplicateOfRawRowId,
            String targetDomain,
            String targetObjectType,
            String targetId,
            String errorCode,
            String errorMessage) {
        public StoredRawRow(UUID id, UUID batchId, UUID tableId, UUID tenantId,
                            String sheetName, String tableCode, String domainCode,
                            String objectType, int rowNumber, String sourceDocumentNo,
                            Instant sourceCreatedAt, String projectionStatus,
                            Map<String, String> values, Map<String, List<String>> attachmentRefs) {
            this(id, batchId, tableId, tenantId, sheetName, tableCode, domainCode, objectType,
                    rowNumber, sourceDocumentNo, sourceCreatedAt, projectionStatus, values,
                    attachmentRefs, null, null, null, null, null, null, null, null);
        }

        public StoredRawRow(UUID id, UUID batchId, UUID tableId, UUID tenantId,
                            String sheetName, String tableCode, String domainCode,
                            String objectType, int rowNumber, String sourceDocumentNo,
                            Instant sourceCreatedAt, String projectionStatus,
                            Map<String, String> values, Map<String, List<String>> attachmentRefs,
                            String deduplicationKey, String duplicateScope, UUID duplicateOfRawRowId) {
            this(id, batchId, tableId, tenantId, sheetName, tableCode, domainCode, objectType,
                    rowNumber, sourceDocumentNo, sourceCreatedAt, projectionStatus, values,
                    attachmentRefs, deduplicationKey, duplicateScope, duplicateOfRawRowId,
                    null, null, null, null, null);
        }

        public StoredRawRow {
            values = values == null ? Map.of() : Map.copyOf(values);
            attachmentRefs = attachmentRefs == null ? Map.of() : Map.copyOf(attachmentRefs);
        }
    }

    record RowProjectionUpdate(
            UUID tenantId,
            UUID rawRowId,
            String projectionStatus,
            String targetDomain,
            String targetObjectType,
            String targetId,
            String errorCode,
            String errorMessage,
            UUID updatedBy,
            Instant updatedAt) {
    }

    record RowAttachmentUpdate(
            UUID tenantId,
            UUID rawRowId,
            Map<String, String> values,
            Map<String, List<String>> attachmentRefs,
            UUID updatedBy,
            Instant updatedAt) {
        public RowAttachmentUpdate {
            values = values == null ? Map.of() : Map.copyOf(values);
            attachmentRefs = attachmentRefs == null ? Map.of() : Map.copyOf(attachmentRefs);
        }
    }

    record RawRowAttachmentStatus(
            int uploadedAttachmentCount,
            int failedAttachmentRows) {
    }

    record ProjectionIssueSummary(
            String projectionStatus,
            String targetDomain,
            String targetObjectType,
            String errorCode,
            String errorMessage,
            long rowCount) {
    }

    final class ProjectionIssueSummaryAccumulator {
        private final String projectionStatus;
        private final String targetDomain;
        private final String targetObjectType;
        private final String errorCode;
        private final String errorMessage;
        private long rowCount;

        private ProjectionIssueSummaryAccumulator(String projectionStatus, String targetDomain,
                                                  String targetObjectType, String errorCode,
                                                  String errorMessage) {
            this.projectionStatus = projectionStatus;
            this.targetDomain = targetDomain;
            this.targetObjectType = targetObjectType;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        private void increment() {
            rowCount++;
        }

        private ProjectionIssueSummary summary() {
            return new ProjectionIssueSummary(projectionStatus, targetDomain, targetObjectType,
                    errorCode, errorMessage, rowCount);
        }
    }

    record ExistingDeduplicationRow(
            String deduplicationKey,
            UUID rawRowId,
            String rowHash,
            String projectionStatus,
            String targetDomain,
            String targetObjectType,
            String targetId) {
    }

    record ImportTemplate(
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
            List<ImportTemplateDependency> dependencies) {
        public ImportTemplate {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            requiredHeaders = requiredHeaders == null ? List.of() : List.copyOf(requiredHeaders);
            sourceDocumentFields = sourceDocumentFields == null ? List.of() : List.copyOf(sourceDocumentFields);
            sourceCreatedFields = sourceCreatedFields == null ? List.of() : List.copyOf(sourceCreatedFields);
            deduplicationFields = deduplicationFields == null ? List.of() : List.copyOf(deduplicationFields);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    record ImportTemplateDependency(
            String dependsOnTemplateCode,
            String relationKind,
            List<String> sourceReferenceFields,
            List<String> targetReferenceFields,
            boolean required) {
        public ImportTemplateDependency {
            sourceReferenceFields = sourceReferenceFields == null ? List.of() : List.copyOf(sourceReferenceFields);
            targetReferenceFields = targetReferenceFields == null ? List.of() : List.copyOf(targetReferenceFields);
        }
    }
}
