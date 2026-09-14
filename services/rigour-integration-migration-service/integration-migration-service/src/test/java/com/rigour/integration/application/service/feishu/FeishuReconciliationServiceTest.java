package com.rigour.integration.application.service.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureCommand;
import com.rigour.integration.application.port.out.FeishuBitableClient;
import com.rigour.integration.application.port.out.FeishuBitableClient.CaptureResult;
import com.rigour.integration.application.port.out.FeishuBitableClient.CapturedRecord;
import com.rigour.integration.application.port.out.FeishuBitableClientException;
import com.rigour.integration.application.port.out.FeishuCaptureBudget;
import com.rigour.integration.application.port.out.FeishuOnlineCaptureStore;
import com.rigour.integration.application.port.out.FeishuReconciliationSources;
import com.rigour.integration.application.port.out.FeishuReconciliationSources.Source;
import com.rigour.integration.application.port.out.FeishuReconciliationSources.Table;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class FeishuReconciliationServiceTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Set<String> PERMISSIONS = Set.of("analytics:reconciliation:write", "integration:feishu:import");
    private final FeishuReconciliationSources sources = mock(FeishuReconciliationSources.class);
    private final FeishuBitableClient client = mock(FeishuBitableClient.class);
    private final FeishuOnlineCaptureStore store = mock(FeishuOnlineCaptureStore.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final FeishuReconciliationService service = new FeishuReconciliationService(sources, client, store, json,
            Clock.fixed(Instant.parse("2026-09-14T01:00:00Z"), ZoneOffset.UTC));
    private final List<String> payloads = new ArrayList<>();

    @BeforeEach
    void setup() {
        when(sources.forTenant(TENANT)).thenReturn(List.of(source(TENANT, false)));
        doAnswer(call -> { payloads.add(call.getArgument(3)); return null; })
                .when(store).save(any(), any(), any(), any());
    }

    @Test
    void sourceListOnlyExposesBusinessLabelsAndTenantVisibleEntries() {
        when(sources.forTenant(TENANT)).thenReturn(List.of(source(TENANT, true), source(OTHER, false)));
        var views = service.sources(user(PERMISSIONS));
        assertThat(views).hasSize(1);
        assertThat(views.getFirst().filtered()).isTrue();
        assertThat(views.getFirst().tables()).extracting("tableCode")
                .containsExactly("FEISHU_SALES_ORDER", "FEISHU_SALES_ORDER_LINE", "FEISHU_PRODUCT");
        String rendered = json.writeValueAsString(views);
        assertThat(rendered).doesNotContain("tenantId", "appToken", "tableId", "appFixture", "tblOrder", "Secret");
        verifyNoInteractions(client, store);
    }

    @Test
    void sourceWithNoTenantConfigurationIsEmptyAndCaptureFailsClosed() {
        when(sources.forTenant(TENANT)).thenReturn(List.of());
        assertThat(service.sources(user(PERMISSIONS))).isEmpty();
        assertThatThrownBy(() -> service.capture(user(PERMISSIONS), new CaptureCommand("fixture")))
                .hasMessage("飞书对账来源不存在或不可访问");
        verifyNoInteractions(client, store);
    }

    @Test
    void foreignTenantSourceIdCannotBeCapturedEvenIfPortAccidentallyReturnsIt() {
        when(sources.forTenant(TENANT)).thenReturn(List.of(source(OTHER, false)));
        assertThatThrownBy(() -> service.capture(user(PERMISSIONS), new CaptureCommand("fixture")))
                .hasMessage("飞书对账来源不存在或不可访问");
        verifyNoInteractions(client, store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"analytics:reconciliation:write", "integration:feishu:import", "integration:feishu:write", ""})
    void bothPermissionFamiliesAreRequiredBeforeListingOrCapturing(String permission) {
        CallerIdentity caller = user(Set.of(permission));
        assertThatThrownBy(() -> service.sources(caller)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.capture(caller, new CaptureCommand("fixture")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(sources, client, store);
    }

    @Test
    void integrationWriteAlternativeAndSignedWildcardStillRequireTenantUser() {
        assertThat(service.sources(user(Set.of("analytics:reconciliation:write", "integration:feishu:write")))).hasSize(1);
        assertThat(service.sources(user(Set.of("*:*:*")))).hasSize(1);
        UUID principal = UUID.randomUUID();
        CallerIdentity serviceCaller = new CallerIdentity("SERVICE", principal, TENANT, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("*:*:*"));
        CallerIdentity platformCaller = new CallerIdentity("PLATFORM", principal, null, null, principal,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("*:*:*"));
        for (CallerIdentity caller : List.of(serviceCaller, platformCaller)) {
            assertThatThrownBy(() -> service.capture(caller, new CaptureCommand("fixture")))
                    .isInstanceOf(AuthorizationDeniedException.class);
        }
        assertThatThrownBy(() -> service.sources(null)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(client, store);
    }

    @Test
    void completeEvidenceIncludesAllTablesMetadataAndRawFieldsWithoutImportOrDownloadCalls() throws Exception {
        stubComplete();
        var result = service.capture(user(PERMISSIONS), new CaptureCommand("fixture"));
        assertThat(result.complete()).isTrue();
        assertThat(result.filtered()).isFalse();
        assertThat(result.recordCount()).isEqualTo(3);
        assertThat(result.pageCount()).isEqualTo(3);
        assertThat(payloads).hasSize(1);
        String payload = payloads.getFirst();
        assertThat(payload).contains("\"viewId\":null", "\"createdTime\":1770000000000", "\"lastModifiedTime\":1770000001000",
                "\"empty\":null", "link_record_ids", "FEISHU_PRODUCT");
        assertThat(payload).doesNotContain("created_by", "modified_by");
        assertThat(result.checksum()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))));
        for (String table : List.of("tblProduct", "tblOrder", "tblLine")) {
            verify(client).captureRecords(eq("appFixture"), eq(table), eq(null), any());
        }
        verifyNoMoreInteractions(client);
        assertThat(FeishuReconciliationService.class.getDeclaredFields()).noneMatch(field ->
                field.getType().getSimpleName().contains("Import") || field.getType().getSimpleName().contains("Projection"));
    }

    @Test
    void checksumStableAcrossMapAndRecordArrivalOrderButChangesWithRawValues() {
        when(sources.forTenant(TENANT)).thenReturn(List.of(new Source("fixture", "订单明细", TENANT, "appFixture",
                List.of(new Table("tblOrder", "FEISHU_SALES_ORDER", null),
                        new Table("tblLine", "FEISHU_SALES_ORDER_LINE", null)))));
        Map<String, Object> fieldsOne = new LinkedHashMap<>();
        fieldsOne.put("z", new java.math.BigDecimal("12.3400")); fieldsOne.put("a", List.of(Map.of("text", "原文")));
        Map<String, Object> fieldsTwo = new LinkedHashMap<>();
        fieldsTwo.put("a", List.of(Map.of("text", "原文"))); fieldsTwo.put("z", new java.math.BigDecimal("12.34"));
        when(client.captureRecords(eq("appFixture"), eq("tblOrder"), eq(null), any()))
                .thenReturn(new CaptureResult(List.of(new CapturedRecord("r2", fieldsOne), new CapturedRecord("r1", Map.of())), 1, true))
                .thenReturn(new CaptureResult(List.of(new CapturedRecord("r1", Map.of()), new CapturedRecord("r2", fieldsTwo)), 1, true))
                .thenReturn(new CaptureResult(List.of(new CapturedRecord("r1", Map.of()), new CapturedRecord("r2", Map.of("amount", 13))), 1, true));
        when(client.captureRecords(eq("appFixture"), eq("tblLine"), eq(null), any()))
                .thenReturn(new CaptureResult(List.of(), 1, true));
        var first = service.capture(user(PERMISSIONS), new CaptureCommand("fixture"));
        var second = service.capture(user(PERMISSIONS), new CaptureCommand("fixture"));
        var third = service.capture(user(PERMISSIONS), new CaptureCommand("fixture"));
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.checksum()).isEqualTo(second.checksum()).isNotEqualTo(third.checksum());
        assertThat(payloads.get(0)).isEqualTo(payloads.get(1));
    }

    @Test
    void filteredEvidenceIsAlwaysMarkedFromServerConfiguration() {
        when(sources.forTenant(TENANT)).thenReturn(List.of(source(TENANT, true)));
        stubComplete();
        when(client.captureRecords(eq("appFixture"), eq("tblLine"), eq("viwSubset"), any()))
                .thenReturn(new CaptureResult(List.of(), 1, true));
        assertThat(service.capture(user(PERMISSIONS), new CaptureCommand("fixture")).filtered()).isTrue();
        assertThat(payloads.getFirst()).contains("viwSubset");
    }

    @Test
    void capturesConfiguredSourceUrlWithoutExposingItInSourceSelector() {
        Source old = source(TENANT, false);
        String url = "https://tenant-fixture.feishu.cn/base/appFixture?table=tblOrder";
        when(sources.forTenant(TENANT)).thenReturn(List.of(new Source(old.id(), old.name(), old.tenantId(),
                old.appToken(), old.tables(), url)));
        stubComplete();
        assertThat(json.writeValueAsString(service.sources(user(PERMISSIONS)))).doesNotContain("sourceUrl", url);
        var result = service.capture(user(PERMISSIONS), new CaptureCommand("fixture"));
        assertThat(result.sourceUrl()).isEqualTo(url);
        verify(store).save(eq(TENANT), any(), eq(result), eq(payloads.getFirst()));
        assertThat(payloads.getFirst()).doesNotContain("sourceUrl", url);
    }

    @Test
    void laterIncompleteOrFailedTableNeverPersistsPartialEvidence() {
        stubComplete();
        when(client.captureRecords(eq("appFixture"), eq("tblLine"), eq(null), any()))
                .thenReturn(new CaptureResult(List.of(), 1, false))
                .thenThrow(new FeishuBitableClientException("INTERRUPTED", "interrupted", false));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.capture(user(PERMISSIONS), new CaptureCommand("fixture")))
                    .isInstanceOf(FeishuBitableClientException.class);
        }
        verifyNoInteractions(store);
    }

    @Test
    void rejectsDuplicateRowsFromAlternativeClient() {
        stubComplete();
        when(client.captureRecords(eq("appFixture"), eq("tblLine"), eq(null), any()))
                .thenReturn(new CaptureResult(List.of(new CapturedRecord("r", Map.of()), new CapturedRecord("r", Map.of())), 1, true));
        assertThatThrownBy(() -> service.capture(user(PERMISSIONS), new CaptureCommand("fixture"))).hasMessageContaining("重复");
        verifyNoInteractions(store);
    }

    @Test
    void rejectsMissingOrderOrLineSourceBeforeAnyNetworkCall() {
        when(sources.forTenant(TENANT)).thenReturn(List.of(new Source("fixture", "不完整", TENANT, "appFixture",
                List.of(new Table("tblOrder", "FEISHU_SALES_ORDER", null)))));
        assertThatThrownBy(() -> service.capture(user(PERMISSIONS), new CaptureCommand("fixture"))).hasMessageContaining("同时配置");
        verifyNoInteractions(client, store);
    }

    private void stubComplete() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("empty", null);
        fields.put("产品编号", Map.of("link_record_ids", List.of("recProduct")));
        for (String table : List.of("tblProduct", "tblOrder", "tblLine")) {
            when(client.captureRecords(eq("appFixture"), eq(table), eq(null), any()))
                    .thenReturn(new CaptureResult(List.of(new CapturedRecord("r" + table, fields,
                            1770000000000L, 1770000001000L)), 1, true));
        }
    }
    private static Source source(UUID tenant, boolean filtered) {
        return new Source("fixture", "销售对账", tenant, "appFixture", List.of(
                new Table("tblOrder", "FEISHU_SALES_ORDER", null),
                new Table("tblLine", "FEISHU_SALES_ORDER_LINE", filtered ? "viwSubset" : null),
                new Table("tblProduct", "FEISHU_PRODUCT", null)));
    }
    private static CallerIdentity user(Set<String> permissions) {
        UUID user = UUID.fromString("00000000-0000-0000-0000-000000000003");
        return new CallerIdentity("TENANT", user, TENANT, user, null, UUID.randomUUID(), 0, 0, 0, Set.of(), permissions);
    }
}
