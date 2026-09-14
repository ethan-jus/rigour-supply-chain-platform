package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.analytics.infrastructure.persistence.mapper.BiReconciliationReviewMapper;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BiReconciliationOnlineCaptureRepositoryTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private final BiReconciliationReviewMapper mapper = mock(BiReconciliationReviewMapper.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final MybatisBiReconciliationReviewRepository repository = new MybatisBiReconciliationReviewRepository(mapper, json);

    @Test void sourceSelectPermissionFailureHasStableActionableErrorWithoutSqlOrAccountDetails() {
        var denied = new SQLException("SELECT command denied to user 'private_reader' for table 'integration_feishu_online_capture'", "42000", 1142);
        var wrapped = new BadSqlGrammarException("capture", "SELECT payload_json FROM rigour_integration.integration_feishu_online_capture", denied);
        when(mapper.onlineCapture("tenant", ID, MybatisBiReconciliationReviewRepository.MAX_ONLINE_PAYLOAD_BYTES)).thenThrow(wrapped);
        var failure = catchThrowableOfType(() -> repository.onlineCapture("tenant", ID), BusinessException.class);
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(failure.getErrorCode().getHttpStatus().value()).isEqualTo(503);
        assertThat(failure.getDetails()).singleElement().satisfies(detail -> {
            assertThat(detail.reason()).isEqualTo("BI_ONLINE_SOURCE_READ_NOT_PROVISIONED");
            assertThat(detail.field()).isNull();
        });
        assertThat(failure.getMessage()).contains("只读权限", "管理员", "本次采集", "无需重新导入");
        assertThat(failure.getCause()).isNull();
        String body = json.writeValueAsString(ApiResponse.error(failure.getErrorCode().getCode(), failure.getMessage(), failure.getDetails()));
        assertThat(body).doesNotContain("SELECT", "payload_json", "private_reader", "rigour_integration", "integration_feishu_online_capture", "42000", "1142");
        verify(mapper).onlineCapture("tenant", ID, MybatisBiReconciliationReviewRepository.MAX_ONLINE_PAYLOAD_BYTES);
        verifyNoMoreInteractions(mapper);
    }

    @Test void nestedJdbcColumnReadDenialAlsoHasStableProvisioningError() {
        var chain = new SQLException("wrapper", "HY000", 0);
        chain.setNextException(new SQLException("column command denied", "42000", 1143));
        when(mapper.onlineCapture("tenant", ID, MybatisBiReconciliationReviewRepository.MAX_ONLINE_PAYLOAD_BYTES))
                .thenThrow(new IllegalStateException("mapper wrapper", new BadSqlGrammarException("capture", "private SQL", chain)));
        var failure = catchThrowableOfType(() -> repository.onlineCapture("tenant", ID), BusinessException.class);
        assertThat(failure.getDetails().getFirst().reason()).isEqualTo("BI_ONLINE_SOURCE_READ_NOT_PROVISIONED");
    }

    @Test void unrelatedDatabaseFailuresAreNotMisreportedAsMissingSourceSelectPermission() {
        for (SQLException sql : List.of(new SQLException("syntax", "42000", 1064),
                new SQLException("missing table", "42S02", 1146), new SQLException("database unavailable", "08001", 0),
                new SQLException("database access denied", "42000", 1044))) {
            reset(mapper);
            var wrapped = new BadSqlGrammarException("capture", "SQL", sql);
            when(mapper.onlineCapture("tenant", ID, MybatisBiReconciliationReviewRepository.MAX_ONLINE_PAYLOAD_BYTES)).thenThrow(wrapped);
            assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).isSameAs(wrapped);
        }
    }

    @Test void legacySourceReadsAreNotRelabeledAsOnlineCapturePermissionFailures() {
        var wrapped = new BadSqlGrammarException("file", "SQL", new SQLException("table command denied", "42000", 1142));
        when(mapper.sourceRows("tenant", "batch", 20001)).thenThrow(wrapped);
        assertThatThrownBy(() -> repository.sourceRows("tenant", "batch", 20001)).isSameAs(wrapped);
    }

    @Test void captureQueryEnforcesTenantCompletedEvidenceAndBoundedPayloadBeforeTransfer() throws Exception {
        String sql = String.join("\n", BiReconciliationReviewMapper.class
                .getMethod("onlineCapture", String.class, String.class, int.class).getAnnotation(Select.class).value());
        assertThat(sql).contains("rigour_integration.integration_feishu_online_capture", "tenant_id = UUID_TO_BIN(#{tenant})",
                "id = UUID_TO_BIN(#{captureId})", "complete = TRUE", "completed_at IS NOT NULL",
                "OCTET_LENGTH(payload_json) <= #{maxPayloadBytes}").doesNotContain("UPDATE ", "DELETE ", "INSERT ");
        var row = evidence(payload(null));
        stub(row);
        assertThat(repository.onlineCapture("tenant", ID)).isPresent();
        assertThat(repository.onlineCapture("other-tenant", ID)).isEmpty();
        verify(mapper).onlineCapture("other-tenant", ID, MybatisBiReconciliationReviewRepository.MAX_ONLINE_PAYLOAD_BYTES);
    }

    @Test void completedCapturePreservesUtcWindowAndSourceIdentityNotOrderIdentity() {
        stub(evidence(payload(null)));
        var capture = repository.onlineCapture("tenant", ID).orElseThrow();
        var evidence = capture.version().onlineEvidence();
        assertThat(capture.sourceComplete()).isTrue();
        assertThat(capture.version().batchId()).isNull();
        assertThat(capture.version().importStatus()).isEqualTo("CAPTURED_NON_ATOMIC");
        assertThat(evidence.captureId()).isEqualTo(ID);
        assertThat(evidence.startedAt()).isEqualTo(Instant.parse("2026-09-14T01:00:00Z"));
        assertThat(evidence.completedAt()).isEqualTo(Instant.parse("2026-09-14T01:01:00Z"));
        assertThat(evidence.atomic()).isFalse();
        assertThat(evidence.complete()).isTrue();
        assertThat(evidence.filtered()).isFalse();
        assertThat(evidence.recordCount()).isEqualTo(2);
        assertThat(capture.rows()).allSatisfy(r -> assertThat(r).containsKey("recordId").doesNotContainKey("sourceDocumentNo"));
    }

    @Test void filteredDatabaseFlagOrPayloadViewPreventsFullSourceClaim() {
        var row = evidence(payload(null));
        row.put("filtered", true);
        stub(row);
        assertThat(repository.onlineCapture("tenant", ID).orElseThrow().sourceComplete()).isFalse();
        stub(evidence(payload("view-filtered")));
        var viewCapture = repository.onlineCapture("tenant", ID).orElseThrow();
        assertThat(viewCapture.sourceComplete()).isFalse();
        assertThat(viewCapture.version().onlineEvidence().filtered()).isTrue();
    }
    @Test void optionalProductLookupIsIncludedInCountsButCannotReplaceMandatoryOrderTable() {
        String payload = json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_SALES_ORDER", "orders", null),
                table("FEISHU_SALES_ORDER_LINE", "lines", null), table("FEISHU_PRODUCT", "products", null))));
        var row = evidence(payload);
        row.put("recordCount", 3); row.put("pageCount", 3);
        stub(row);
        var capture = repository.onlineCapture("tenant", ID).orElseThrow();
        assertThat(capture.rows()).hasSize(3);
        assertThat(capture.version().onlineEvidence().recordCount()).isEqualTo(3);
        assertThat(capture.sourceComplete()).isTrue();
        stub(evidence(json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_PRODUCT", "products", null),
                table("FEISHU_SALES_ORDER_LINE", "lines", null))))));
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("缺少销售订单");
    }

    @Test void incompleteOrUnfinishedSnapshotIsNotReadable() {
        var row = evidence(payload(null));
        row.put("complete", false);
        stub(row);
        assertThat(repository.onlineCapture("tenant", ID)).isEmpty();
        row.put("complete", true);
        row.remove("completedAt");
        assertThat(repository.onlineCapture("tenant", ID)).isEmpty();
    }

    @Test void missingDuplicateOrEmptyTablesCannotGenerateMatchingEvidence() {
        for (String invalid : List.of("{\"tables\":[]}", json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_SALES_ORDER", "orders", null)))),
                json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_SALES_ORDER", "orders", null), table("FEISHU_SALES_ORDER", "orders2", null)))))) {
            stub(evidence(invalid));
            assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).isInstanceOf(RuntimeException.class);
        }
        var empty = table("FEISHU_SALES_ORDER_LINE", "lines", null);
        empty.put("rows", List.of());
        stub(evidence(json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_SALES_ORDER", "orders", null), empty)))));
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("没有可核对记录");
    }

    @Test void missingFieldsRecordIdentityOrPartialTableIsRejectedEvenWithCompleteFlag() {
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("recordId", "recOne"),
                Map.<String, Object>of("fields", Map.of("订单号", "DD1")), Map.<String, Object>of("recordId", "recOne", "fields", Map.of()))) {
            var table = table("FEISHU_SALES_ORDER_LINE", "lines", null);
            table.put("rows", List.of(invalid));
            stub(evidence(json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_SALES_ORDER", "orders", null), table)))));
            assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("记录标识或业务字段");
        }
        var table = table("FEISHU_SALES_ORDER_LINE", "lines", null);
        table.put("complete", false);
        stub(evidence(json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_SALES_ORDER", "orders", null), table)))));
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("未完整采集");
    }

    @Test void duplicateRecordsMismatchedCountsInvalidWindowsAndOversizeEvidenceFailClosed() {
        var row = evidence(payload(null));
        row.put("recordCount", 20001);
        stub(row);
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("20000");
        row.put("recordCount", 3);
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("数量与采集证据不一致");
        row.put("recordCount", 2);
        row.put("payloadBytes", MybatisBiReconciliationReviewRepository.MAX_ONLINE_PAYLOAD_BYTES + 1L);
        row.remove("payloadJson");
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("32MiB");
        row = evidence(payload(null));
        row.put("startedAt", LocalDateTime.of(2026, 9, 14, 2, 0));
        stub(row);
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("窗口");
        var table = table("FEISHU_SALES_ORDER_LINE", "lines", null);
        var record = Map.of("recordId", "recDuplicate", "fields", Map.of("订单号", "DD1"));
        table.put("rows", List.of(record, record));
        stub(evidence(json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_SALES_ORDER", "orders", null), table)))));
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("重复");
    }

    @Test void payloadChecksumAndMalformedJsonAreValidated() {
        var row = evidence(payload(null));
        row.put("checksum", "f".repeat(64));
        stub(row);
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("校验值不一致");
        stub(evidence("{broken"));
        assertThatThrownBy(() -> repository.onlineCapture("tenant", ID)).hasMessageContaining("无法解析");
    }

    @Test void oldSavedJsonAndOldConstructorsRemainReadableWithoutOnlineEvidence() {
        String legacy = """
                {"id":"review","from":"2026-09-01T00:00:00Z","to":"2026-09-12T00:00:00Z",
                "sourceVersion":{"batchId":"batch","fileName":"old.xlsx","checksum":"old",
                "uploadedAt":"2026-09-12T00:00:00Z","importStatus":"PREFLIGHTED","rowCount":2},
                "onlineStatus":"UNVERIFIED","status":"UNVERIFIED","sourceDeclaredComplete":false,"notices":[],"rows":[]}
                """;
        when(mapper.find("tenant", "actor", "review")).thenReturn(legacy);
        var review = repository.find("tenant", "actor", "review").orElseThrow();
        assertThat(review.sourceVersion().onlineEvidence()).isNull();
        assertThat(review.sourceVersion().fileName()).isEqualTo("old.xlsx");
        var command = json.readValue("{\"batchId\":\"batch\",\"sourceDeclaredComplete\":true}", BiReconciliationReview.Command.class);
        assertThat(command.onlineCaptureId()).isNull();
        assertThat(new BiReconciliationReview.Command("batch", null, null, null, null, true).onlineCaptureId()).isNull();
    }

    @Test void onlineReviewRoundTripStoresCaptureIdentityAndNeverRequeriesSourceOnGet() {
        stub(evidence(payload(null)));
        var version = repository.onlineCapture("tenant", ID).orElseThrow().version();
        var now = Instant.parse("2026-09-14T02:00:00Z");
        var review = new BiReconciliationReview(ID, now, now, now, now, version, null, "CAPTURED_NON_ATOMIC",
                "UNVERIFIED", true, null, List.of(), List.of());
        clearInvocations(mapper);
        repository.save("tenant", "actor", review);
        verify(mapper).insert(eq("tenant"), eq("actor"), eq(ID), eq(ID), any(), any(), eq(json.writeValueAsString(review)));
        clearInvocations(mapper);
        when(mapper.find("tenant", "actor", ID)).thenReturn(json.writeValueAsString(review));
        assertThat(repository.find("tenant", "actor", ID)).contains(review);
        verify(mapper).find("tenant", "actor", ID);
        verifyNoMoreInteractions(mapper);
    }

    private void stub(Map<String, Object> row) {
        when(mapper.onlineCapture("tenant", ID, MybatisBiReconciliationReviewRepository.MAX_ONLINE_PAYLOAD_BYTES)).thenReturn(row);
    }
    private String payload(String view) {
        return json.writeValueAsString(Map.of("tables", List.of(table("FEISHU_SALES_ORDER", "orders", view), table("FEISHU_SALES_ORDER_LINE", "lines", null))));
    }
    private static Map<String, Object> table(String code, String id, String view) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("tableId", id); table.put("tableCode", code); table.put("viewId", view);
        table.put("rows", List.of(Map.of("recordId", "rec" + id, "fields", Map.of("订单编号", "DD1"))));
        return table;
    }
    private static Map<String, Object> evidence(String payload) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("captureId", ID); row.put("sourceId", "sales-source"); row.put("sourceName", "销售订单");
        row.put("sourceUrl", "https://example.feishu.cn/base/source");
        row.put("startedAt", LocalDateTime.of(2026, 9, 14, 1, 0)); row.put("completedAt", LocalDateTime.of(2026, 9, 14, 1, 1));
        row.put("complete", true); row.put("filtered", false); row.put("recordCount", 2); row.put("pageCount", 2);
        row.put("payloadJson", payload); row.put("payloadBytes", payload.getBytes(StandardCharsets.UTF_8).length);
        try { row.put("checksum", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)))); }
        catch (Exception ex) { throw new AssertionError(ex); }
        return row;
    }
}
