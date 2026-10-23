package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.util.*;

class HrExpenseAttachmentTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final HrExpenseStore costs = mock(HrExpenseStore.class);
    final CostAttachmentStore files = mock(CostAttachmentStore.class);
    final CostAttachmentStorage storage = mock(CostAttachmentStorage.class);
    final CostAttachmentService service = new CostAttachmentService(costs, files, storage);

    void actor(String scope, Set<String> permissions) {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        scope,
                        user,
                        tenant,
                        "TENANT".equals(scope) ? user : null,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        "TENANT".equals(scope) ? 1 : 0,
                        Set.of(),
                        permissions));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void readPermissionAndExpenseOwnershipAreRequiredBeforeSigning() {
        actor("TENANT", Set.of("hr:expense:read"));
        when(files.fileKey(tenant.toString(), "expense", "file"))
                .thenReturn("tenant/hr-expense-attachments/expense/file.pdf");
        when(storage.url(anyString())).thenReturn("signed-url");
        assertThat(service.url("expense", "file")).isEqualTo("signed-url");
        var order = inOrder(costs, files, storage);
        order.verify(costs).expenseDetail(tenant.toString(), "expense", "hr:expense:read");
        order.verify(files).fileKey(tenant.toString(), "expense", "file");
        order.verify(storage).url(anyString());
        doThrow(new AuthorizationDeniedException("hr:expense:read"))
                .when(costs)
                .expenseDetail(tenant.toString(), "hidden", "hr:expense:read");
        assertThatThrownBy(() -> service.url("hidden", "file"))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(files, never()).fileKey(tenant.toString(), "hidden", "file");
    }

    @Test
    void serviceIdentityAndUnrelatedPermissionCannotReadHrFiles() {
        actor("SERVICE", Set.of("*:*:*"));
        assertThatThrownBy(() -> service.list("expense"))
                .isInstanceOf(AuthorizationDeniedException.class);
        actor("TENANT", Set.of("erp:cost:rental:read"));
        assertThatThrownBy(() -> service.list("expense"))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(costs, files, storage);
    }

    @Test
    void fileContentMustMatchDeclaredType() {
        assertThat(
                        CostAttachmentService.validContent(
                                "application/pdf",
                                "%PDF-1.7 test".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(
                        CostAttachmentService.validContent(
                                "image/png",
                                "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(
                        CostAttachmentService.validContent(
                                "text/html",
                                "<script>alert(1)</script>"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isFalse();
    }
}
