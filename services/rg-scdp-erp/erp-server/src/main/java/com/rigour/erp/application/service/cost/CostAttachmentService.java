package com.rigour.erp.application.service.cost;

import com.rigour.erp.api.v1.model.ErpCostAttachmentView;
import com.rigour.erp.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;

@Service
public class CostAttachmentService {
    private final ErpCostStore costs;
    private final CostAttachmentStore files;
    private final CostAttachmentStorage storage;

    public CostAttachmentService(
            ErpCostStore costs, CostAttachmentStore files, CostAttachmentStorage storage) {
        this.costs = costs;
        this.files = files;
        this.storage = storage;
    }

    private String authorize(String category, String entry, String action) {
        var caller = AuthorizationContext.requireCurrent();
        String c = ErpCostService.category(category);
        String permission = ErpCostService.permission(c, action);
        if (caller.tenantId() == null) throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        String tenant = caller.tenantId().toString();
        costs.entry(tenant, c, entry, permission);
        return tenant;
    }

    public List<ErpCostAttachmentView> list(String category, String entry) {
        return files.list(authorize(category, entry, "read"), entry);
    }

    public String url(String category, String entry, String file) {
        String tenant = authorize(category, entry, "read");
        return storage.url(files.fileKey(tenant, entry, file));
    }

    @Transactional
    public ErpCostAttachmentView upload(
            String category,
            String entry,
            int revision,
            String name,
            String contentType,
            byte[] bytes) {
        return storeFile(category, entry, revision, name, contentType, bytes, null);
    }

    @Transactional
    public ErpCostAttachmentView replace(
            String category,
            String entry,
            String replacing,
            int revision,
            String name,
            String contentType,
            byte[] bytes) {
        return storeFile(category, entry, revision, name, contentType, bytes, replacing);
    }

    private ErpCostAttachmentView storeFile(
            String category,
            String entry,
            int revision,
            String name,
            String contentType,
            byte[] bytes,
            String replacing) {
        String tenant = authorize(category, entry, "write");
        files.lock(tenant);
        String c = ErpCostService.category(category);
        String action = "write";
        String permission = ErpCostService.permission(c, action);
        var current = costs.entry(tenant, c, entry, permission);
        if (!"DRAFT".equals(current.statusCode()))
            throw new BusinessException(ErrorCode.CONFLICT, "请刷新记录，仅待确认记录可以上传附件", List.of());
        if (name == null
                || name.isBlank()
                || name.length() > 200
                || name.chars().anyMatch(ch -> ch < 32)) throw bad("附件名称无效");
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024)
            throw bad("附件须为1字节至10MB");
        if (!validContent(contentType, bytes)) throw bad("仅支持内容匹配的 PNG、JPEG 或 PDF 文件");
        // 同一单据、文件名及内容的重试复用已有附件；不因响应丢失重复写对象或增加版本。
        String attachmentId =
                attachmentId(
                        tenant, entry, name + ":" + revision + ":" + replacing, contentType, bytes);
        var existing = files.list(tenant, entry);
        var duplicate =
                existing.stream().filter(file -> file.id().equals(attachmentId)).findFirst();
        if (duplicate.isPresent()) return duplicate.get();
        if (current.revision() != revision)
            throw new BusinessException(ErrorCode.CONFLICT, "记录已更新，请刷新后上传附件", List.of());
        if (replacing == null && existing.size() >= 20) throw bad("每条记录最多20个附件");
        var original =
                replacing == null
                        ? null
                        : files.list(tenant, entry).stream()
                                .filter(file -> file.id().equals(replacing))
                                .findFirst()
                                .orElseThrow(() -> bad("原附件不存在或已更换，请刷新"));
        String key = storage.upload(tenant, entry, contentType, bytes);
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) storage.discard(key);
                        }
                    });
        var file =
                new ErpCostAttachmentView(
                        attachmentId,
                        name,
                        contentType,
                        bytes.length,
                        AuthorizationContext.requireCurrent().principalId().toString(),
                        Instant.now());
        if (original != null) files.retire(tenant, entry, original.id());
        files.add(tenant, entry, revision, file, key);
        if (original == null)
            costs.attachmentAdded(tenant, c, entry, file.createdBy(), file.id(), permission);
        else
            costs.attachmentChanged(
                    tenant,
                    c,
                    entry,
                    file.createdBy(),
                    "ATTACHMENT_REPLACED",
                    original.fileName()
                            + "（"
                            + original.id()
                            + "） → "
                            + file.fileName()
                            + "（"
                            + file.id()
                            + "）",
                    permission);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        org.slf4j.LoggerFactory.getLogger(CostAttachmentService.class)
                                .info(
                                        "cost_attachment_added tenant={} entry={} attachment={}"
                                                + " revision={} actor={}",
                                        tenant,
                                        entry,
                                        file.id(),
                                        revision + 1,
                                        file.createdBy());
                    }
                });
        return file;
    }

    @Transactional
    public void remove(String category, String entry, String id, int revision) {
        String tenant = authorize(category, entry, "write");
        files.lock(tenant);
        String c = ErpCostService.category(category),
                permission = ErpCostService.permission(c, "write");
        var current = costs.entry(tenant, c, entry, permission);
        if (!"DRAFT".equals(current.statusCode()) || current.revision() != revision)
            throw new BusinessException(ErrorCode.CONFLICT, "只有待确认费用可修改附件，请刷新", List.of());
        var original =
                files.list(tenant, entry).stream()
                        .filter(file -> file.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> bad("附件不存在或已移除"));
        String actor = AuthorizationContext.requireCurrent().principalId().toString();
        files.touch(tenant, entry, revision, actor);
        files.retire(tenant, entry, id);
        costs.attachmentChanged(
                tenant,
                c,
                entry,
                actor,
                "ATTACHMENT_REMOVED",
                original.fileName() + "（" + id + "）",
                permission);
    }

    static String attachmentId(
            String tenant, String entry, String name, String type, byte[] bytes) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String part : List.of(tenant, entry, name, type)) {
                digest.update(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            digest.update(bytes);
            return UUID.nameUUIDFromBytes(digest.digest()).toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean validContent(String type, byte[] b) {
        if (b.length < 8) return false;
        return "image/png".equals(type)
                ? Arrays.equals(
                        Arrays.copyOf(b, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})
                : "image/jpeg".equals(type)
                        ? b[0] == (byte) 255 && b[1] == (byte) 216 && b[2] == (byte) 255
                        : "application/pdf".equals(type)
                                && new String(b, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                                        .equals("%PDF-");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
