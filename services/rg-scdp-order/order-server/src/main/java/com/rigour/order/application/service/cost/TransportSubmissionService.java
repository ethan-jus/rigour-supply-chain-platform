package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.TransportRecordModels.Detail;
import com.rigour.order.application.port.out.CostAttachmentStorage;
import com.rigour.order.application.port.out.CostAttachmentStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** 单据、关联和凭证元数据整体提交；COS 新对象由附件服务在事务回滚时清理。 */
@Service
public class TransportSubmissionService {
    public record Content(String name, String type, byte[] bytes) {}

    private final TransportRecordService transports;
    private final OrderLogisticsCostService costs;
    private final CostAttachmentService evidence;
    private final CostAttachmentStore files;
    private final JsonMapper json;
    private final CostAttachmentStorage storage;
    private final TransactionTemplate transaction;
    private SalesLogisticsService salesLogistics;

    @org.springframework.beans.factory.annotation.Autowired
    void salesLogistics(SalesLogisticsService service) {
        this.salesLogistics = service;
    }

    public TransportSubmissionService(
            TransportRecordService transports,
            OrderLogisticsCostService costs,
            CostAttachmentService evidence,
            CostAttachmentStore files,
            JsonMapper json,
            CostAttachmentStorage storage,
            PlatformTransactionManager manager) {
        this.transports = transports;
        this.costs = costs;
        this.evidence = evidence;
        this.files = files;
        this.json = json;
        this.storage = storage;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Detail save(String id, TransportSubmission command, List<Content> contents) {
        var caller = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission("order:logistics-cost:write");
        if (caller.tenantId() == null) throw bad("缺少企业上下文");
        if (command == null || command.draft() == null) throw bad("请填写运输信息");
        if (command.salesOrderId() != null) {
            if (id != null || command.advanced() != null) throw bad("销售侧新增物流参数无效");
            salesLogistics.validateManual(command.salesOrderId(), command.draft());
        }
        try {
            UUID.fromString(command.submissionId());
        } catch (Exception e) {
            throw bad("保存标识无效，请重新打开页面");
        }
        var uploads =
                command.uploads() == null
                        ? List.<TransportSubmission.Upload>of()
                        : command.uploads();
        var removed = command.removedIds() == null ? List.<String>of() : command.removedIds();
        if (contents == null
                || uploads.size() != contents.size()
                || uploads.size() > 20
                || removed.size() > 20) throw bad("附件信息不完整或超出20份，请重新选择");
        var changedIds = new HashSet<String>();
        for (int i = 0; i < uploads.size(); i++) {
            var upload = uploads.get(i);
            if (upload == null) throw bad("凭证分类不能为空");
            CostAttachmentService.evidenceCategory(upload.categoryCode());
            var file = contents.get(i);
            if (file == null) throw bad("附件读取失败，请重新选择");
            CostAttachmentService.validateEvidence(file.name(), file.type(), file.bytes());
            if (Set.of("LOADING", "UNLOADING").contains(upload.categoryCode())
                    && !file.type().startsWith("image/")) throw bad("装卸货照片请选择PNG或JPG图片");
            if (upload.replacingId() != null && !changedIds.add(upload.replacingId()))
                throw bad("附件不能重复更换");
        }
        for (var removedId : removed)
            if (removedId == null || !changedIds.add(removedId)) throw bad("附件不能重复移除或同时更换");
        String tenant = caller.tenantId().toString();
        String hash = fingerprint(id, command, contents);
        var previous = files.submission(tenant, command.submissionId());
        if (previous != null) return repeated(id, hash, previous);
        checkFinalSet(id, command, uploads, changedIds);
        try {
            if (id == null) UUID.fromString(command.draft().requestId());
        } catch (Exception e) {
            throw bad("单据保存标识无效，请重新打开页面");
        }
        var keys = new ArrayList<String>();
        var completion = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
        var used = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            // 对象先归档，随后短事务登记单据与元数据，避免大文件阻塞同企业同步和保存。
            for (var file : contents)
                keys.add(
                        storage.upload(
                                tenant,
                                id == null ? "request_" + command.draft().requestId() : id,
                                file.type(),
                                file.bytes()));
            return transaction.execute(
                    status -> {
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCompletion(int state) {
                                        completion.set(state);
                                    }
                                });
                        files.lock(tenant);
                        var duplicate = files.submission(tenant, command.submissionId());
                        if (duplicate != null) return repeated(id, hash, duplicate);
                        used.set(true);
                        return persist(
                                id,
                                command,
                                contents,
                                uploads,
                                removed,
                                changedIds,
                                tenant,
                                hash,
                                keys);
                    });
        } finally {
            if (!used.get() || completion.get() == TransactionSynchronization.STATUS_ROLLED_BACK)
                keys.forEach(storage::discard);
            else if (completion.get() != TransactionSynchronization.STATUS_COMMITTED)
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn(
                                "运输凭证保存结果待核对 tenant={} submission={}; 保留COS对象以支持安全重试",
                                tenant,
                                command.submissionId());
        }
    }

    private Detail repeated(String id, String hash, CostAttachmentStore.Submission previous) {
        var current = transports.detail(previous.entryId());
        if (!previous.fingerprint().equals(hash)
                || id != null && !id.equals(previous.entryId())
                || current.record().revision() != previous.revision())
            throw bad("本次保存内容或记录已变化，请刷新后核对，不能重复覆盖");
        return current;
    }

    private boolean checkFinalSet(
            String id,
            TransportSubmission command,
            List<TransportSubmission.Upload> uploads,
            Set<String> changedIds) {
        var old = id == null ? null : transports.detail(id);
        if (old != null
                && (!"ACTIVE".equals(old.record().statusCode())
                        || !Objects.equals(old.record().revision(), command.draft().revision())))
            throw bad("记录已变化或不是有效记录，请刷新");
        boolean manual = old == null || old.platform() == null;
        var existing =
                id == null
                        ? List.<LogisticsCostAttachmentView>of()
                        : evidence.list("logistics", id);
        var existingIds =
                new HashSet<>(existing.stream().map(LogisticsCostAttachmentView::id).toList());
        if (!existingIds.containsAll(changedIds)) throw bad("部分附件不存在或已被更改，请刷新");
        int finalCount = existing.size() - changedIds.size() + uploads.size();
        if (manual && finalCount < 1) throw bad("手工运输记录至少上传一份业务凭证");
        if (finalCount > 20) throw bad("每条记录最多20个附件");
        return manual;
    }

    private Detail persist(
            String id,
            TransportSubmission command,
            List<Content> contents,
            List<TransportSubmission.Upload> uploads,
            List<String> removed,
            Set<String> changedIds,
            String tenant,
            String hash,
            List<String> keys) {
        boolean manual = checkFinalSet(id, command, uploads, changedIds);
        // COS上传期间销售单可能被取消；事务内再次核验，失败时一并回滚单据与关联。
        if (command.salesOrderId() != null)
            salesLogistics.validateManual(command.salesOrderId(), command.draft());
        var old = id == null ? null : transports.detail(id);
        Detail saved;
        if (command.advanced() != null) {
            if (old == null
                    || !Objects.equals(command.advanced().revision(), command.draft().revision()))
                throw bad("费用明细编辑信息无效，请重新打开记录");
            saved = transports.detail(costs.save("logistics", id, command.advanced()).id());
        } else saved = transports.saveWithPendingEvidence(id, command.draft());
        // 新增请求编号已落过单时，必须通过原保存回执重试；新回执不能再次追加整批凭证。
        if (id == null && !files.list(tenant, saved.record().id()).isEmpty())
            throw bad("此运输记录已经保存，请从列表打开后编辑，不能重复新增凭证");
        // 先移除已明确选中的旧附件，最终集合已校验；任一新对象上传失败则整单回滚。
        for (String removedId : removed) {
            evidence.removeWithinSubmission(
                    "logistics", saved.record().id(), removedId, saved.record().revision());
            saved = transports.detail(saved.record().id());
        }
        for (int i = 0; i < uploads.size(); i++) {
            var file = contents.get(i);
            var upload = uploads.get(i);
            evidence.attachUploaded(
                    saved.record().id(),
                    saved.record().revision(),
                    upload.replacingId(),
                    upload.categoryCode(),
                    file.name(),
                    file.type(),
                    file.bytes().length,
                    keys.get(i));
            saved = transports.detail(saved.record().id());
        }
        if (manual && evidence.list("logistics", saved.record().id()).isEmpty())
            throw bad("凭证尚未上传成功，请重试保存");
        files.completed(
                tenant,
                saved.record().id(),
                command.submissionId(),
                hash,
                saved.record().revision());
        return saved;
    }

    private String fingerprint(String id, TransportSubmission command, List<Content> contents) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var values = new ArrayList<Object>();
            values.add(Objects.toString(id, ""));
            values.add(command);
            for (var file : contents)
                values.add(
                        List.of(
                                file.name(),
                                file.type(),
                                HexFormat.of().formatHex(digest.digest(file.bytes()))));
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(values)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
