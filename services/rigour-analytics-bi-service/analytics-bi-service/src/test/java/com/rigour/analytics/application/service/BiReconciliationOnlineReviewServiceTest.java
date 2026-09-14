package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.Command;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.History;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.OnlineEvidence;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.Version;
import com.rigour.analytics.application.port.out.BiReconciliationReviewStore;
import com.rigour.analytics.application.port.out.BiReconciliationReviewStore.OnlineCapture;
import com.rigour.analytics.application.port.out.BiReconciliationUnitDictionary;
import com.rigour.analytics.infrastructure.persistence.mapper.BiReconciliationReviewMapper;
import com.rigour.analytics.infrastructure.persistence.repository.MybatisBiReconciliationReviewRepository;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.BadSqlGrammarException;
import tools.jackson.databind.json.JsonMapper;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.BATCH;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.COMMAND;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.DATE;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.NOW;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.VERSION;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.facts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BiReconciliationOnlineReviewServiceTest {
    private static final String CAPTURE = "33333333-3333-3333-3333-333333333333";
    private final String tenant = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString();
    private final BiReconciliationReviewStore store = mock(BiReconciliationReviewStore.class);
    private final BiDataScopeService scope = mock(BiDataScopeService.class);
    private final BiReconciliationReviewService service = new BiReconciliationReviewService(store, scope, Clock.fixed(NOW, ZoneOffset.UTC), JsonMapper.builder().build(),
            ignored -> Map.of("BOX", "箱"));

    @AfterEach void clear() { TestAuthorizationContext.clear(); }

    @Test void captureReadsDictionaryOnceAndHistoricalQueriesReusePersistedUnitEvidence() {
        setup(false);
        var units = mock(BiReconciliationUnitDictionary.class);
        when(units.productUnits(any())).thenReturn(Map.of("BOX", "箱"));
        var serviceWithUnits = new BiReconciliationReviewService(store, scope, Clock.fixed(NOW, ZoneOffset.UTC), JsonMapper.builder().build(), units);
        var result = serviceWithUnits.capture(onlineCommand());
        var saved = ArgumentCaptor.forClass(BiReconciliationReview.class);
        verify(store).save(eq(tenant), eq(actor), saved.capture());
        var sku = saved.getValue().rows().stream().filter(row -> "SKU".equals(row.kind())).findFirst().orElseThrow();
        assertThat(sku.business().rawUnit()).isEqualTo("箱");
        assertThat(sku.business().unitCode()).isEqualTo("BOX");
        when(store.find(tenant, actor, result.id())).thenReturn(Optional.of(saved.getValue()));
        serviceWithUnits.get(result.id(), "SKU", null, null, null, null, null, 1, 50);
        serviceWithUnits.history();
        verify(units).productUnits(any());
        verifyNoMoreInteractions(units);
    }

    @Test void missingSourceReadProvisioningStopsCaptureBeforeBusinessReadsOrSavingReview() {
        authorize();
        var mapper = mock(BiReconciliationReviewMapper.class);
        var json = JsonMapper.builder().build();
        var repository = new MybatisBiReconciliationReviewRepository(mapper, json);
        var captureService = new BiReconciliationReviewService(repository, scope, Clock.fixed(NOW, ZoneOffset.UTC), json, ignored -> Map.of("BOX", "箱"));
        when(mapper.onlineCapture(eq(tenant), eq(CAPTURE), anyInt())).thenThrow(new BadSqlGrammarException("capture", "private SQL",
                new SQLException("SELECT command denied", "42000", 1142)));
        var failure = catchThrowableOfType(() -> captureService.capture(onlineCommand()), BusinessException.class);
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(failure.getDetails().getFirst().reason()).isEqualTo("BI_ONLINE_SOURCE_READ_NOT_PROVISIONED");
        verify(mapper).onlineCapture(eq(tenant), eq(CAPTURE), anyInt());
        verifyNoMoreInteractions(mapper);
    }

    @Test void onlineClientCompletenessAndExportTimestampCannotOverrideStoredEvidence() {
        setup(false);
        var command = new Command(null, null, COMMAND.from(), COMMAND.to(), NOW.plusSeconds(86400), false, CAPTURE);
        var result = service.capture(command);
        assertThat(result.onlineStatus()).isEqualTo("CAPTURED_NON_ATOMIC");
        assertThat(result.status()).isEqualTo("SNAPSHOT_MATCH");
        assertThat(result.sourceDeclaredComplete()).isTrue();
        assertThat(result.sourceExportedAt()).isNull();
        assertThat(result.sourceVersion().onlineEvidence().atomic()).isFalse();
        assertThat(result.notices()).anyMatch(n -> n.contains("采集窗口") && n.contains("不等于原子快照"));
        verify(store).onlineCapture(tenant, CAPTURE);
        verify(store, never()).sourceRows(any(), any(), anyInt());
        verify(store, never()).version(any(), any());
        verify(store).save(eq(tenant), eq(actor), any(BiReconciliationReview.class));
    }

    @Test void clientTrueCannotCertifyFilteredCaptureAndAbsentRecordsAreNotDeletions() {
        setup(true);
        var previous = new Version(BATCH, "previous.xlsx", "b".repeat(64), null, NOW.minusSeconds(3600), "PREFLIGHTED", 2);
        when(store.version(tenant, BATCH)).thenReturn(Optional.of(previous));
        when(store.sourceRows(tenant, BATCH, 20001)).thenReturn(raw("DD2"));
        var result = service.capture(new Command(null, BATCH, COMMAND.from(), COMMAND.to(), null, true, CAPTURE));
        assertThat(result.sourceDeclaredComplete()).isFalse();
        assertThat(result.status()).isEqualTo("UNVERIFIED");
        assertThat(result.rows()).noneMatch(row -> "DELETED".equals(row.versionStatus()));
        assertThat(result.rows()).filteredOn(r -> "DD2".equals(r.orderNo())).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("UNVERIFIED");
            assertThat(r.versionStatus()).isEqualTo("ABSENT_IN_SELECTED_VERSION");
        });
        assertThat(result.notices()).anyMatch(n -> n.contains("不证明在线删除"));
    }

    @Test void onlineCaptureCannotBeReadAcrossTenantsAndDoesNotTouchBusinessOnFailure() {
        authorize();
        when(store.onlineCapture("other-tenant", CAPTURE)).thenReturn(Optional.of(capture(false)));
        assertThatThrownBy(() -> service.capture(onlineCommand())).hasMessageContaining("当前租户完整成功");
        verify(store).onlineCapture(tenant, CAPTURE);
        verifyNoMoreInteractions(store);
    }

    @Test void exactlyOneSourceIdentifierIsRequiredBeforeAnyRead() {
        authorize();
        for (Command command : List.of(new Command(null, null, COMMAND.from(), NOW, null, false),
                new Command(BATCH, null, COMMAND.from(), NOW, null, true, CAPTURE),
                new Command("", null, COMMAND.from(), NOW, null, true),
                new Command(null, null, COMMAND.from(), NOW, null, false, ""))) {
            assertThatThrownBy(() -> service.capture(command)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(store);
    }

    @Test void incompleteEvidenceIsRejectedEvenIfCallerClaimsComplete() {
        authorize();
        var invalid = new OnlineEvidence(CAPTURE, "source", "在线订单", null, NOW.minusSeconds(60), NOW, false, false, 2, 2, false);
        var version = new Version(null, "在线订单", "c".repeat(64), null, NOW, "CAPTURED_NON_ATOMIC", 2, invalid);
        when(store.onlineCapture(tenant, CAPTURE)).thenReturn(Optional.of(new OnlineCapture(version, raw("DD1"), true)));
        assertThatThrownBy(() -> service.capture(new Command(null, null, COMMAND.from(), NOW, null, true, CAPTURE)))
                .hasMessageContaining("证据不完整");
        verify(store, never()).businessRows(any(), anyInt());
        verify(store, never()).save(any(), any(), any());
    }

    @Test void noOnlineRowsCannotSaveAMatchingSnapshot() {
        setup(false);
        when(store.onlineCapture(tenant, CAPTURE)).thenReturn(Optional.of(new OnlineCapture(capture(false).version(), List.of(), true)));
        assertThatThrownBy(() -> service.capture(onlineCommand())).hasMessageContaining("来源为空");
        verify(store, never()).save(any(), any(), any());
    }

    @Test void onlineEvidenceCanCompareAnOlderFileVersion() {
        setup(false);
        when(store.version(tenant, BATCH)).thenReturn(Optional.of(VERSION));
        // The historical file must predate the online collection start, not merely its end.
        var previous = new Version(BATCH, "previous.xlsx", "b".repeat(64), null, NOW.minusSeconds(3600), "PREFLIGHTED", 2);
        when(store.version(tenant, BATCH)).thenReturn(Optional.of(previous));
        when(store.sourceRows(tenant, BATCH, 20001)).thenReturn(raw("DD1"));
        var result = service.capture(new Command(null, BATCH, COMMAND.from(), NOW, null, false, CAPTURE));
        assertThat(result.previousVersion()).isEqualTo(previous);
        assertThat(result.previousVersion().onlineEvidence()).isNull();
        assertThat(result.rows()).allMatch(r -> "UNCHANGED".equals(r.versionStatus()));
        assertThat(result.onlineStatus()).isNotEqualTo("ONLINE_MATCH");
    }

    @Test void legacyFileCaptureStillUsesRawBatchAndClientExportDeclaration() {
        authorize();
        when(store.version(tenant, BATCH)).thenReturn(Optional.of(VERSION));
        when(store.sourceRows(tenant, BATCH, 20001)).thenReturn(raw("DD1"));
        when(store.businessRows(tenant, 20001)).thenReturn(facts("DD1", "100"));
        when(store.biRows(tenant, 20001)).thenReturn(facts("DD1", "100"));
        var result = service.capture(new Command(BATCH, null, COMMAND.from(), NOW, NOW.minusSeconds(10), false));
        assertThat(result.onlineStatus()).isEqualTo("UNVERIFIED");
        assertThat(result.sourceDeclaredComplete()).isFalse();
        assertThat(result.sourceExportedAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(result.sourceVersion().onlineEvidence()).isNull();
        verify(store, never()).onlineCapture(any(), any());
    }

    @Test void storedOnlineReviewAndHistoryOnlyReadBiSnapshot() {
        setup(false);
        var review = BiReconciliationReviewService.compare(CAPTURE, onlineCommand(), capture(false).version(), null,
                facts("DD1", "100"), facts("DD1", "100"), facts("DD1", "100"), List.of(), NOW, NOW);
        clearInvocations(store);
        when(store.find(tenant, actor, CAPTURE)).thenReturn(Optional.of(review));
        when(store.history(tenant, actor)).thenReturn(List.of(new History(CAPTURE, "在线订单", NOW, "SNAPSHOT_MATCH")));
        assertThat(service.get(CAPTURE, "ORDER", null, null, null, null, null, 1, 50).sourceVersion().onlineEvidence()).isNotNull();
        assertThat(service.history()).hasSize(1);
        verify(store).find(tenant, actor, CAPTURE);
        verify(store).history(tenant, actor);
        verifyNoMoreInteractions(store);
    }

    private void setup(boolean filtered) {
        authorize();
        when(store.onlineCapture(tenant, CAPTURE)).thenReturn(Optional.of(capture(filtered)));
        when(store.businessRows(tenant, 20001)).thenReturn(facts("DD1", "100"));
        when(store.biRows(tenant, 20001)).thenReturn(facts("DD1", "100"));
    }
    private void authorize() {
        var user = UUID.fromString(actor);
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, UUID.fromString(tenant), user, null, UUID.randomUUID(), 1, 1, 1,
                Set.of("TENANT_SUPER_ADMIN"), Set.of("analytics:dashboard:read", "analytics:reconciliation:write")));
    }
    private static Command onlineCommand() { return new Command(null, null, COMMAND.from(), NOW, null, false, CAPTURE); }
    private static OnlineCapture capture(boolean filtered) {
        var evidence = new OnlineEvidence(CAPTURE, "source", "在线订单", "https://example.feishu.cn/base/source",
                NOW.minusSeconds(120), NOW.minusSeconds(60), true, filtered, 2, 2, false);
        return new OnlineCapture(new Version(null, "在线订单", "c".repeat(64), evidence.sourceUrl(), evidence.completedAt(),
                "CAPTURED_NON_ATOMIC", 2, evidence), raw("DD1"), !filtered);
    }
    private static List<Map<String, Object>> raw(String no) {
        var json = JsonMapper.builder().build();
        return List.of(BiReconciliationSourceNormalizerTest.online("FEISHU_SALES_ORDER", "recHeader", json.writeValueAsString(Map.of(
                "订单编号", no, "销售日期", DATE.toString(), "城市", "北京", "销售", "销售甲", "门店", "客户", "数量", 1,
                "实际小计", 100, "收款合计", 0, "待付金额", 100))),
                BiReconciliationSourceNormalizerTest.online("FEISHU_SALES_ORDER_LINE", "recLine", json.writeValueAsString(Map.of(
                        "关联订单", no, "订单产品", "方便面", "SKU编码", "NOODLE", "单位", "箱", "数量", 1, "实际小计", 100))));
    }
}
