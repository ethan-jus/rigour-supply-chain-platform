package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.LogisticsCostAttachmentView;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

class CostAttachmentPreviewTest {
    final OrderLogisticsCostStore costs = mock(OrderLogisticsCostStore.class);
    final CostAttachmentStore files = mock(CostAttachmentStore.class);
    final CostAttachmentStorage storage = mock(CostAttachmentStorage.class);
    final CostAttachmentService service = new CostAttachmentService(costs, files, storage);
    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void caller() {
        UUID user = UUID.randomUUID();
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
                        Set.of("order:logistics-cost:read")));
        when(files.list(tenant.toString(), "C1"))
                .thenReturn(
                        List.of(
                                new LogisticsCostAttachmentView(
                                        "F1",
                                        "回单.pdf",
                                        "application/pdf",
                                        20,
                                        "user",
                                        Instant.EPOCH)));
        when(files.fileKey(tenant.toString(), "C1", "F1"))
                .thenReturn("tenant/order-cost-attachments/file.pdf");
    }

    @AfterEach
    void cleanup() {
        TestAuthorizationContext.clear();
    }

    @Test
    void onlyAuthorizedActivePdfCanReadItsStoredKey() {
        when(storage.read("tenant/order-cost-attachments/file.pdf")).thenReturn(new byte[] {1, 2});
        assertThat(service.previewPdf("C1", "F1")).containsExactly(1, 2);
        verify(costs).entry(tenant.toString(), "LOGISTICS", "C1", "order:logistics-cost:read");
        assertThatThrownBy(() -> service.previewPdf("C1", "unknown")).hasMessageContaining("附件不存在");
        verify(storage, times(1)).read(anyString());
    }

    @Test
    void deniedEntryAndOtherFileTypesCannotReachCos() {
        when(costs.entry(tenant.toString(), "LOGISTICS", "C2", "order:logistics-cost:read"))
                .thenThrow(new AuthorizationDeniedException("order:logistics-cost:read"));
        assertThatThrownBy(() -> service.previewPdf("C2", "F1"))
                .isInstanceOf(AuthorizationDeniedException.class);
        when(files.list(tenant.toString(), "C1"))
                .thenReturn(
                        List.of(
                                new LogisticsCostAttachmentView(
                                        "F1",
                                        "image.png",
                                        "image/png",
                                        20,
                                        "user",
                                        Instant.EPOCH)));
        assertThatThrownBy(() -> service.previewPdf("C1", "F1")).hasMessageContaining("不是PDF");
        verifyNoInteractions(storage);
    }

    @Test
    void downloadReadsOnlyTheFileBelongingToTheAuthorizedRecord() {
        when(storage.read("tenant/order-cost-attachments/file.pdf")).thenReturn(new byte[] {1, 2});
        var file = service.downloadContent("C1", "F1");
        assertThat(file.fileName()).isEqualTo("回单.pdf");
        assertThat(file.bytes()).containsExactly(1, 2);
        assertThatThrownBy(() -> service.downloadContent("C1", "unknown"))
                .hasMessageContaining("附件不存在");
        when(costs.entry(tenant.toString(), "LOGISTICS", "C2", "order:logistics-cost:read"))
                .thenThrow(new AuthorizationDeniedException("order:logistics-cost:read"));
        assertThatThrownBy(() -> service.downloadContent("C2", "F1"))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(storage, times(1)).read(anyString());
    }
}
