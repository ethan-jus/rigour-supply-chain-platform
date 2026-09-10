package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuBitableClient;
import com.rigour.integration.application.port.out.FeishuBitableClient.BitableField;
import com.rigour.integration.application.port.out.FeishuBitableClient.BitableRecord;
import com.rigour.integration.application.port.out.FeishuBitableClient.BitableTable;
import com.rigour.integration.application.port.out.FeishuBitableClient.DownloadedAttachment;
import com.rigour.integration.application.port.out.FeishuBitableClientException;
import com.rigour.integration.application.port.out.FeishuImportStore;
import com.rigour.integration.application.port.out.FeishuImportStore.RowAttachmentUpdate;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredBatch;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.shared.context.CallerIdentity;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 飞书附件落库前补齐服务。
 *
 * <p>Excel 导出只能给出附件字段的展示值；正式导入时通过 Base API 回查同一行的附件
 * file_token，下载后上传到 COS 私桶，并把导入行附件字段更新成内部 objectKey。</p>
 */
public final class FeishuAttachmentImportService {
    private static final Logger log = LoggerFactory.getLogger(FeishuAttachmentImportService.class);
    private static final int ATTACHMENT_UPDATE_FLUSH_SIZE = 50;
    private static final Pattern BASE_TOKEN_PATTERN = Pattern.compile("/base/([A-Za-z0-9]+)");
    private static final Set<String> TOKEN_KEYS = Set.of("file_token", "fileToken", "token", "media_token");
    private static final Set<String> NAME_KEYS = Set.of("name", "file_name", "fileName", "filename");
    private static final Set<String> TYPE_KEYS = Set.of("type", "mime_type", "mimeType", "content_type", "contentType");
    private static final List<String> WRAPPED_ATTACHMENT_KEYS = List.of("value", "values", "items", "files", "attachments");
    private static final List<String> VOUCHER_ATTACHMENT_FIELD_ALIASES = List.of(
            "回款凭证", "付款凭证", "支付凭证", "收款凭证",
            "回款附件", "付款附件", "支付附件", "收款附件");

    private final FeishuImportStore store;
    private final FeishuBitableClient bitableClient;
    private final ProductMediaStorage storage;
    private final FeishuAttachmentObjectKeyFactory keyFactory;
    private final String defaultSourceUrl;

    public FeishuAttachmentImportService(FeishuImportStore store,
                                         FeishuBitableClient bitableClient,
                                         ProductMediaStorage storage,
                                         FeishuAttachmentObjectKeyFactory keyFactory) {
        this(store, bitableClient, storage, keyFactory, null);
    }

    public FeishuAttachmentImportService(FeishuImportStore store,
                                         FeishuBitableClient bitableClient,
                                         ProductMediaStorage storage,
                                         FeishuAttachmentObjectKeyFactory keyFactory,
                                         String defaultSourceUrl) {
        this.store = store;
        this.bitableClient = bitableClient;
        this.storage = storage;
        this.keyFactory = keyFactory;
        this.defaultSourceUrl = defaultSourceUrl;
    }

    AttachmentResolution resolve(CallerIdentity caller, StoredBatch batch, List<StoredRawRow> sourceRows,
                                 UUID updatedBy, Instant updatedAt) {
        List<StoredRawRow> rows = sourceRows == null ? List.of() : List.copyOf(sourceRows);
        List<StoredRawRow> rowsWithAttachments = rows.stream()
                .filter(row -> !unresolvedAttachmentRefs(caller.tenantId(), row).isEmpty())
                .toList();
        if (rowsWithAttachments.isEmpty()) return AttachmentResolution.unchanged(rows);
        if (bitableClient == null || storage == null || keyFactory == null) {
            return failAll(rows, rowsWithAttachments, "FEISHU_ATTACHMENT_CLIENT_REQUIRED",
                    "飞书附件下载或COS上传组件未装配，不能正式落库附件行");
        }
        Optional<BaseLocator> locator = BaseLocator.parse(firstNonBlank(batch.sourceUrl(), defaultSourceUrl));
        if (locator.isEmpty()) {
            return failAll(rows, rowsWithAttachments, "FEISHU_ATTACHMENT_SOURCE_URL_REQUIRED",
                    "当前批次包含附件，但没有可解析的飞书Base地址，不能下载附件后落库");
        }

        Map<UUID, AttachmentFailure> failures = new LinkedHashMap<>();
        Map<UUID, StoredRawRow> resolvedRows = new LinkedHashMap<>();
        List<RowAttachmentUpdate> attachmentUpdates = new ArrayList<>();
        int uploaded = 0;
        Map<String, String> tableIdsBySheet;
        Map<String, Map<String, String>> fieldIdsBySheet;
        Map<String, Map<String, BitableRecord>> recordsBySheetSourceNo;
        try {
            tableIdsBySheet = tableIdsBySheet(locator.get(), rowsWithAttachments);
            fieldIdsBySheet = fieldIdsBySheet(locator.get(), rowsWithAttachments, tableIdsBySheet);
            recordsBySheetSourceNo = recordsBySheetSourceNo(locator.get(), rowsWithAttachments, tableIdsBySheet);
        } catch (FeishuBitableClientException exception) {
            return failAll(rows, rowsWithAttachments, safeErrorCode(exception.code(), "FEISHU_ATTACHMENT_METADATA_FAILED"),
                    failureMessage("飞书附件元数据读取失败", exception));
        } catch (RuntimeException exception) {
            log.warn("飞书附件元数据读取失败 tenantId={} batchId={} errorType={} message={}",
                    caller.tenantId(), batch.id(), exception.getClass().getSimpleName(),
                    safeFailureDetail(exception), exception);
            return failAll(rows, rowsWithAttachments, "FEISHU_ATTACHMENT_IMPORT_FAILED",
                    failureMessage("飞书附件元数据读取失败", exception));
        }
        Map<String, DownloadedAttachment> downloadCache = new HashMap<>();
        for (StoredRawRow row : rows) {
            Map<String, List<String>> unresolvedRefs = unresolvedAttachmentRefs(caller.tenantId(), row);
            if (unresolvedRefs.isEmpty()) {
                resolvedRows.put(row.id(), row);
                continue;
            }
            String tableId = tableIdsBySheet.get(normalize(row.sheetName()));
            if (!StringUtils.hasText(tableId)) {
                failures.put(row.id(), failure(row, "FEISHU_ATTACHMENT_TABLE_NOT_FOUND",
                        "飞书Base未找到工作表对应的数据表，不能定位附件"));
                resolvedRows.put(row.id(), row);
                continue;
            }
            BitableRecord record = recordForRow(recordsBySheetSourceNo, row);
            if (record == null) {
                failures.put(row.id(), failure(row, "FEISHU_ATTACHMENT_RECORD_NOT_FOUND",
                        "飞书Base未找到来源单号对应记录，不能定位附件"));
                resolvedRows.put(row.id(), row);
                continue;
            }
            try {
                RowAttachmentResult result = resolveRow(caller, row, record, tableId,
                        fieldIdsBySheet.getOrDefault(normalize(row.sheetName()), Map.of()), downloadCache);
                if (result.failure() != null) {
                    failures.put(row.id(), result.failure());
                    resolvedRows.put(row.id(), row);
                    continue;
                }
                uploaded += result.uploaded();
                resolvedRows.put(row.id(), result.row());
                attachmentUpdates.add(new RowAttachmentUpdate(caller.tenantId(), row.id(),
                        result.row().values(), result.row().attachmentRefs(), updatedBy, updatedAt));
                if (attachmentUpdates.size() >= ATTACHMENT_UPDATE_FLUSH_SIZE) {
                    flushAttachmentUpdates(caller, batch, attachmentUpdates, uploaded, failures.size());
                }
            } catch (FeishuBitableClientException exception) {
                String errorCode = safeErrorCode(exception.code(), "FEISHU_ATTACHMENT_DOWNLOAD_FAILED");
                failures.put(row.id(), failure(row, errorCode, failureMessage("飞书附件下载失败", exception)));
                resolvedRows.put(row.id(), row);
                logRowFailure(caller, batch, row, errorCode, exception);
            } catch (RuntimeException exception) {
                String errorCode = "FEISHU_ATTACHMENT_UPLOAD_FAILED";
                failures.put(row.id(), failure(row, errorCode, failureMessage("飞书附件下载或COS上传失败", exception)));
                resolvedRows.put(row.id(), row);
                logRowFailure(caller, batch, row, errorCode, exception);
            }
        }
        flushAttachmentUpdates(caller, batch, attachmentUpdates, uploaded, failures.size());
        List<StoredRawRow> ordered = rows.stream()
                .map(row -> resolvedRows.getOrDefault(row.id(), row))
                .toList();
        return new AttachmentResolution(ordered, Map.copyOf(failures), uploaded, failures.size());
    }

    private void flushAttachmentUpdates(CallerIdentity caller, StoredBatch batch,
                                        List<RowAttachmentUpdate> attachmentUpdates,
                                        int uploaded, int failedRows) {
        if (attachmentUpdates.isEmpty()) return;
        int updatedRows = attachmentUpdates.size();
        store.updateRawRowAttachments(List.copyOf(attachmentUpdates));
        attachmentUpdates.clear();
        log.info("飞书导入附件写回进度 tenantId={} batchId={} updatedRows={} uploaded={} failedRows={}",
                caller.tenantId(), batch.id(), updatedRows, uploaded, failedRows);
    }

    private Map<String, String> tableIdsBySheet(BaseLocator locator, List<StoredRawRow> rows) {
        List<BitableTable> tables = bitableClient.tables(locator.appToken());
        Map<String, String> byName = new HashMap<>();
        for (BitableTable table : tables) {
            byName.putIfAbsent(normalize(table.name()), table.tableId());
        }
        Map<String, List<StoredRawRow>> rowsBySheet = rowsBySheet(rows);
        boolean singleSheet = rowsBySheet.size() == 1;
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, List<StoredRawRow>> entry : rowsBySheet.entrySet()) {
            String normalizedSheet = entry.getKey();
            String tableId = tableIdForCandidates(tableNameCandidates(entry.getValue()), byName, tables);
            if (!StringUtils.hasText(tableId) && singleSheet && StringUtils.hasText(locator.tableId())) {
                tableId = locator.tableId();
            }
            if (StringUtils.hasText(tableId)) result.put(normalizedSheet, tableId);
        }
        return Map.copyOf(result);
    }

    private static List<String> tableNameCandidates(List<StoredRawRow> rows) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (StoredRawRow row : rows) {
            if (StringUtils.hasText(row.sheetName())) candidates.add(row.sheetName());
            candidates.addAll(FeishuImportTableCatalog.aliases(row.tableCode()));
            if (StringUtils.hasText(row.tableCode())) candidates.add(row.tableCode());
        }
        return List.copyOf(candidates);
    }

    private static String tableIdForCandidates(List<String> candidates, Map<String, String> byName,
                                               List<BitableTable> tables) {
        for (String candidate : candidates) {
            String tableId = byName.get(normalize(candidate));
            if (StringUtils.hasText(tableId)) return tableId;
        }
        for (String candidate : candidates) {
            String normalizedCandidate = normalize(candidate);
            if (!StringUtils.hasText(normalizedCandidate)) continue;
            for (BitableTable table : tables) {
                String normalizedTable = normalize(table.name());
                if (normalizedCandidate.contains(normalizedTable)
                        || normalizedTable.contains(normalizedCandidate)) {
                    return table.tableId();
                }
            }
        }
        return null;
    }

    private Map<String, Map<String, String>> fieldIdsBySheet(
            BaseLocator locator, List<StoredRawRow> rows, Map<String, String> tableIdsBySheet) {
        Map<String, List<StoredRawRow>> rowsBySheet = rowsBySheet(rows);
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<StoredRawRow>> entry : rowsBySheet.entrySet()) {
            String tableId = tableIdsBySheet.get(entry.getKey());
            if (!StringUtils.hasText(tableId)) continue;
            Map<String, String> fieldIdsByName = new HashMap<>();
            for (BitableField field : bitableClient.fields(locator.appToken(), tableId)) {
                fieldIdsByName.putIfAbsent(normalize(field.name()), field.fieldId());
            }
            result.put(entry.getKey(), Map.copyOf(fieldIdsByName));
        }
        return Map.copyOf(result);
    }

    private Map<String, Map<String, BitableRecord>> recordsBySheetSourceNo(
            BaseLocator locator, List<StoredRawRow> rows, Map<String, String> tableIdsBySheet) {
        Map<String, List<StoredRawRow>> rowsBySheet = rowsBySheet(rows);
        Map<String, Map<String, BitableRecord>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<StoredRawRow>> entry : rowsBySheet.entrySet()) {
            String tableId = tableIdsBySheet.get(entry.getKey());
            if (!StringUtils.hasText(tableId)) continue;
            String viewId = tableId.equals(locator.tableId()) ? locator.viewId() : null;
            List<BitableRecord> records = bitableClient.records(locator.appToken(), tableId, viewId);
            result.put(entry.getKey(), indexRecords(entry.getValue(), records));
        }
        return result;
    }

    private Map<String, BitableRecord> indexRecords(List<StoredRawRow> rows, List<BitableRecord> records) {
        Map<String, String> sourceNoIndex = new HashMap<>();
        Set<String> sourceNoFields = new HashSet<>();
        for (StoredRawRow row : rows) {
            if (StringUtils.hasText(row.sourceDocumentNo())) {
                sourceNoIndex.put(normalizeText(row.sourceDocumentNo()), row.sourceDocumentNo());
                for (Map.Entry<String, String> entry : row.values().entrySet()) {
                    if (sameText(entry.getValue(), row.sourceDocumentNo())) sourceNoFields.add(entry.getKey());
                }
            }
        }
        if (sourceNoIndex.isEmpty()) return Map.of();
        Map<String, BitableRecord> result = new HashMap<>();
        for (BitableRecord record : records) {
            String sourceNo = sourceNo(record.fields(), sourceNoFields, sourceNoIndex);
            if (sourceNo != null) result.putIfAbsent(sourceNo, record);
        }
        return Map.copyOf(result);
    }

    private static String sourceNo(Map<String, Object> fields, Set<String> sourceNoFields,
                                   Map<String, String> sourceNoIndex) {
        for (String field : sourceNoFields) {
            String value = scalarText(fields.get(field));
            String matched = sourceNoIndex.get(normalizeText(value));
            if (matched != null) return matched;
        }
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String value = scalarText(entry.getValue());
            String matched = sourceNoIndex.get(normalizeText(value));
            if (matched != null) return matched;
        }
        return null;
    }

    private BitableRecord recordForRow(Map<String, Map<String, BitableRecord>> recordsBySheetSourceNo,
                                       StoredRawRow row) {
        Map<String, BitableRecord> bySourceNo = recordsBySheetSourceNo.get(normalize(row.sheetName()));
        if (bySourceNo == null) return null;
        BitableRecord record = bySourceNo.get(row.sourceDocumentNo());
        if (record != null) return record;
        String normalizedSourceNo = normalizeText(row.sourceDocumentNo());
        for (Map.Entry<String, BitableRecord> entry : bySourceNo.entrySet()) {
            if (normalizeText(entry.getKey()).equals(normalizedSourceNo)) return entry.getValue();
        }
        return null;
    }

    private RowAttachmentResult resolveRow(CallerIdentity caller, StoredRawRow row, BitableRecord record,
                                           String tableId, Map<String, String> fieldIdsByName,
                                           Map<String, DownloadedAttachment> downloadCache) {
        Map<String, List<String>> objectKeysByField = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>(row.values());
        int uploaded = 0;
        for (Map.Entry<String, List<String>> entry : row.attachmentRefs().entrySet()) {
            String fieldName = entry.getKey();
            List<String> sourceRefs = entry.getValue() == null ? List.of() : entry.getValue();
            if (sourceRefs.isEmpty()) continue;
            if (sourceRefs.stream().allMatch(value -> keyFactory.isGeneratedForTenant(
                    caller.tenantId().toString(), value))) {
                objectKeysByField.put(fieldName, List.copyOf(sourceRefs));
                values.put(fieldName, String.join("\n", sourceRefs));
                continue;
            }
            String fieldId = fieldIdForAttachmentField(fieldIdsByName, fieldName);
            if (!StringUtils.hasText(fieldId)) {
                AttachmentFieldMatch fallback = matchingAttachmentField(record.fields(), sourceRefs, fieldIdsByName);
                if (fallback == null) {
                    return RowAttachmentResult.failed(failure(row, "FEISHU_ATTACHMENT_FIELD_ID_NOT_FOUND",
                            "飞书Base字段列表里未找到附件字段ID：" + fieldName));
                }
                fieldId = fallback.fieldId();
            }
            List<AttachmentRef> bitableRefs = attachmentRefs(record.fields(), fieldName, fieldId);
            if (bitableRefs.isEmpty()) {
                AttachmentFieldMatch fallback = matchingAttachmentField(record.fields(), sourceRefs, fieldIdsByName);
                if (fallback == null) {
                    return RowAttachmentResult.failed(failure(row, "FEISHU_ATTACHMENT_FIELD_NOT_FOUND",
                            "飞书Base记录里未找到附件字段：" + fieldName));
                }
                fieldId = fallback.fieldId();
                bitableRefs = fallback.refs();
            }
            List<String> objectKeys = new ArrayList<>();
            for (int index = 0; index < sourceRefs.size(); index++) {
                String sourceRef = sourceRefs.get(index);
                if (keyFactory.isGeneratedForTenant(caller.tenantId().toString(), sourceRef)) {
                    objectKeys.add(sourceRef);
                    continue;
                }
                AttachmentRef attachment = matchAttachment(sourceRef, index, bitableRefs);
                if (attachment == null || !StringUtils.hasText(attachment.fileToken())) {
                    return RowAttachmentResult.failed(failure(row, "FEISHU_ATTACHMENT_TOKEN_NOT_FOUND",
                            "飞书Base记录里未找到附件 file_token：" + sourceRef));
                }
                String downloadFieldId = fieldId;
                DownloadedAttachment downloaded = downloadCache.computeIfAbsent(attachment.fileToken(),
                        token -> bitableClient.downloadAttachment(token,
                                firstNonBlank(attachment.fileName(), sourceRef),
                                tableId, record.recordId(), downloadFieldId));
                String fileName = firstNonBlank(downloaded.fileName(), attachment.fileName(), sourceRef,
                        attachment.fileToken());
                String contentType = firstNonBlank(downloaded.contentType(), attachment.contentType());
                String objectKey = keyFactory.generate(caller.tenantId().toString(), row.tableCode(),
                        row.sourceDocumentNo(), fieldName, downloaded.content(), fileName, contentType);
                storage.put(caller.tenantId().toString(), objectKey, fileName, contentType, downloaded.content());
                objectKeys.add(objectKey);
                uploaded++;
            }
            objectKeysByField.put(fieldName, List.copyOf(objectKeys));
            values.put(fieldName, String.join("\n", objectKeys));
        }
        StoredRawRow updated = new StoredRawRow(row.id(), row.batchId(), row.tableId(), row.tenantId(),
                row.sheetName(), row.tableCode(), row.domainCode(), row.objectType(), row.rowNumber(),
                row.sourceDocumentNo(), row.sourceCreatedAt(), row.projectionStatus(),
                Map.copyOf(values), Map.copyOf(objectKeysByField), row.deduplicationKey(),
                row.duplicateScope(), row.duplicateOfRawRowId());
        return RowAttachmentResult.resolved(updated, uploaded);
    }

    private static AttachmentFieldMatch matchingAttachmentField(Map<String, Object> fields,
                                                               List<String> sourceRefs,
                                                               Map<String, String> fieldIdsByName) {
        if (fields == null || fields.isEmpty() || sourceRefs == null || sourceRefs.isEmpty()) return null;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            List<AttachmentRef> refs = attachmentRefs(entry.getValue());
            if (refs.isEmpty() || refs.stream().noneMatch(ref -> matchesAnySourceRef(sourceRefs, ref))) {
                continue;
            }
            String fieldKey = entry.getKey();
            String fieldId = fieldIdsByName == null ? null : fieldIdsByName.get(normalize(fieldKey));
            if (!StringUtils.hasText(fieldId)) fieldId = fieldKey;
            return new AttachmentFieldMatch(fieldId, refs);
        }
        return null;
    }

    private static boolean matchesAnySourceRef(List<String> sourceRefs, AttachmentRef ref) {
        if (ref == null) return false;
        for (String sourceRef : sourceRefs) {
            if (!StringUtils.hasText(sourceRef)) continue;
            String normalized = normalizeText(sourceRef);
            String sourceNameStem = normalizeFileNameStem(sourceRef);
            if (normalized.equals(normalizeText(ref.fileName()))
                    || normalized.equals(normalizeText(ref.fileToken()))
                    || (!sourceNameStem.isEmpty()
                    && sourceNameStem.equals(normalizeFileNameStem(ref.fileName())))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, List<String>> unresolvedAttachmentRefs(UUID tenantId, StoredRawRow row) {
        if (row == null || row.attachmentRefs().isEmpty()) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : row.attachmentRefs().entrySet()) {
            List<String> values = entry.getValue() == null ? List.of() : entry.getValue().stream()
                    .filter(StringUtils::hasText)
                    .toList();
            boolean allResolved = values.stream()
                    .allMatch(value -> keyFactory != null
                            && keyFactory.isGeneratedForTenant(tenantId.toString(), value));
            if (!values.isEmpty() && !allResolved) result.put(entry.getKey(), values);
        }
        return Map.copyOf(result);
    }

    private static String fieldIdForAttachmentField(Map<String, String> fieldIdsByName, String fieldName) {
        if (fieldIdsByName == null || fieldIdsByName.isEmpty()) return null;
        for (String candidate : attachmentFieldCandidates(fieldName)) {
            String fieldId = fieldIdsByName.get(normalize(candidate));
            if (StringUtils.hasText(fieldId)) return fieldId;
        }
        Set<String> normalizedCandidates = normalizedAttachmentFieldCandidates(fieldName);
        for (Map.Entry<String, String> entry : fieldIdsByName.entrySet()) {
            String normalizedKey = normalize(entry.getKey());
            if (normalizedCandidates.contains(normalizedKey)
                    || normalizedCandidates.stream().anyMatch(candidate ->
                    normalizedKey.contains(candidate) || candidate.contains(normalizedKey))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static List<AttachmentRef> attachmentRefs(Map<String, Object> fields, String fieldName,
                                                      String fieldId) {
        for (String candidate : attachmentFieldCandidates(fieldName)) {
            Object fieldValue = fieldValue(fields, candidate);
            List<AttachmentRef> refs = attachmentRefs(fieldValue);
            if (!refs.isEmpty()) return refs;
        }
        Object fieldValue = fieldValue(fields, fieldId);
        List<AttachmentRef> refs = attachmentRefs(fieldValue);
        if (!refs.isEmpty()) return refs;
        Set<String> normalizedCandidates = normalizedAttachmentFieldCandidates(fieldName);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String normalizedKey = normalize(entry.getKey());
            if (normalizedCandidates.contains(normalizedKey) || normalizedKey.equals(normalize(fieldId))) {
                return attachmentRefs(entry.getValue());
            }
        }
        return List.of();
    }

    private static List<String> attachmentFieldCandidates(String fieldName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(fieldName)) candidates.add(fieldName);
        String normalized = normalize(fieldName);
        if (normalized.contains("凭证")
                || normalized.contains("回款")
                || normalized.contains("付款")
                || normalized.contains("支付")
                || normalized.contains("收款")) {
            candidates.addAll(VOUCHER_ATTACHMENT_FIELD_ALIASES);
        }
        return List.copyOf(candidates);
    }

    private static Set<String> normalizedAttachmentFieldCandidates(String fieldName) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String candidate : attachmentFieldCandidates(fieldName)) {
            String normalized = normalize(candidate);
            if (StringUtils.hasText(normalized)) candidates.add(normalized);
        }
        return candidates;
    }

    private static Object fieldValue(Map<String, Object> fields, String fieldName) {
        if (fields == null || fieldName == null) return null;
        Object value = fields.get(fieldName);
        if (value != null) return value;
        String normalized = normalize(fieldName);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (normalize(entry.getKey()).equals(normalized)) return entry.getValue();
        }
        return null;
    }

    private static List<AttachmentRef> attachmentRefs(Object value) {
        if (value == null) return List.of();
        List<AttachmentRef> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) result.addAll(attachmentRefs(item));
            return List.copyOf(result);
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : WRAPPED_ATTACHMENT_KEYS) {
                Object nested = map.get(key);
                if (nested == null) continue;
                result.addAll(attachmentRefs(nested));
            }
            if (!result.isEmpty()) return List.copyOf(result);
            String token = firstMapText(map, TOKEN_KEYS);
            String name = firstMapText(map, NAME_KEYS);
            String contentType = firstMapText(map, TYPE_KEYS);
            if (StringUtils.hasText(token) || StringUtils.hasText(name)) {
                result.add(new AttachmentRef(token, name, contentType));
            }
            return List.copyOf(result);
        }
        String text = scalarText(value);
        return StringUtils.hasText(text) ? List.of(new AttachmentRef(text, text, null)) : List.of();
    }

    private static AttachmentRef matchAttachment(String sourceRef, int index, List<AttachmentRef> refs) {
        String normalized = normalizeText(sourceRef);
        String sourceNameStem = normalizeFileNameStem(sourceRef);
        for (AttachmentRef ref : refs) {
            if (normalized.equals(normalizeText(ref.fileName()))
                    || normalized.equals(normalizeText(ref.fileToken()))
                    || (!sourceNameStem.isEmpty()
                    && sourceNameStem.equals(normalizeFileNameStem(ref.fileName())))) {
                return ref;
            }
        }
        if (refs.size() == 1) return refs.get(0);
        return index >= 0 && index < refs.size() ? refs.get(index) : null;
    }

    private static Map<String, List<StoredRawRow>> rowsBySheet(List<StoredRawRow> rows) {
        Map<String, List<StoredRawRow>> rowsBySheet = new LinkedHashMap<>();
        for (StoredRawRow row : rows == null ? List.<StoredRawRow>of() : rows) {
            rowsBySheet.computeIfAbsent(normalize(row.sheetName()), ignored -> new ArrayList<>()).add(row);
        }
        return rowsBySheet;
    }

    private static String normalizeFileNameStem(String value) {
        if (!StringUtils.hasText(value)) return "";
        String text = value.strip();
        int separator = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (separator >= 0 && separator + 1 < text.length()) text = text.substring(separator + 1);
        int query = firstPositiveIndex(text.indexOf('?'), text.indexOf('#'));
        if (query >= 0) text = text.substring(0, query);
        int dot = text.lastIndexOf('.');
        if (dot > 0) text = text.substring(0, dot);
        return normalizeText(text);
    }

    private static int firstPositiveIndex(int left, int right) {
        if (left < 0) return right;
        if (right < 0) return left;
        return Math.min(left, right);
    }

    private static String firstMapText(Map<?, ?> map, Set<String> names) {
        for (String name : names) {
            Object value = map.get(name);
            String text = scalarText(value);
            if (StringUtils.hasText(text)) return text;
        }
        String normalizedNames = names.stream().map(FeishuAttachmentImportService::normalize)
                .reduce("", (left, right) -> left + "|" + right);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null
                    && normalizedNames.contains(normalize(String.valueOf(entry.getKey())))) {
                String text = scalarText(entry.getValue());
                if (StringUtils.hasText(text)) return text;
            }
        }
        return null;
    }

    private static String scalarText(Object value) {
        if (value == null) return null;
        if (value instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object item : iterable) {
                String text = scalarText(item);
                if (StringUtils.hasText(text)) parts.add(text);
            }
            return parts.size() == 1 ? parts.get(0) : null;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("text", "name", "value", "title")) {
                String text = scalarText(map.get(key));
                if (StringUtils.hasText(text)) return text;
            }
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static AttachmentResolution failAll(List<StoredRawRow> rows, List<StoredRawRow> failedRows,
                                                String errorCode, String message) {
        Map<UUID, AttachmentFailure> failures = new LinkedHashMap<>();
        for (StoredRawRow row : failedRows) {
            failures.put(row.id(), failure(row, errorCode, message));
        }
        return new AttachmentResolution(rows, Map.copyOf(failures), 0, failures.size());
    }

    private static AttachmentFailure failure(StoredRawRow row, String errorCode, String message) {
        return new AttachmentFailure(row.id(), row.domainCode(), row.objectType(), errorCode, message);
    }

    private static String safeErrorCode(String errorCode, String fallback) {
        return StringUtils.hasText(errorCode) ? errorCode.strip() : fallback;
    }

    private static String failureMessage(String prefix, Throwable exception) {
        String detail = safeFailureDetail(exception);
        return StringUtils.hasText(detail) ? prefix + "：" + detail : prefix;
    }

    private static String safeFailureDetail(Throwable exception) {
        if (exception == null) return null;
        String message = exception.getMessage();
        String detail = StringUtils.hasText(message)
                ? message.strip()
                : exception.getClass().getSimpleName();
        detail = detail.replace('\r', ' ').replace('\n', ' ');
        return detail.length() <= 300 ? detail : detail.substring(0, 300);
    }

    private static void logRowFailure(CallerIdentity caller, StoredBatch batch, StoredRawRow row,
                                      String errorCode, RuntimeException exception) {
        log.warn("飞书附件行处理失败 tenantId={} batchId={} rawRowId={} sheetName={} rowNumber={} "
                        + "sourceDocumentNo={} errorCode={} errorType={} message={}",
                caller.tenantId(), batch.id(), row.id(), row.sheetName(), row.rowNumber(),
                row.sourceDocumentNo(), errorCode, exception.getClass().getSimpleName(),
                safeFailureDetail(exception), exception);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isLetterOrDigit(character)
                    || Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN) {
                builder.append(Character.toUpperCase(character));
            }
        }
        return builder.toString().toUpperCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        return value.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static boolean sameText(String left, String right) {
        return normalizeText(left).equals(normalizeText(right));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.strip();
        }
        return null;
    }

    record AttachmentResolution(List<StoredRawRow> rows,
                                Map<UUID, AttachmentFailure> failures,
                                int uploadedAttachmentCount,
                                int failedAttachmentRows) {
        AttachmentResolution {
            rows = rows == null ? List.of() : List.copyOf(rows);
            failures = failures == null ? Map.of() : Map.copyOf(failures);
        }

        static AttachmentResolution unchanged(List<StoredRawRow> rows) {
            return new AttachmentResolution(rows, Map.of(), 0, 0);
        }
    }

    record AttachmentFailure(UUID rawRowId, String targetDomain, String targetObjectType,
                             String errorCode, String message) {
    }

    private record RowAttachmentResult(StoredRawRow row, int uploaded, AttachmentFailure failure) {
        static RowAttachmentResult resolved(StoredRawRow row, int uploaded) {
            return new RowAttachmentResult(row, uploaded, null);
        }

        static RowAttachmentResult failed(AttachmentFailure failure) {
            return new RowAttachmentResult(null, 0, failure);
        }
    }

    private record AttachmentRef(String fileToken, String fileName, String contentType) {
    }

    private record AttachmentFieldMatch(String fieldId, List<AttachmentRef> refs) {
        private AttachmentFieldMatch {
            refs = refs == null ? List.of() : List.copyOf(refs);
        }
    }

    private record BaseLocator(String appToken, String tableId, String viewId) {
        static Optional<BaseLocator> parse(String sourceUrl) {
            if (!StringUtils.hasText(sourceUrl)) return Optional.empty();
            String appToken = null;
            String tableId = null;
            String viewId = null;
            try {
                URI uri = new URI(sourceUrl.strip());
                Matcher matcher = BASE_TOKEN_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath());
                if (matcher.find()) appToken = matcher.group(1);
                Map<String, String> query = queryParams(uri.getRawQuery());
                tableId = query.get("table");
                viewId = query.get("view");
            } catch (URISyntaxException ignored) {
                Matcher matcher = BASE_TOKEN_PATTERN.matcher(sourceUrl);
                if (matcher.find()) appToken = matcher.group(1);
            }
            if (!StringUtils.hasText(appToken)) return Optional.empty();
            return Optional.of(new BaseLocator(appToken, tableId, viewId));
        }

        private static Map<String, String> queryParams(String rawQuery) {
            if (!StringUtils.hasText(rawQuery)) return Map.of();
            Map<String, String> result = new HashMap<>();
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0 || eq == pair.length() - 1) continue;
                result.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            return Map.copyOf(result);
        }
    }
}
