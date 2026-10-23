package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class HrExpenseAttachmentRetryTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final String permission = "hr:expense:write";
    final HrExpenseStore costs = mock(HrExpenseStore.class);
    final CostAttachmentStore files = mock(CostAttachmentStore.class);
    final CostAttachmentStorage storage = mock(CostAttachmentStorage.class);
    final CostAttachmentService service = new CostAttachmentService(costs, files, storage);
    final HrExpenseDetailView current = mock(HrExpenseDetailView.class);
    final List<HrExpenseAttachmentView> attachments = new ArrayList<>();
    final byte[] bytes = "%PDF-1.7 test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final AtomicInteger revision = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of(),
                        Set.of(permission)));
        TransactionSynchronizationManager.initSynchronization();
        when(costs.expenseDetail(tenant.toString(), "bill", permission)).thenReturn(current);
        when(current.statusCode()).thenReturn("DRAFT");
        when(current.revision()).thenAnswer(invocation -> revision.get());
        when(files.list(tenant.toString(), "bill")).thenReturn(attachments);
        when(storage.upload(anyString(), anyString(), anyString(), any()))
                .thenReturn("private-key");
        doAnswer(
                        invocation -> {
                            attachments.add(invocation.getArgument(3));
                            revision.incrementAndGet();
                            return null;
                        })
                .when(files)
                .add(anyString(), anyString(), anyInt(), any(), anyString());
    }

    @AfterEach
    void tearDown() {
        TestAuthorizationContext.clear();
        TransactionSynchronizationManager.clearSynchronization();
    }

    HrExpenseAttachmentView upload(int version, String name, byte[] content) {
        return service.upload("bill", version, name, "application/pdf", content);
    }

    @Test
    void retryWithOldRevisionDoesNotDuplicateFileOrAudit() {
        var first = upload(1, "票据.pdf", bytes);
        var retried = upload(1, "票据.pdf", bytes);
        assertThat(retried).isEqualTo(first);
        assertThat(revision.get()).isEqualTo(2);
        assertThat(attachments).hasSize(1);
        assertThat(first.createdBy()).isEqualTo(user.toString());
        verify(storage, times(1)).upload(anyString(), anyString(), anyString(), any());
        verify(files, times(1)).add(anyString(), anyString(), eq(1), eq(first), anyString());
        verify(costs, times(1))
                .attachmentAdded(tenant.toString(), "bill", user.toString(), first.id());
    }

    @Test
    void changedFileCannotBypassRevisionAndDraftChecks() {
        upload(1, "票据.pdf", bytes);
        assertThatThrownBy(() -> upload(1, "另一票据.pdf", bytes))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记录已更新");
        assertThatThrownBy(() -> upload(1, "票据.pdf", "%PDF-1.7 changed".getBytes()))
                .isInstanceOf(BusinessException.class);
        when(current.statusCode()).thenReturn("CONFIRMED");
        assertThatThrownBy(() -> upload(2, "票据.pdf", bytes))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待确认");
        verify(storage, times(1)).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    void fullRecordAllowsRetryButRejectsNewFile() {
        var first = upload(1, "票据.pdf", bytes);
        for (int i = 1; i < 20; i++)
            attachments.add(
                    new HrExpenseAttachmentView(
                            "other-" + i,
                            "other.pdf",
                            "application/pdf",
                            10,
                            user.toString(),
                            java.time.Instant.now()));
        assertThat(upload(1, "票据.pdf", bytes)).isEqualTo(first);
        assertThatThrownBy(() -> upload(2, "额外.pdf", bytes))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多20");
        assertThat(attachments).hasSize(20);
    }

    @Test
    void unauthorizedRecordCannotReuseAnotherFilesReceipt() {
        upload(1, "票据.pdf", bytes);
        doThrow(new AuthorizationDeniedException(permission))
                .when(costs)
                .expenseDetail(tenant.toString(), "bill", permission);
        assertThatThrownBy(() -> upload(1, "票据.pdf", bytes))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(storage, times(1)).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    void idIsScopedToTenantRecordNameAndContent() {
        String id =
                CostAttachmentService.attachmentId(
                        "tenant", "bill", "票据.pdf", "application/pdf", bytes);
        assertThat(
                        CostAttachmentService.attachmentId(
                                "other", "bill", "票据.pdf", "application/pdf", bytes))
                .isNotEqualTo(id);
        assertThat(
                        CostAttachmentService.attachmentId(
                                "tenant", "other", "票据.pdf", "application/pdf", bytes))
                .isNotEqualTo(id);
        assertThat(
                        CostAttachmentService.attachmentId(
                                "tenant", "bill", "另一.pdf", "application/pdf", bytes))
                .isNotEqualTo(id);
        assertThat(
                        CostAttachmentService.attachmentId(
                                "tenant", "bill", "票据.pdf", "application/pdf", new byte[] {1}))
                .isNotEqualTo(id);
    }
}
