package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.LogisticsCostAttachmentView;
import com.rigour.order.application.port.out.*;
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
    private final OrderLogisticsCostStore costs;
    private final CostAttachmentStore files;
    private final CostAttachmentStorage storage;

    public CostAttachmentService(
            OrderLogisticsCostStore costs,
            CostAttachmentStore files,
            CostAttachmentStorage storage) {
        this.costs = costs;
        this.files = files;
        this.storage = storage;
    }

    private String authorize(String category, String entry, String action) {
        var caller = AuthorizationContext.requireCurrent();
        String c = "LOGISTICS";
        String permission = "order:logistics-cost:" + action;
        if (caller.tenantId() == null) throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        String tenant = caller.tenantId().toString();
        costs.entry(tenant, c, entry, permission);
        return tenant;
    }

    public List<LogisticsCostAttachmentView> list(String category, String entry) {
        return files.list(authorize(category, entry, "read"), entry);
    }

    public String url(String category, String entry, String file) {
        String tenant = authorize(category, entry, "read");
        return storage.url(files.fileKey(tenant, entry, file));
    }

    public String downloadUrl(String entry, String id) {
        String tenant = authorize("logistics", entry, "read");
        var file =
                files.list(tenant, entry).stream()
                        .filter(f -> f.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> bad("附件不存在"));
        return storage.downloadUrl(files.fileKey(tenant, entry, id), file.fileName());
    }

    /** 同源读取已授权的 PDF，浏览器无需开放私有桶跨域访问。 */
    public byte[] previewPdf(String entry, String id) {
        String tenant = authorize("logistics", entry, "read");
        var file =
                files.list(tenant, entry).stream()
                        .filter(f -> f.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> bad("附件不存在"));
        if (!"application/pdf".equals(file.contentType())) throw bad("该附件不是PDF文件");
        return storage.read(files.fileKey(tenant, entry, id));
    }

    static String evidenceCategory(String value) {
        if (!Set.of("EXPENSE_VOUCHER", "RECEIPT", "LOADING", "UNLOADING")
                .contains(Objects.toString(value, ""))) throw bad("请选择有效的凭证分类");
        return value;
    }

    @Transactional
    public LogisticsCostAttachmentView uploadEvidence(
            String entry,
            int revision,
            String replacing,
            String evidenceCategory,
            String name,
            String contentType,
            byte[] bytes) {
        return storeFile(
                "logistics",
                entry,
                revision,
                name,
                contentType,
                bytes,
                replacing,
                evidenceCategory(evidenceCategory));
    }

    /** 已由整单保存服务上传的自有对象，仅登记元数据，不在持有租户锁时等待 COS 网络。 */
    LogisticsCostAttachmentView attachUploaded(
            String entry,
            int revision,
            String replacing,
            String category,
            String name,
            String type,
            long size,
            String key) {
        String tenant = authorize("logistics", entry, "write");
        if (!key.startsWith(tenant + "/order-cost-attachments/") || key.contains(".."))
            throw bad("凭证归档位置无效");
        files.lock(tenant);
        var current = costs.entry(tenant, "LOGISTICS", entry, "order:logistics-cost:write");
        if (!"DRAFT".equals(current.statusCode()) || current.revision() != revision)
            throw bad("记录已变化或不是待确认，请刷新");
        var existing = files.list(tenant, entry);
        if (replacing == null && existing.size() >= 20) throw bad("每条记录最多20个附件");
        var original =
                replacing == null
                        ? null
                        : existing.stream()
                                .filter(f -> f.id().equals(replacing))
                                .findFirst()
                                .orElseThrow(() -> bad("原附件不存在或已更换，请刷新"));
        String actor = AuthorizationContext.requireCurrent().principalId().toString();
        var file =
                new LogisticsCostAttachmentView(
                        UUID.randomUUID().toString(),
                        name,
                        type,
                        size,
                        actor,
                        Instant.now(),
                        evidenceCategory(category));
        if (original != null) files.retire(tenant, entry, original.id());
        files.add(tenant, entry, revision, file, key);
        if (original == null)
            costs.attachmentAdded(
                    tenant, "LOGISTICS", entry, actor, file.id(), "order:logistics-cost:write");
        else
            costs.attachmentChanged(
                    tenant,
                    "LOGISTICS",
                    entry,
                    actor,
                    "ATTACHMENT_REPLACED",
                    original.fileName()
                            + "（"
                            + original.id()
                            + "） → "
                            + name
                            + "（"
                            + file.id()
                            + "）",
                    "order:logistics-cost:write");
        return file;
    }

    @Transactional
    public LogisticsCostAttachmentView upload(
            String category,
            String entry,
            int revision,
            String name,
            String contentType,
            byte[] bytes) {
        return storeFile(category, entry, revision, name, contentType, bytes, null);
    }

    @Transactional
    public LogisticsCostAttachmentView replace(
            String category,
            String entry,
            String replacing,
            int revision,
            String name,
            String contentType,
            byte[] bytes) {
        return storeFile(category, entry, revision, name, contentType, bytes, replacing);
    }

    private LogisticsCostAttachmentView storeFile(
            String category,
            String entry,
            int revision,
            String name,
            String contentType,
            byte[] bytes,
            String replacing) {
        return storeFile(
                category, entry, revision, name, contentType, bytes, replacing, "EXPENSE_VOUCHER");
    }

    private LogisticsCostAttachmentView storeFile(
            String category,
            String entry,
            int revision,
            String name,
            String contentType,
            byte[] bytes,
            String replacing,
            String evidenceCategory) {
        String tenant = authorize(category, entry, "write");
        files.lock(tenant);
        String c = "LOGISTICS";
        String action = "write";
        String permission = "order:logistics-cost:" + action;
        var current = costs.entry(tenant, c, entry, permission);
        if (!"DRAFT".equals(current.statusCode()) || current.revision() != revision)
            throw new BusinessException(ErrorCode.CONFLICT, "请刷新记录，仅待确认记录可以上传附件", List.of());
        if (replacing == null && files.list(tenant, entry).size() >= 20) throw bad("每条记录最多20个附件");
        if (name == null
                || name.isBlank()
                || name.length() > 200
                || name.chars().anyMatch(ch -> ch < 32)) throw bad("附件名称无效");
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024)
            throw bad("附件须为1字节至10MB");
        validateEvidence(name, contentType, bytes);
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
                new LogisticsCostAttachmentView(
                        UUID.randomUUID().toString(),
                        name,
                        contentType,
                        bytes.length,
                        AuthorizationContext.requireCurrent().principalId().toString(),
                        Instant.now(),
                        evidenceCategory);
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
        var current = costs.entry(tenant, "LOGISTICS", entry, "order:logistics-cost:write");
        if (!"DRAFT".equals(current.statusCode()) || current.revision() != revision)
            throw bad("只有待确认费用可修改附件，请刷新");
        if (files.list(tenant, entry).size() <= 1) throw bad("至少保留一份业务凭证；更换最后一份凭证请使用更换操作");
        removeWithinSubmission(category, entry, id, revision);
    }

    void removeWithinSubmission(String category, String entry, String id, int revision) {
        String tenant = authorize(category, entry, "write");
        files.lock(tenant);
        String c = "LOGISTICS", permission = "order:logistics-cost:write";
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

    static void validateEvidence(String name, String type, byte[] bytes) {
        if (name == null
                || name.isBlank()
                || name.length() > 200
                || name.chars().anyMatch(c -> c < 32)) throw bad("附件名称无效");
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024)
            throw bad("附件须为1字节至10MB");
        boolean office =
                switch (Objects.toString(type, "")) {
                    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                            officeContent(bytes, "word/document.xml");
                    case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                            officeContent(bytes, "xl/workbook.xml");
                    default -> false;
                };
        if (!validContent(type, bytes) && !office)
            throw bad("请选择有效的 PNG、JPEG、PDF、Word（docx）或 Excel（xlsx）文件");
    }

    private static boolean officeContent(byte[] bytes, String document) {
        boolean types = false, body = false;
        int count = 0;
        long expanded = 0;
        // 验证实际 Office 包结构并限制展开大小，不能仅靠扩展名或 ZIP 文件头判断。
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++count > 2000) return false;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > 50L * 1024 * 1024) return false;
                }
                types |= "[Content_Types].xml".equals(entry.getName());
                body |= document.equals(entry.getName());
            }
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
        return types && body;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
