package com.rigour.erp.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

class CostAttachmentMutationTest {
    final ErpCostStore costs = mock(ErpCostStore.class);
    final CostAttachmentStore files = mock(CostAttachmentStore.class);
    final CostAttachmentStorage storage = mock(CostAttachmentStorage.class);
    final CostAttachmentService service = new CostAttachmentService(costs, files, storage);
    final UUID tenant = UUID.randomUUID();
    final ErpCostCommand data = JsonMapper.builder().build().readValue("{}", ErpCostCommand.class);
    final byte[] pdf = "%PDF-1.7 new voucher".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    @BeforeEach
    void setup() {
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("erp:cost:marketing:write")));
        state("DRAFT", 3);
        when(files.list(tenant.toString(), "bill")).thenReturn(List.of(file("old")));
        when(storage.upload(anyString(), anyString(), anyString(), any()))
                .thenReturn("private/new");
        TransactionSynchronizationManager.initSynchronization();
    }

    void state(String status, int revision) {
        when(costs.entry(tenant.toString(), "MARKETING", "bill", "erp:cost:marketing:write"))
                .thenReturn(
                        new ErpCostDetailView(
                                "bill",
                                "CST1",
                                "MARKETING",
                                status,
                                revision,
                                data,
                                List.of(),
                                null,
                                null,
                                Instant.EPOCH,
                                Instant.EPOCH));
    }

    ErpCostAttachmentView file(String id) {
        return new ErpCostAttachmentView(
                id, id + ".pdf", "application/pdf", 20, "user", Instant.EPOCH);
    }

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clearSynchronization();
        TestAuthorizationContext.clear();
    }

    @Test
    void confirmedAndVoidAttachmentsCannotBeModified() {
        for (var status : List.of("CONFIRMED", "VOID")) {
            state(status, 3);
            assertThatThrownBy(
                            () ->
                                    service.replace(
                                            "marketing",
                                            "bill",
                                            "old",
                                            3,
                                            "new.pdf",
                                            "application/pdf",
                                            pdf))
                    .hasMessageContaining("待确认");
            assertThatThrownBy(() -> service.remove("marketing", "bill", "old", 3))
                    .hasMessageContaining("待确认");
        }
        verifyNoInteractions(storage);
        verify(files, never()).retire(anyString(), anyString(), anyString());
    }

    @Test
    void replacingAtLimitKeepsOneActiveAttachmentAndRetainsOldObject() {
        when(files.list(tenant.toString(), "bill"))
                .thenReturn(
                        java.util.stream.IntStream.range(0, 20)
                                .mapToObj(i -> file(i == 0 ? "old" : "f" + i))
                                .toList());
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        "marketing", "bill", 3, "new.pdf", "application/pdf", pdf))
                .hasMessageContaining("20");
        var replacement =
                service.replace("marketing", "bill", "old", 3, "new.pdf", "application/pdf", pdf);
        verify(files).retire(tenant.toString(), "bill", "old");
        verify(files).add(tenant.toString(), "bill", 3, replacement, "private/new");
        verify(costs)
                .attachmentChanged(
                        eq(tenant.toString()),
                        eq("MARKETING"),
                        eq("bill"),
                        anyString(),
                        eq("ATTACHMENT_REPLACED"),
                        contains("old.pdf"),
                        eq("erp:cost:marketing:write"));
        verify(storage, never()).discard(anyString());
    }

    @Test
    void replacingMissingOrForeignFileNeverUploads() {
        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "marketing",
                                        "bill",
                                        "foreign",
                                        3,
                                        "new.pdf",
                                        "application/pdf",
                                        pdf))
                .hasMessageContaining("不存在");
        verifyNoInteractions(storage);
    }

    @Test
    void staleRevisionCannotRemoveOrReplace() {
        assertThatThrownBy(() -> service.remove("marketing", "bill", "old", 2))
                .hasMessageContaining("刷新");
        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "marketing",
                                        "bill",
                                        "old",
                                        2,
                                        "new.pdf",
                                        "application/pdf",
                                        pdf))
                .hasMessageContaining("刷新");
        verifyNoInteractions(storage);
    }

    @Test
    void replacementFailureDiscardsOnlyNewCosObjectOnRollback() {
        doThrow(new IllegalStateException("metadata failed"))
                .when(files)
                .add(anyString(), anyString(), anyInt(), any(), anyString());
        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "marketing",
                                        "bill",
                                        "old",
                                        3,
                                        "new.pdf",
                                        "application/pdf",
                                        pdf))
                .hasMessageContaining("metadata failed");
        for (var sync : TransactionSynchronizationManager.getSynchronizations())
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).discard("private/new");
        verify(storage, never()).discard("private/old");
    }

    @Test
    void removalAdvancesVersionAndAuditsWithoutDeletingCosObject() {
        service.remove("marketing", "bill", "old", 3);
        verify(files).touch(eq(tenant.toString()), eq("bill"), eq(3), anyString());
        verify(files).retire(tenant.toString(), "bill", "old");
        verify(costs)
                .attachmentChanged(
                        eq(tenant.toString()),
                        eq("MARKETING"),
                        eq("bill"),
                        anyString(),
                        eq("ATTACHMENT_REMOVED"),
                        contains("old.pdf"),
                        eq("erp:cost:marketing:write"));
        verifyNoInteractions(storage);
    }
}
