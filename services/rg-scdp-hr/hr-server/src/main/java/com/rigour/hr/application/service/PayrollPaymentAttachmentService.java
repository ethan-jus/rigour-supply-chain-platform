package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Evidence supplements an existing payment registration; it never executes or changes a payment. */
@Service
public class PayrollPaymentAttachmentService {
    public static final int MAX_FILE_SIZE = 10 * 1024 * 1024;
    private final HrPayrollStore payroll;
    private final PayrollPaymentAttachmentStore files;
    private final PayrollPaymentAttachmentStorage storage;
    private final HrAuditActorNameResolver names;

    public PayrollPaymentAttachmentService(HrPayrollStore payroll, PayrollPaymentAttachmentStore files,
            PayrollPaymentAttachmentStorage storage, HrAuditActorNameResolver names) {
        this.payroll = payroll;
        this.files = files;
        this.storage = storage;
        this.names = names;
    }

    private record Authorized(String tenant, String actor, PayrollDetailView payroll) {}
    public record Content(PayrollPaymentAttachmentView metadata, byte[] bytes) {}

    private Authorized authorize(String payrollId, String paymentId, String action) {
        String permission = "hr:payroll:" + action;
        var caller = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(caller.principalScope()) || caller.tenantId() == null || caller.userId() == null)
            throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        String tenant = caller.tenantId().toString();
        var current = payroll.detail(tenant, payrollId, permission);
        if (current.payments().stream().noneMatch(p -> p.id().equals(paymentId)))
            throw new BusinessException(ErrorCode.NOT_FOUND, "发放登记不存在", List.of());
        return new Authorized(tenant, caller.principalId().toString(), current);
    }

    public List<PayrollPaymentAttachmentView> list(String payrollId, String paymentId) {
        var actor = authorize(payrollId, paymentId, "read");
        return files.list(actor.tenant(), payrollId, paymentId);
    }

    public Content content(String payrollId, String paymentId, String attachmentId) {
        var actor = authorize(payrollId, paymentId, "read");
        var file = files.file(actor.tenant(), payrollId, paymentId, attachmentId);
        byte[] bytes = storage.read(file.fileKey());
        if (bytes == null || bytes.length != file.metadata().size()
                || bytes.length > MAX_FILE_SIZE || !validContent(file.metadata().contentType(), bytes))
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "发放凭证内容不完整，请重新上传或联系管理员", List.of());
        return new Content(file.metadata(), bytes);
    }

    public PayrollPaymentAttachmentLinkView downloadLink(String payrollId, String paymentId, String attachmentId) {
        var actor = authorize(payrollId, paymentId, "read");
        var file = files.file(actor.tenant(), payrollId, paymentId, attachmentId);
        Instant expiresAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .plus(java.time.Duration.ofDays(7));
        String url = storage.signedDownload(file.fileKey(), file.metadata().fileName(),
                file.metadata().contentType(), expiresAt);
        return new PayrollPaymentAttachmentLinkView(url, expiresAt);
    }

    @Transactional
    public PayrollPaymentAttachmentView upload(String payrollId, String paymentId, int revision,
            String name, String contentType, byte[] bytes) {
        var actor = authorize(payrollId, paymentId, "paid");
        var locked = files.lock(actor.tenant(), payrollId);
        actor = authorize(payrollId, paymentId, "paid");
        if (!Set.of("CONFIRMED", "PAID").contains(locked.statusCode()))
            throw conflict("当前工资不允许补充发放凭证，请刷新后重试");
        if (name == null || name.isBlank() || name.length() > 200 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(character -> character < 32)) throw bad("凭证文件名称无效");
        String type = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_SIZE) throw bad("凭证文件须为1字节至10MB");
        if (!validContent(type, bytes)) throw bad("仅支持内容匹配的 PNG、JPEG 或 PDF 凭证");
        String id = attachmentId(actor.tenant(), payrollId, paymentId, name, type, bytes);
        var existing = files.listForUpdate(actor.tenant(), payrollId, paymentId);
        var duplicate = existing.stream().filter(file -> file.id().equals(id)).findFirst();
        if (duplicate.isPresent()) return duplicate.get();
        if (revision < 1 || locked.revision() != revision) throw conflict("工资单已更新，请刷新后上传凭证");
        if (existing.size() >= 20) throw bad("每笔发放登记最多20个凭证文件");
        var file = new PayrollPaymentAttachmentView(id, name, type, bytes.length, actor.actor(),
                names.resolve(actor.tenant(), actor.actor()),
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        String key = storage.upload(actor.tenant(), payrollId, paymentId, type, bytes);
        boolean synchronizedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) discard(key);
            }
        });
        try {
            files.add(actor.tenant(), payrollId, paymentId, file, key);
        } catch (RuntimeException | Error failure) {
            if (!synchronizedTransaction) discard(key);
            throw failure;
        }
        return file;
    }

    private void discard(String key) {
        try { storage.discard(key); }
        catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("薪酬凭证回滚清理失败 key={} reason={}",
                    key, failure.getClass().getSimpleName());
        }
    }

    static String attachmentId(String tenant, String payroll, String payment, String name, String type, byte[] bytes) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String part : List.of(tenant, payroll, payment, name, type)) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            digest.update(bytes);
            return UUID.nameUUIDFromBytes(digest.digest()).toString();
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    static boolean validContent(String type, byte[] bytes) {
        if (bytes == null || bytes.length < 8) return false;
        return "image/png".equals(type)
                ? Arrays.equals(Arrays.copyOf(bytes, 8), new byte[] {(byte)137,80,78,71,13,10,26,10})
                : "image/jpeg".equals(type)
                        ? bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255
                        : "application/pdf".equals(type) && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-");
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of()); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message, List.of()); }
}
