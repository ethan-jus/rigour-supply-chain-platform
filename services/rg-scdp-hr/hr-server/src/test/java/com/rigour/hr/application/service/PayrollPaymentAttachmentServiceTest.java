package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class PayrollPaymentAttachmentServiceTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final String payrollId = UUID.randomUUID().toString(), paymentId = UUID.randomUUID().toString();
    final HrPayrollStore payroll = mock(HrPayrollStore.class);
    final PayrollPaymentAttachmentStore files = mock(PayrollPaymentAttachmentStore.class);
    final PayrollPaymentAttachmentStorage storage = mock(PayrollPaymentAttachmentStorage.class);
    final HrAuditActorNameResolver names = mock(HrAuditActorNameResolver.class);
    final PayrollDetailView current = mock(PayrollDetailView.class);
    final List<PayrollPaymentAttachmentView> existing = new ArrayList<>();
    final byte[] pdf = "%PDF-1.7\nregistered payment evidence".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    final PayrollPaymentAttachmentService service = new PayrollPaymentAttachmentService(payroll, files, storage, names);

    @BeforeEach
    void setup() {
        permissions("hr:payroll:paid", "hr:payroll:read");
        when(payroll.detail(eq(tenant.toString()), eq(payrollId), anyString())).thenReturn(current);
        when(current.statusCode()).thenReturn("PAID");
        when(current.revision()).thenReturn(4);
        when(current.payments()).thenReturn(List.of(new PayrollPaymentView(paymentId, "request", LocalDate.of(2026,9,30),
                new BigDecimal("5000"), "BANK-1", "已登记", Instant.EPOCH)));
        when(files.listForUpdate(tenant.toString(), payrollId, paymentId)).thenReturn(existing);
        when(files.lock(tenant.toString(),payrollId)).thenReturn(new PayrollPaymentAttachmentStore.LockedPayroll(4,"PAID"));
        when(names.resolve(tenant.toString(), user.toString())).thenReturn("李四");
        when(storage.upload(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("private/payroll/key");
        doAnswer(invocation -> { existing.add(invocation.getArgument(3)); return null; })
                .when(files).add(anyString(), anyString(), anyString(), any(), anyString());
    }

    void permissions(String... permissions) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(),
                1,1,1,Set.of(),Set.of(permissions)));
    }

    @AfterEach
    void close() {
        TestAuthorizationContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    PayrollPaymentAttachmentView upload(int revision, String name, String type, byte[] bytes) {
        return service.upload(payrollId, paymentId, revision, name, type, bytes);
    }

    @Test
    void currentLoginOwnsEvidenceAndRetryDoesNotDuplicateObjectOrWageChange() {
        var first = upload(4, "银行回单.pdf", "application/pdf", pdf);
        assertThat(upload(1, "银行回单.pdf", "application/pdf", pdf)).isEqualTo(first);
        assertThat(first.createdBy()).isEqualTo(user.toString());
        assertThat(first.createdByName()).isEqualTo("李四");
        assertThat(existing).containsExactly(first);
        verify(storage, times(1)).upload(tenant.toString(), payrollId, paymentId, "application/pdf", pdf);
        verify(files, times(1)).add(eq(tenant.toString()), eq(payrollId), eq(paymentId), eq(first), anyString());
        verify(payroll, never()).pay(anyString(), anyString(), any(), anyString());
    }

    @Test
    void bothKindsOfImageAndPdfRequireMatchingMagic() {
        upload(4, "回单.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10,1});
        upload(4, "回单.jpg", "image/jpeg", new byte[]{(byte)255,(byte)216,(byte)255,1,2,3,4,5});
        for (String type : List.of("image/png", "image/jpeg", "text/html"))
            assertThatThrownBy(() -> upload(4, "伪造图片.png", type, pdf)).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("内容匹配");
        assertThatThrownBy(() -> upload(4,"回单.pdf","application/pdf",new byte[0])).hasMessageContaining("10MB");
        assertThatThrownBy(() -> upload(4,"回单.pdf","application/pdf",new byte[10*1024*1024+1])).hasMessageContaining("10MB");
        assertThatThrownBy(() -> upload(4,"../回单.pdf","application/pdf",pdf)).hasMessageContaining("名称");
    }

    @Test
    void staleRevisionAndUnregisteredPaymentCannotUploadAnObject() {
        assertThatThrownBy(() -> upload(3,"回单.pdf","application/pdf",pdf)).hasMessageContaining("已更新");
        assertThatThrownBy(() -> service.upload(payrollId,"other-payment",4,"回单.pdf","application/pdf",pdf))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(storage);
    }

    @Test
    void fullPaymentAllowsDuplicateRetryButNotTwentyFirstFile() {
        var first=upload(4,"回单.pdf","application/pdf",pdf);
        for(int i=1;i<20;i++) existing.add(new PayrollPaymentAttachmentView("file-"+i,"其他回单.pdf","application/pdf",10,user.toString(),"李四",Instant.EPOCH));
        assertThat(upload(4,"回单.pdf","application/pdf",pdf)).isEqualTo(first);
        assertThatThrownBy(() -> upload(4,"额外回单.pdf","application/pdf",pdf)).hasMessageContaining("最多20");
        verify(storage,times(1)).upload(anyString(),anyString(),anyString(),anyString(),any());
    }

    @Test
    void readPermissionDoesNotGrantUploadAndPaidPermissionDoesNotGrantRead() {
        permissions("hr:payroll:read");
        assertThatThrownBy(() -> upload(4,"回单.pdf","application/pdf",pdf)).isInstanceOf(AuthorizationDeniedException.class);
        permissions("hr:payroll:paid");
        assertThatThrownBy(() -> service.list(payrollId,paymentId)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.content(payrollId,paymentId,"file")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.downloadLink(payrollId,paymentId,"file")).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void employeeScopeAndPaymentOwnershipAreCheckedBeforeMetadataOrBlobRead() {
        doThrow(new AuthorizationDeniedException("hr:payroll:read"))
                .when(payroll).detail(tenant.toString(),payrollId,"hr:payroll:read");
        assertThatThrownBy(() -> service.content(payrollId,paymentId,"file")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.downloadLink(payrollId,paymentId,"file")).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(files,storage);
    }

    @Test
    void contentUsesPersistedPrivateKeyAndNeverTreatsPdfAsImage() {
        var metadata = new PayrollPaymentAttachmentView("file","回单.pdf","application/pdf",pdf.length,user.toString(),"李四",Instant.EPOCH);
        when(files.file(tenant.toString(),payrollId,paymentId,"file"))
                .thenReturn(new PayrollPaymentAttachmentStore.Stored(metadata,"private/payroll/key"));
        when(storage.read("private/payroll/key")).thenReturn(pdf);
        var content = service.content(payrollId,paymentId,"file");
        assertThat(content.metadata().contentType()).isEqualTo("application/pdf");
        assertThat(content.bytes()).containsExactly(pdf);
        when(storage.read("private/payroll/key")).thenReturn(new byte[]{1,2,3});
        assertThatThrownBy(() -> service.content(payrollId,paymentId,"file")).hasMessageContaining("内容不完整");
    }

    @Test
    void exportLinkUsesScopedPersistedKeyAndSevenDaysWithoutReadingOrChangingWages() {
        var metadata = new PayrollPaymentAttachmentView("file","回单.pdf","application/pdf",pdf.length,user.toString(),"李四",Instant.EPOCH);
        when(files.file(tenant.toString(),payrollId,paymentId,"file"))
                .thenReturn(new PayrollPaymentAttachmentStore.Stored(metadata,"private/payroll/key"));
        when(storage.signedDownload(eq("private/payroll/key"),eq("回单.pdf"),eq("application/pdf"),any()))
                .thenReturn("https://bucket.cos.example/receipt?signature=example");
        Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var link = service.downloadLink(payrollId,paymentId,"file");
        assertThat(link.expiresAt()).isBetween(before.plus(Duration.ofDays(7)), Instant.now().plus(Duration.ofDays(7)));
        assertThat(link.url()).startsWith("https://");
        verify(storage).signedDownload("private/payroll/key","回单.pdf","application/pdf",link.expiresAt());
        verify(storage,never()).read(anyString());
        verifyNoMoreInteractions(storage);
        assertThatThrownBy(() -> service.downloadLink(payrollId,"other-payment","file"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
    }

    @Test
    void failedMetadataSaveDeletesObjectAndTransactionRollbackAlsoDeletesObject() {
        doThrow(new BusinessException(ErrorCode.CONFLICT,"写入失败",List.of()))
                .when(files).add(anyString(),anyString(),anyString(),any(),anyString());
        assertThatThrownBy(() -> upload(4,"回单.pdf","application/pdf",pdf)).hasMessage("写入失败");
        verify(storage).discard("private/payroll/key");
        clearInvocations(storage);
        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(() -> upload(4,"回单.pdf","application/pdf",pdf)).hasMessage("写入失败");
        verify(storage,never()).discard(anyString());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(storage).discard("private/payroll/key");
    }

    @Test
    void duplicateIdentityIncludesTenantPayrollPaymentAndContent() {
        String id=PayrollPaymentAttachmentService.attachmentId("t","p","payment","回单.pdf","application/pdf",pdf);
        assertThat(PayrollPaymentAttachmentService.attachmentId("other","p","payment","回单.pdf","application/pdf",pdf)).isNotEqualTo(id);
        assertThat(PayrollPaymentAttachmentService.attachmentId("t","other","payment","回单.pdf","application/pdf",pdf)).isNotEqualTo(id);
        assertThat(PayrollPaymentAttachmentService.attachmentId("t","p","other","回单.pdf","application/pdf",pdf)).isNotEqualTo(id);
    }
}
