package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinDeletionRepository.DeletionCandidateRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinDeletionRepository.DeletionJobRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SubmissionDeletionFailure;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SubmissionDeletionRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SubmissionDeletionView;
import com.rigour.shared.file.FileStorage;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/**
 * 小批量测试记录物理删除。数据库任务先锁定记录，COS 删除在事务外执行；
 * 失败记录保留为 FAILED，可用新的 requestId 再次提交，成功记录不可恢复。
 */
@Service
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
final class TemporaryCheckinDeletionService {

    private static final String CONFIRMATION = "DELETE_SELECTED_SUBMISSIONS";
    private static final int MAX_BATCH_SIZE = 20;

    private final TemporaryCheckinDeletionRepository repository;
    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final UUID tenantId;

    TemporaryCheckinDeletionService(
            TemporaryCheckinDeletionRepository repository,
            FileStorage fileStorage,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            Clock clock,
            TemporaryCheckinProperties properties) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.clock = clock;
        this.tenantId = properties.requireTenantId();
    }

    SubmissionDeletionView delete(
            String requestedBy, String scopeCity, SubmissionDeletionRequest rawRequest) {
        NormalizedRequest request = normalize(rawRequest);
        DeletionJobRow existing = repository.findJob(tenantId, request.requestId()).orElse(null);
        if (existing != null) return existingView(existing);

        PreparedDeletion prepared;
        try {
            prepared = transactions.execute(status -> prepare(requestedBy, scopeCity, request));
        } catch (DuplicateKeyException duplicate) {
            return repository.findJob(tenantId, request.requestId())
                    .map(this::existingView)
                    .orElseThrow(() -> TemporaryCheckinException.conflict("删除请求正在并发处理，请稍后刷新"));
        }
        if (prepared == null) {
            throw TemporaryCheckinException.conflict("删除请求未能创建");
        }

        List<SubmissionDeletionFailure> failures = new ArrayList<>();
        int deleted = 0;
        for (DeletionCandidateRow candidate : prepared.candidates()) {
            try {
                deleteCandidate(candidate);
                deleted++;
            } catch (RuntimeException exception) {
                repository.markFailed(tenantId, candidate.id(), clock.instant());
                failures.add(new SubmissionDeletionFailure(
                        candidate.id(), "DELETE_FAILED", "对象存储或数据库删除失败，可稍后重试"));
            }
        }
        Instant completedAt = clock.instant();
        String status = failures.isEmpty() ? "COMPLETED"
                : deleted == 0 ? "FAILED" : "PARTIAL_FAILED";
        SubmissionDeletionView view = new SubmissionDeletionView(
                request.requestId(), status, request.ids().size(), deleted,
                failures.size(), List.copyOf(failures), completedAt);
        repository.finishJob(tenantId, request.requestId(), status, deleted, failures.size(),
                toJson(view), completedAt);
        return view;
    }

    private PreparedDeletion prepare(
            String requestedBy, String scopeCity, NormalizedRequest request) {
        Instant now = clock.instant();
        UUID jobId = UUID.randomUUID();
        repository.insertJob(jobId, tenantId, request.requestId(), requestedBy, scopeCity,
                request.reason(), request.ids().size(), "{}", now);
        List<DeletionCandidateRow> candidates = repository.findCandidates(tenantId, request.ids(), scopeCity);
        if (candidates.size() != request.ids().size()) {
            throw TemporaryCheckinException.notFound("所选记录包含不存在或超出当前城市范围的数据");
        }
        for (DeletionCandidateRow candidate : candidates) {
            if ("PENDING".equals(candidate.deletionState())) {
                throw TemporaryCheckinException.conflict("所选记录中有正在删除的数据，请稍后重试");
            }
            if (repository.markPending(tenantId, candidate.id(), now) != 1) {
                throw TemporaryCheckinException.conflict("所选记录状态已变化，请刷新后重试");
            }
        }
        repository.markJobProcessing(tenantId, request.requestId(), now);
        return new PreparedDeletion(candidates);
    }

    private void deleteCandidate(DeletionCandidateRow candidate) {
        LinkedHashSet<String> objectKeys = new LinkedHashSet<>(candidate.projectedObjectKeys());
        objectKeys.addAll(activeAudioObjectKeys(candidate.audioSegmentsJson()));
        for (String objectKey : objectKeys) {
            requireOwnedObjectKey(candidate.id(), objectKey);
            fileStorage.delete(tenantId.toString(), objectKey);
        }
        if (repository.hardDelete(tenantId, candidate.id()) != 1) {
            throw new IllegalStateException("submission deletion state changed");
        }
    }

    private List<String> activeAudioObjectKeys(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(manifestJson);
            if (root == null || !root.isArray()) return List.of();
            List<String> keys = new ArrayList<>();
            for (JsonNode item : root) {
                JsonNode deletedAt = item.get("deletedAt");
                JsonNode objectKey = item.get("objectKey");
                if ((deletedAt == null || deletedAt.isNull()) && objectKey != null && objectKey.isTextual()
                        && !objectKey.asText().isBlank()) {
                    keys.add(objectKey.asText());
                }
            }
            return keys;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid audio segment manifest", exception);
        }
    }

    private void requireOwnedObjectKey(UUID submissionId, String objectKey) {
        String root = tenantId + "/temporary-sales-checkin/" + submissionId + "/";
        if (!objectKey.startsWith(root) || objectKey.contains("..") || objectKey.indexOf('\\') >= 0) {
            throw new IllegalStateException("unsafe temporary check-in object key");
        }
        String relative = objectKey.substring(root.length());
        if (!(relative.startsWith("photos/storefront/")
                || relative.startsWith("screenshots/wechat/")
                || relative.startsWith("recordings/visit/"))) {
            throw new IllegalStateException("unexpected temporary check-in object directory");
        }
    }

    private NormalizedRequest normalize(SubmissionDeletionRequest request) {
        if (request == null || request.requestId() == null) {
            throw TemporaryCheckinException.badRequest("requestId不能为空");
        }
        if (!CONFIRMATION.equals(request.confirmation())) {
            throw TemporaryCheckinException.badRequest("请重新确认永久删除操作");
        }
        if (request.ids() == null || request.ids().isEmpty() || request.ids().size() > MAX_BATCH_SIZE
                || request.ids().stream().anyMatch(java.util.Objects::isNull)) {
            throw TemporaryCheckinException.badRequest("每次必须选择1到20条记录");
        }
        if (new HashSet<>(request.ids()).size() != request.ids().size()) {
            throw TemporaryCheckinException.badRequest("所选记录不能重复");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 2 || reason.length() > 512
                || reason.chars().anyMatch(character -> Character.isISOControl(character)
                        && character != '\n')) {
            throw TemporaryCheckinException.badRequest("删除原因必须为2到512个有效字符");
        }
        return new NormalizedRequest(request.requestId(), List.copyOf(request.ids()), reason);
    }

    private SubmissionDeletionView existingView(DeletionJobRow row) {
        if (row.resultJson() != null && !row.resultJson().isBlank() && !"{}".equals(row.resultJson())) {
            try {
                return objectMapper.readValue(row.resultJson(), SubmissionDeletionView.class);
            } catch (RuntimeException ignored) {
                // 历史审计 JSON 损坏时仍返回任务的结构化列，不向浏览器暴露内部细节。
            }
        }
        return new SubmissionDeletionView(row.requestId(), row.status(), row.requestedCount(),
                row.deletedCount(), row.failedCount(), List.of(), row.completedAt());
    }

    private String toJson(SubmissionDeletionView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("cannot serialize deletion audit", exception);
        }
    }

    private record NormalizedRequest(UUID requestId, List<UUID> ids, String reason) { }

    private record PreparedDeletion(List<DeletionCandidateRow> candidates) { }
}
