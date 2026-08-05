package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.OrderQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.PageRequest;
import com.rigour.integration.application.port.out.DhbClient.TimeWindow;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.port.out.DhbSyncStore.PagePersistResult;
import com.rigour.integration.application.port.out.DhbSyncStore.SyncCheckpoint;
import com.rigour.integration.application.port.out.DhbSyncStore.SyncRunStarted;
import com.rigour.integration.application.port.out.DhbSyncStore.SyncTaskContext;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import com.rigour.shared.context.CallerIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订货宝订单手动同步用例。
 *
 * <p>第一阶段只拉取订单列表，使用供应商更新时间窗口和 begin/step 分页。每一页在
 * Integration 内先落 Raw Landing，再更新订单镜像并写入 Outbox；同步成功后才推进
 * checkpoint。不会调用订货宝写接口，也不会把外部状态覆盖成 Order Center 的内部状态。</p>
 */
public final class DhbOrderSyncService {

    private static final Logger log = LoggerFactory.getLogger(DhbOrderSyncService.class);
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final Duration FIRST_RUN_WINDOW = Duration.ofHours(24);

    private final DhbSyncStore store;
    private final DhbClient client;

    public DhbOrderSyncService(DhbSyncStore store, DhbClient client) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.client = Objects.requireNonNull(client, "client cannot be null");
    }

    public SyncRunView runOrderPull(CallerIdentity caller, UUID taskId, SyncRunCommand command) {
        Objects.requireNonNull(caller, "caller cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");

        SyncTaskContext task = store.loadTask(caller.tenantId(), taskId);
        validateTask(task);
        SyncCheckpoint checkpoint = store.loadCheckpoint(caller.tenantId(), taskId);
        Window window = resolveWindow(task, checkpoint, command);
        int pageSize = resolvePageSize(task, command);
        SyncRunStarted started = store.beginRun(caller.tenantId(), caller.userId(), taskId,
                window.from(), window.to());

        long fetched = 0;
        long accepted = 0;
        long duplicate = 0;
        long rejected = 0;
        try {
            PageRequest pageRequest = PageRequest.first(pageSize);
            while (true) {
                Page<OrderSummary> page = client.getOrders(task.connector(), new OrderQuery(
                        pageRequest, null, null, new TimeWindow(window.from(), window.to()),
                        "all", "all", null, null));
                fetched += page.items().size();
                PagePersistResult persisted = store.persistOrderPage(caller.tenantId(), taskId,
                        started.runId(), page.items(), Instant.now());
                accepted += persisted.acceptedCount();
                duplicate += persisted.duplicateCount();
                rejected += persisted.rejectedCount();
                if (!page.hasNext()) {
                    break;
                }
                pageRequest = page.nextRequest();
            }

            String status = rejected == 0 ? "SUCCEEDED" : "PARTIAL";
            String errorCode = rejected == 0 ? null : "DHB_PARTIAL_SYNC";
            String errorMessage = rejected == 0 ? null : "部分订货宝订单缺少可用业务主键，checkpoint 未推进";
            store.finishRun(caller.tenantId(), caller.userId(), taskId, started.runId(),
                    window.from(), window.to(), status, fetched, accepted, duplicate, rejected,
                    window.to().toString(), errorCode, errorMessage);
            return new SyncRunView(started.runId(), taskId, status, window.from(), window.to(),
                    fetched, accepted, duplicate, rejected, errorCode, errorMessage);
        } catch (RuntimeException error) {
            String errorCode = "DHB_SYNC_FAILED";
            String errorMessage = safeMessage(error);
            try {
                store.finishRun(caller.tenantId(), caller.userId(), taskId, started.runId(),
                        window.from(), window.to(), "FAILED", fetched, accepted, duplicate, rejected,
                        null, errorCode, errorMessage);
            } catch (RuntimeException persistError) {
                log.error("订货宝同步失败后无法写入同步批次 tenantId={} taskId={} runId={}",
                        caller.tenantId(), taskId, started.runId(), persistError);
            }
            throw error;
        }
    }

    private static void validateTask(SyncTaskContext task) {
        if (task == null) {
            throw new IllegalArgumentException("订货宝同步任务不存在");
        }
        if (!"ORDER".equalsIgnoreCase(task.objectType())) {
            throw new IllegalArgumentException("当前第一阶段只支持 objectType=ORDER");
        }
        if (!task.enabled()) {
            throw new IllegalStateException("订货宝同步任务已禁用");
        }
        if (!"ACTIVE".equalsIgnoreCase(task.connectorStatus())) {
            throw new IllegalStateException("订货宝连接器未启用");
        }
        if ("PAUSED".equalsIgnoreCase(task.taskStatus())) {
            throw new IllegalStateException("订货宝同步任务已暂停");
        }
    }

    private static Window resolveWindow(SyncTaskContext task, SyncCheckpoint checkpoint,
                                        SyncRunCommand command) {
        Instant from = command == null ? null : command.from();
        Instant to = command == null ? null : command.to();
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("同步窗口 from 和 to 必须同时提供");
        }
        if (from == null) {
            to = Instant.now();
            Instant checkpointAt = checkpoint == null ? null : checkpoint.sourceUpdatedAt();
            from = checkpointAt == null
                    ? to.minus(FIRST_RUN_WINDOW)
                    : checkpointAt.minusSeconds(Math.max(0, task.overlapSeconds()));
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("同步窗口 from 必须早于 to");
        }
        return new Window(from, to);
    }

    private static int resolvePageSize(SyncTaskContext task, SyncRunCommand command) {
        int pageSize = command == null || command.pageSize() == null
                ? task.batchSize() : command.pageSize();
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > 1000) {
            throw new IllegalArgumentException("订货宝订单分页 pageSize 不能超过1000");
        }
        return pageSize;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "订货宝同步失败";
        }
        String redacted = message.replaceAll(
                "(?i)(password|token|skey|serialnumber|api[-_]?key)\\s*[:=]\\s*[^,;\\s}]+",
                "$1=[REDACTED]");
        return redacted.length() > 2000 ? redacted.substring(0, 2000) : redacted;
    }

    private record Window(Instant from, Instant to) {
    }
}
