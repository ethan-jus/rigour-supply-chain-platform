package com.rigour.integration.application.service.feishu;

import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunCommand;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunResult;
import com.rigour.integration.application.port.out.FeishuImportStore;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 飞书正式导入后台执行器；HTTP 入口只提交任务并轮询状态。 */
public final class FeishuImportAsyncRunService {
    private static final Logger log = LoggerFactory.getLogger(FeishuImportAsyncRunService.class);
    private static final int STATUS_ROW_LIMIT = 500;

    private final FeishuImportBundleService delegate;
    private final FeishuImportStore store;
    private final ExecutorService executor;
    private final Clock clock;
    private final ConcurrentMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public FeishuImportAsyncRunService(FeishuImportBundleService delegate,
                                       FeishuImportStore store,
                                       ExecutorService executor,
                                       Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "FeishuImportBundleService不能为空");
        this.store = Objects.requireNonNull(store, "FeishuImportStore不能为空");
        this.executor = Objects.requireNonNull(executor, "ExecutorService不能为空");
        this.clock = Objects.requireNonNull(clock, "Clock不能为空");
    }

    public FeishuImportRunResult start(CallerIdentity caller, UUID batchId,
                                       FeishuImportRunCommand command) {
        Objects.requireNonNull(caller, "CallerIdentity不能为空");
        Objects.requireNonNull(batchId, "导入批次ID不能为空");
        if (caller.tenantId() == null || caller.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "飞书导入需要租户用户身份", List.of());
        }
        FeishuImportStore.StoredBatch batch = store.batch(caller.tenantId(), batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "飞书导入批次不存在", List.of()));
        if ("REJECTED".equals(batch.status()) || "CANCELLED".equals(batch.status())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前导入批次状态不允许执行", List.of());
        }
        String taskKey = taskKey(caller.tenantId(), batchId);
        Future<?> existing = runningTasks.get(taskKey);
        if (existing != null && !existing.isDone()) {
            return delegate.runStatus(caller, batchId, STATUS_ROW_LIMIT);
        }

        store.updateBatchStatus(caller.tenantId(), batchId, "RUNNING", caller.userId(), clock.instant());
        FeishuImportRunCommand workerCommand = new FeishuImportRunCommand(
                command == null ? null : command.maxRows(),
                false,
                true,
                false);
        Future<?> submitted = executor.submit(() -> {
            try {
                delegate.run(caller, batchId, workerCommand);
            } catch (RuntimeException exception) {
                log.error("飞书后台导入执行失败 tenantId={} batchId={} errorType={} reason={}",
                        caller.tenantId(), batchId, exception.getClass().getSimpleName(),
                        exception.getMessage(), exception);
                store.updateBatchStatus(caller.tenantId(), batchId, "FAILED",
                        caller.userId(), clock.instant());
            } finally {
                runningTasks.remove(taskKey);
            }
        });
        runningTasks.put(taskKey, submitted);
        return delegate.runStatus(caller, batchId, STATUS_ROW_LIMIT);
    }

    private static String taskKey(UUID tenantId, UUID batchId) {
        return tenantId + ":" + batchId;
    }
}
