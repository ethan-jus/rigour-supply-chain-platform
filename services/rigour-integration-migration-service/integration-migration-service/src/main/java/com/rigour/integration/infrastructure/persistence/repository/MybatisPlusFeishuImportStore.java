package com.rigour.integration.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.integration.application.port.out.FeishuImportStore;
import com.rigour.integration.application.port.out.FeishuImportStore.ExistingDeduplicationRow;
import com.rigour.integration.application.port.out.FeishuImportStore.ImportTemplate;
import com.rigour.integration.application.port.out.FeishuImportStore.ImportTemplateDependency;
import com.rigour.integration.application.port.out.FeishuImportStore.ProjectionIssueSummary;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationFeishuImportBatchEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationFeishuImportIssueEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationFeishuImportRawRowEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationFeishuImportTableEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationFeishuImportTemplateDependencyEntity;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationFeishuImportTemplateEntity;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportBatchMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportIssueMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportRawRowMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportTableMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportTemplateDependencyMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportTemplateMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 飞书导入批次 MyBatis-Plus 持久化适配器。 */
public final class MybatisPlusFeishuImportStore implements FeishuImportStore {
    private static final int PREFLIGHT_BATCH_SIZE = 200;
    private final IntegrationFeishuImportBatchMapper batchMapper;
    private final IntegrationFeishuImportTableMapper tableMapper;
    private final IntegrationFeishuImportIssueMapper issueMapper;
    private final IntegrationFeishuImportRawRowMapper rawRowMapper;
    private final IntegrationFeishuImportTemplateMapper templateMapper;
    private final IntegrationFeishuImportTemplateDependencyMapper templateDependencyMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public MybatisPlusFeishuImportStore(
            IntegrationFeishuImportBatchMapper batchMapper,
            IntegrationFeishuImportTableMapper tableMapper,
            IntegrationFeishuImportIssueMapper issueMapper,
            IntegrationFeishuImportRawRowMapper rawRowMapper,
            IntegrationFeishuImportTemplateMapper templateMapper,
            IntegrationFeishuImportTemplateDependencyMapper templateDependencyMapper,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.tableMapper = tableMapper;
        this.issueMapper = issueMapper;
        this.rawRowMapper = rawRowMapper;
        this.templateMapper = templateMapper;
        this.templateDependencyMapper = templateDependencyMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public void savePreflight(PreflightBatch batch) {
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = dateTime(batch.createdAt());
            IntegrationFeishuImportBatchEntity row = new IntegrationFeishuImportBatchEntity();
            row.id = bin(batch.id());
            row.tenantId = bin(batch.tenantId());
            row.sourceSystem = batch.sourceSystem();
            row.sourceUrl = truncate(batch.sourceUrl(), 2000);
            row.originalFileName = truncate(batch.originalFileName(), 255);
            row.fileSizeBytes = batch.fileSizeBytes();
            row.fileSha256 = batch.fileSha256();
            row.status = batch.status();
            row.totalSheets = batch.totalSheets();
            row.totalRows = batch.totalRows();
            row.duplicateRows = batch.duplicateRows();
            row.attachmentReferenceCount = batch.attachmentReferenceCount();
            row.createdAt = now;
            row.createdBy = bin(batch.createdBy());
            row.updatedAt = now;
            row.updatedBy = bin(batch.createdBy());
            row.version = 0L;
            batchMapper.insert(row);

            batchInsertTables(batch, now);
            batchInsertIssues(batch, now);
            batchInsertRawRows(batch, now);
        });
    }

    private void batchInsertTables(PreflightBatch batch, LocalDateTime now) {
        if (batch.tables().isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                        INSERT INTO integration_feishu_import_table
                        (id, batch_id, tenant_id, sheet_name, table_code, domain_code, object_type,
                         mapping_status, header_row_number, row_count, duplicate_rows, column_count,
                         attachment_reference_count, headers_json, attachment_fields_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                batch.tables(), PREFLIGHT_BATCH_SIZE, (ps, table) -> {
                    ps.setBytes(1, bin(table.id()));
                    ps.setBytes(2, bin(batch.id()));
                    ps.setBytes(3, bin(batch.tenantId()));
                    ps.setString(4, truncate(table.sheetName(), 255));
                    ps.setString(5, truncate(table.tableCode(), 64));
                    ps.setString(6, truncate(table.domainCode(), 32));
                    ps.setString(7, truncate(table.objectType(), 64));
                    ps.setString(8, table.mappingStatus());
                    ps.setObject(9, table.headerRowNumber());
                    ps.setObject(10, table.rowCount());
                    ps.setObject(11, table.duplicateRows());
                    ps.setObject(12, table.columnCount());
                    ps.setObject(13, table.attachmentReferenceCount());
                    ps.setString(14, json(table.headers()));
                    ps.setString(15, json(table.attachmentFields()));
                    ps.setObject(16, now);
                });
    }

    private void batchInsertIssues(PreflightBatch batch, LocalDateTime now) {
        if (batch.issues().isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                        INSERT INTO integration_feishu_import_issue
                        (id, batch_id, tenant_id, severity, issue_type, table_name,
                         source_row_number, field_name, message, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                batch.issues(), PREFLIGHT_BATCH_SIZE, (ps, issue) -> {
                    ps.setBytes(1, bin(issue.id()));
                    ps.setBytes(2, bin(batch.id()));
                    ps.setBytes(3, bin(batch.tenantId()));
                    ps.setString(4, truncate(issue.severity(), 16));
                    ps.setString(5, truncate(issue.issueType(), 64));
                    ps.setString(6, truncate(issue.tableName(), 255));
                    ps.setObject(7, issue.rowNumber());
                    ps.setString(8, truncate(issue.fieldName(), 255));
                    ps.setString(9, truncate(issue.message(), 1000));
                    ps.setObject(10, now);
                });
    }

    private void batchInsertRawRows(PreflightBatch batch, LocalDateTime now) {
        if (batch.rawRows().isEmpty()) return;
        for (int offset = 0; offset < batch.rawRows().size(); offset += PREFLIGHT_BATCH_SIZE) {
            List<PreflightRawRow> chunk = batch.rawRows().subList(offset,
                    Math.min(offset + PREFLIGHT_BATCH_SIZE, batch.rawRows().size()));
            insertRawRowChunk(batch, now, chunk);
        }
    }

    private void insertRawRowChunk(PreflightBatch batch, LocalDateTime now,
                                   List<PreflightRawRow> rawRows) {
        String placeholders = String.join(", ", Collections.nCopies(rawRows.size(),
                "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
        String sql = """
                        INSERT INTO integration_feishu_import_raw_row
                        (id, batch_id, table_id, tenant_id, sheet_name, table_code, domain_code,
                         object_type, source_row_number, source_document_no, source_created_at,
                         raw_row_hash, deduplication_key, duplicate_scope, duplicate_of_raw_row_id,
                         row_json, attachment_refs_json, import_status,
                         projection_status, created_at, updated_at, updated_by, version)
                        VALUES
                        """;
        List<Object> args = new ArrayList<>(rawRows.size() * 23);
        for (PreflightRawRow rawRow : rawRows) {
            args.add(bin(rawRow.id()));
            args.add(bin(batch.id()));
            args.add(bin(rawRow.tableId()));
            args.add(bin(batch.tenantId()));
            args.add(truncate(rawRow.sheetName(), 255));
            args.add(truncate(rawRow.tableCode(), 64));
            args.add(truncate(rawRow.domainCode(), 32));
            args.add(truncate(rawRow.objectType(), 64));
            args.add(rawRow.rowNumber());
            args.add(truncate(rawRow.sourceDocumentNo(), 128));
            args.add(dateTime(rawRow.sourceCreatedAt()));
            args.add(rawRow.rowHash());
            args.add(truncate(rawRow.deduplicationKey(), 255));
            args.add(truncate(rawRow.duplicateScope(), 32));
            args.add(bin(rawRow.duplicateOfRawRowId()));
            args.add(json(rawRow.values()));
            args.add(json(rawRow.attachmentRefs()));
            args.add(rawRow.importStatus());
            args.add(rawRow.projectionStatus());
            args.add(now);
            args.add(now);
            args.add(bin(batch.createdBy()));
            args.add(0L);
        }
        jdbcTemplate.update(sql + placeholders, args.toArray());
    }

    @Override
    public Optional<StoredBatch> batch(UUID tenantId, UUID batchId) {
        IntegrationFeishuImportBatchEntity row = batchMapper.selectOne(new QueryWrapper<IntegrationFeishuImportBatchEntity>()
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(batchId))
                .last("LIMIT 1"));
        if (row == null) return Optional.empty();
        return Optional.of(storedBatch(row));
    }

    @Override
    public List<StoredBatch> recentBatches(UUID tenantId, int limit) {
        int pageSize = Math.max(1, Math.min(limit, 100));
        return batchMapper.selectList(new QueryWrapper<IntegrationFeishuImportBatchEntity>()
                        .eq("tenant_id", bin(tenantId))
                        .orderByDesc("created_at")
                        .last("LIMIT " + pageSize))
                .stream()
                .map(this::storedBatch)
                .toList();
    }

    @Override
    public List<StoredRawRow> rawRowsForRun(UUID tenantId, UUID batchId, int limit, boolean replayProjected) {
        int pageSize = Math.max(1, Math.min(limit, 100_000));
        List<String> statuses = replayProjected
                ? List.of("PENDING", "WAITING_MAPPING", "FAILED", "PROJECTED", "SKIPPED")
                : List.of("PENDING", "WAITING_MAPPING", "FAILED");
        return rawRowMapper.selectList(new QueryWrapper<IntegrationFeishuImportRawRowEntity>()
                        .eq("tenant_id", bin(tenantId))
                        .eq("batch_id", bin(batchId))
                        .eq("import_status", "IMPORTED")
                        .in("projection_status", statuses)
                        .orderByAsc("sheet_name", "source_row_number")
                        .last("LIMIT " + pageSize))
                .stream()
                .map(this::storedRawRow)
                .toList();
    }

    @Override
    public List<StoredRawRow> rawRowsForBatch(UUID tenantId, UUID batchId, int limit) {
        int pageSize = Math.max(1, Math.min(limit, 100_000));
        return rawRowMapper.selectList(new QueryWrapper<IntegrationFeishuImportRawRowEntity>()
                        .eq("tenant_id", bin(tenantId))
                        .eq("batch_id", bin(batchId))
                        .in("import_status", List.of("IMPORTED", "DROPPED"))
                        .orderByAsc("sheet_name", "source_row_number")
                        .last("LIMIT " + pageSize))
                .stream()
                .map(this::storedRawRow)
                .toList();
    }

    @Override
    public Map<String, Long> rawRowProjectionStatusCounts(UUID tenantId, UUID batchId) {
        Map<String, Long> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT projection_status, COUNT(*) AS row_count
                          FROM integration_feishu_import_raw_row
                         WHERE tenant_id = ?
                           AND batch_id = ?
                           AND import_status IN ('IMPORTED', 'DROPPED')
                         GROUP BY projection_status
                        """,
                bin(tenantId), bin(batchId));
        for (Map<String, Object> row : rows) {
            Object status = row.get("projection_status");
            Object count = row.get("row_count");
            if (status != null && count instanceof Number number) {
                result.put(String.valueOf(status), number.longValue());
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public List<ProjectionIssueSummary> rawRowProjectionIssueSummaries(UUID tenantId, UUID batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT projection_status,
                               COALESCE(NULLIF(target_domain, ''), domain_code) AS target_domain,
                               COALESCE(NULLIF(target_object_type, ''), object_type) AS target_object_type,
                               COALESCE(NULLIF(error_code, ''), 'FEISHU_PROJECTION_PENDING') AS error_code,
                               COALESCE(NULLIF(error_message, ''), '等待内部领域投影处理') AS error_message,
                               COUNT(*) AS row_count
                          FROM integration_feishu_import_raw_row
                         WHERE tenant_id = ?
                           AND batch_id = ?
                           AND import_status = 'IMPORTED'
                           AND projection_status NOT IN ('PROJECTED', 'SKIPPED')
                         GROUP BY projection_status,
                                  COALESCE(NULLIF(target_domain, ''), domain_code),
                                  COALESCE(NULLIF(target_object_type, ''), object_type),
                                  COALESCE(NULLIF(error_code, ''), 'FEISHU_PROJECTION_PENDING'),
                                  COALESCE(NULLIF(error_message, ''), '等待内部领域投影处理')
                         ORDER BY row_count DESC, projection_status ASC
                        """,
                bin(tenantId), bin(batchId));
        return rows.stream()
                .map(row -> new ProjectionIssueSummary(
                        text(row.get("projection_status")),
                        text(row.get("target_domain")),
                        text(row.get("target_object_type")),
                        text(row.get("error_code")),
                        text(row.get("error_message")),
                        number(row.get("row_count"))))
                .toList();
    }

    @Override
    public List<ImportTemplate> importTemplates(UUID tenantId) {
        List<IntegrationFeishuImportTemplateDependencyEntity> dependencyRows =
                templateDependencyMapper.selectList(new QueryWrapper<IntegrationFeishuImportTemplateDependencyEntity>()
                        .eq("source_system", "FEISHU")
                        .eq("enabled_flag", 1)
                        .and(query -> query.isNull("tenant_id").or().eq("tenant_id", bin(tenantId)))
                        .orderByAsc("template_code", "depends_on_template_code"));
        Map<String, List<ImportTemplateDependency>> dependencies = new LinkedHashMap<>();
        for (IntegrationFeishuImportTemplateDependencyEntity row : dependencyRows) {
            dependencies.computeIfAbsent(row.templateCode, ignored -> new ArrayList<>())
                    .add(new ImportTemplateDependency(row.dependsOnTemplateCode, row.relationKind,
                            stringList(row.sourceReferenceFieldsJson),
                            stringList(row.targetReferenceFieldsJson),
                            Boolean.TRUE.equals(row.requiredFlag)));
        }
        return templateMapper.selectList(new QueryWrapper<IntegrationFeishuImportTemplateEntity>()
                        .eq("source_system", "FEISHU")
                        .eq("enabled_flag", 1)
                        .and(query -> query.isNull("tenant_id").or().eq("tenant_id", bin(tenantId)))
                        .orderByAsc("tenant_id", "template_code"))
                .stream()
                .map(row -> new ImportTemplate(row.templateCode, row.templateName, row.sourceSystem,
                        row.domainCode, row.objectType, stringList(row.aliasesJson),
                        stringList(row.requiredHeadersJson),
                        stringList(row.sourceDocumentFieldsJson),
                        stringList(row.sourceCreatedFieldsJson),
                        row.deduplicationStrategy, stringList(row.deduplicationFieldsJson),
                        Boolean.TRUE.equals(row.readyByDefaultFlag),
                        dependencies.getOrDefault(row.templateCode, List.of())))
                .toList();
    }

    @Override
    public Map<String, ExistingDeduplicationRow> existingDeduplicationRows(UUID tenantId, Iterable<String> keys) {
        List<String> distinctKeys = StreamSupport.stream(keys.spliterator(), false)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (distinctKeys.isEmpty()) return Map.of();
        Map<String, ExistingDeduplicationRow> result = new LinkedHashMap<>();
        for (int offset = 0; offset < distinctKeys.size(); offset += PREFLIGHT_BATCH_SIZE) {
            List<String> chunk = distinctKeys.subList(offset, Math.min(offset + PREFLIGHT_BATCH_SIZE,
                    distinctKeys.size()));
            String placeholders = String.join(", ", Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>(chunk.size() + 1);
            args.add(bin(tenantId));
            args.addAll(chunk);
            jdbcTemplate.query("""
                            SELECT deduplication_key, id, raw_row_hash, projection_status,
                                   target_domain, target_object_type, target_id
                              FROM integration_feishu_import_raw_row
	                             WHERE tenant_id = ?
	                               AND import_status = 'IMPORTED'
	                               AND projection_status = 'PROJECTED'
	                               AND deduplication_key IN (
	                            """ + placeholders + """
                               )
                             ORDER BY created_at DESC
                            """,
                    args.toArray(),
                    rs -> {
                        String key = rs.getString("deduplication_key");
                        result.putIfAbsent(key, new ExistingDeduplicationRow(key,
                                decode(rs.getBytes("id")),
                                rs.getString("raw_row_hash"),
                                rs.getString("projection_status"),
                                rs.getString("target_domain"),
                                rs.getString("target_object_type"),
                                rs.getString("target_id")));
                    });
        }
        return Map.copyOf(result);
    }

    @Override
    public void updateRawRowProjection(RowProjectionUpdate update) {
        rawRowMapper.update(null, new UpdateWrapper<IntegrationFeishuImportRawRowEntity>()
                .eq("tenant_id", bin(update.tenantId()))
                .eq("id", bin(update.rawRowId()))
                .set("projection_status", truncate(update.projectionStatus(), 32))
                .set("target_domain", truncate(update.targetDomain(), 32))
                .set("target_object_type", truncate(update.targetObjectType(), 64))
                .set("target_id", truncate(update.targetId(), 64))
                .set("error_code", truncate(update.errorCode(), 64))
                .set("error_message", truncate(update.errorMessage(), 1000))
                .set("updated_by", bin(update.updatedBy()))
                .set("updated_at", dateTime(update.updatedAt()))
                .setSql("version = version + 1"));
    }

    @Override
    public void updateRawRowAttachments(RowAttachmentUpdate update) {
        rawRowMapper.update(null, new UpdateWrapper<IntegrationFeishuImportRawRowEntity>()
                .eq("tenant_id", bin(update.tenantId()))
                .eq("id", bin(update.rawRowId()))
                .set("row_json", json(update.values()))
                .set("attachment_refs_json", json(update.attachmentRefs()))
                .set("updated_by", bin(update.updatedBy()))
                .set("updated_at", dateTime(update.updatedAt()))
                .setSql("version = version + 1"));
    }

    @Override
    public void updateRawRowAttachments(List<RowAttachmentUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;
        for (int offset = 0; offset < updates.size(); offset += PREFLIGHT_BATCH_SIZE) {
            List<RowAttachmentUpdate> chunk = updates.subList(offset,
                    Math.min(offset + PREFLIGHT_BATCH_SIZE, updates.size()));
            updateRawRowAttachmentChunk(chunk);
        }
    }

    private void updateRawRowAttachmentChunk(List<RowAttachmentUpdate> updates) {
        UUID tenantId = updates.get(0).tenantId();
        List<Object> args = new ArrayList<>(updates.size() * 6 + 3);
        StringBuilder sql = new StringBuilder("""
                UPDATE integration_feishu_import_raw_row
                SET updated_by = ?, updated_at = ?, version = version + 1,
                """);
        args.add(bin(updates.get(0).updatedBy()));
        args.add(dateTime(updates.get(0).updatedAt()));
        appendAttachmentCase(sql, args, "row_json", updates, update -> json(update.values()));
        sql.append(", ");
        appendAttachmentCase(sql, args, "attachment_refs_json", updates, update -> json(update.attachmentRefs()));
        sql.append(" WHERE tenant_id = ? AND id IN (");
        args.add(bin(tenantId));
        sql.append(String.join(", ", Collections.nCopies(updates.size(), "?")));
        sql.append(')');
        for (RowAttachmentUpdate update : updates) {
            args.add(bin(update.rawRowId()));
        }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    @Override
    public void updateRawRowProjections(List<RowProjectionUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;
        for (int offset = 0; offset < updates.size(); offset += PREFLIGHT_BATCH_SIZE) {
            List<RowProjectionUpdate> chunk = updates.subList(offset,
                    Math.min(offset + PREFLIGHT_BATCH_SIZE, updates.size()));
            updateRawRowProjectionChunk(chunk);
        }
    }

    private void updateRawRowProjectionChunk(List<RowProjectionUpdate> updates) {
        UUID tenantId = updates.get(0).tenantId();
        List<Object> args = new ArrayList<>(updates.size() * 14 + 3);
        StringBuilder sql = new StringBuilder("""
                UPDATE integration_feishu_import_raw_row
                SET updated_by = ?, updated_at = ?, version = version + 1,
                """);
        args.add(bin(updates.get(0).updatedBy()));
        args.add(dateTime(updates.get(0).updatedAt()));
        appendProjectionCase(sql, args, "projection_status", updates,
                update -> truncate(update.projectionStatus(), 32));
        sql.append(", ");
        appendProjectionCase(sql, args, "target_domain", updates,
                update -> truncate(update.targetDomain(), 32));
        sql.append(", ");
        appendProjectionCase(sql, args, "target_object_type", updates,
                update -> truncate(update.targetObjectType(), 64));
        sql.append(", ");
        appendProjectionCase(sql, args, "target_id", updates,
                update -> truncate(update.targetId(), 64));
        sql.append(", ");
        appendProjectionCase(sql, args, "error_code", updates,
                update -> truncate(update.errorCode(), 64));
        sql.append(", ");
        appendProjectionCase(sql, args, "error_message", updates,
                update -> truncate(update.errorMessage(), 1000));
        sql.append(" WHERE tenant_id = ? AND id IN (");
        args.add(bin(tenantId));
        sql.append(String.join(", ", Collections.nCopies(updates.size(), "?")));
        sql.append(')');
        for (RowProjectionUpdate update : updates) {
            args.add(bin(update.rawRowId()));
        }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    private static void appendProjectionCase(StringBuilder sql, List<Object> args, String column,
                                             List<RowProjectionUpdate> updates,
                                             Function<RowProjectionUpdate, Object> valueMapper) {
        sql.append(column).append(" = CASE id ");
        for (RowProjectionUpdate update : updates) {
            sql.append("WHEN ? THEN ? ");
            args.add(bin(update.rawRowId()));
            args.add(valueMapper.apply(update));
        }
        sql.append("ELSE ").append(column).append(" END");
    }

    private void appendAttachmentCase(StringBuilder sql, List<Object> args, String column,
                                      List<RowAttachmentUpdate> updates,
                                      Function<RowAttachmentUpdate, Object> valueMapper) {
        sql.append(column).append(" = CASE id ");
        for (RowAttachmentUpdate update : updates) {
            sql.append("WHEN ? THEN ? ");
            args.add(bin(update.rawRowId()));
            args.add(valueMapper.apply(update));
        }
        sql.append("ELSE ").append(column).append(" END");
    }

    @Override
    public void updateBatchStatus(UUID tenantId, UUID batchId, String status, UUID updatedBy,
                                  Instant updatedAt) {
        batchMapper.update(null, new UpdateWrapper<IntegrationFeishuImportBatchEntity>()
                .eq("tenant_id", bin(tenantId))
                .eq("id", bin(batchId))
                .set("status", truncate(status, 32))
                .set("updated_by", bin(updatedBy))
                .set("updated_at", dateTime(updatedAt))
                .setSql("version = version + 1"));
    }

    private StoredRawRow storedRawRow(IntegrationFeishuImportRawRowEntity row) {
        return new StoredRawRow(
                decode(row.id),
                decode(row.batchId),
                decode(row.tableId),
                decode(row.tenantId),
                row.sheetName,
                row.tableCode,
                row.domainCode,
                row.objectType,
                row.rowNumber == null ? 0 : row.rowNumber,
                row.sourceDocumentNo,
                instant(row.sourceCreatedAt),
                row.projectionStatus,
                stringMap(row.rowJson),
                attachmentMap(row.attachmentRefsJson),
                row.deduplicationKey,
                row.duplicateScope,
                decode(row.duplicateOfRawRowId),
                row.targetDomain,
                row.targetObjectType,
                row.targetId,
                row.errorCode,
                row.errorMessage);
    }

    private StoredBatch storedBatch(IntegrationFeishuImportBatchEntity row) {
        return new StoredBatch(decode(row.id), decode(row.tenantId), row.sourceSystem,
                row.status, row.originalFileName, row.fileSha256,
                row.sourceUrl, row.fileSizeBytes == null ? 0L : row.fileSizeBytes,
                row.totalSheets == null ? 0 : row.totalSheets,
                row.totalRows == null ? 0L : row.totalRows,
                row.duplicateRows == null ? 0L : row.duplicateRows,
                row.attachmentReferenceCount == null ? 0L : row.attachmentReferenceCount,
                instant(row.createdAt), instant(row.updatedAt));
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (RuntimeException exception) {
            return objectMapper.writeValueAsString(Map.of("error", "JSON_SERIALIZE_FAILED"));
        }
    }

    private String json(Map<?, ?> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (RuntimeException exception) {
            return objectMapper.writeValueAsString(Map.of("error", "JSON_SERIALIZE_FAILED"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(value, Map.class);
            if (!(parsed instanceof Map<?, ?> map)) return Map.of();
            return map.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> String.valueOf(entry.getKey()),
                            entry -> String.valueOf(entry.getValue()),
                            (left, right) -> left));
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> attachmentMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(value, Map.class);
            if (!(parsed instanceof Map<?, ?> map)) return Map.of();
            return map.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() instanceof List<?>)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> String.valueOf(entry.getKey()),
                            entry -> ((List<?>) entry.getValue()).stream()
                                    .filter(java.util.Objects::nonNull)
                                    .map(String::valueOf)
                                    .toList(),
                            (left, right) -> left));
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            Object parsed = objectMapper.readValue(value, List.class);
            if (!(parsed instanceof List<?> list)) return List.of();
            return list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static byte[] bin(UUID value) {
        return IntegrationUuidCodec.encode(value);
    }

    private static UUID decode(byte[] value) {
        return IntegrationUuidCodec.decode(value);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static LocalDateTime dateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').strip();
        if (oneLine.isEmpty()) return null;
        return oneLine.length() > max ? oneLine.substring(0, max) : oneLine;
    }
}
