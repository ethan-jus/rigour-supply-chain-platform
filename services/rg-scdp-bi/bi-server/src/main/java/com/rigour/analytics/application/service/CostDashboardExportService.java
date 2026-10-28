package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.CostDashboardExportView;
import com.rigour.analytics.api.v1.model.CostDashboardQuery;
import com.rigour.analytics.application.port.out.CostExportStore;
import com.rigour.analytics.domain.cost.*;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import jakarta.annotation.PreDestroy;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 有界异步导出；任务归属、当前授权与可见记录集合在状态查询及下载时重新校验。 */
@Service
public class CostDashboardExportService {
    private static final Logger log = LoggerFactory.getLogger(CostDashboardExportService.class);
    private final CostDashboardService service;
    private final CostDashboardAccess access;
    private final CostExportStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    1,
                    2,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    r -> {
                        Thread t = new Thread(r, "cost-export");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public CostDashboardExportService(
            CostDashboardService service,
            CostDashboardAccess access,
            CostExportStore store,
            ObjectMapper json,
            Clock analyticsClock,
            @org.springframework.beans.factory.annotation.Value(
                            "${rigour.analytics.cost-dashboard.enabled:true}")
                    boolean enabled) {
        this.service = service;
        this.access = access;
        this.store = store;
        this.json = json;
        this.clock = analyticsClock;
        this.enabled = enabled;
    }

    public CostDashboardExportView create(CostDashboardQuery query) {
        var c = service.context(query);
        requireExport(c.access());
        var rows = service.matching(c);
        requireExportRows(c.access(), rows);
        if (rows.size() > 100000) throw new IllegalArgumentException("单次导出最多10万笔，请缩小筛选范围");
        String id = UUID.randomUUID().toString(),
                tenant = c.snapshot().tenant(),
                actor = c.access().actor().principalId().toString();
        var fixed =
                new CostDashboardQuery(
                        query.year(),
                        query.month(),
                        query.compare(),
                        query.city(),
                        query.scope(),
                        query.category(),
                        query.dimension(),
                        query.dimensionKey(),
                        query.status(),
                        query.payment(),
                        query.search(),
                        query.periodSide(),
                        query.page(),
                        query.size(),
                        c.snapshot().id(),
                        query.missingAmount());
        store.create(
                new CostExportStore.Task(
                        id,
                        tenant,
                        actor,
                        c.snapshot().id(),
                        fingerprint(c.access().hash(), rows),
                        json.writeValueAsString(fixed),
                        "PENDING",
                        null,
                        null,
                        null,
                        clock.instant()));
        try {
            executor.execute(() -> generate(tenant, actor, id, c, rows));
        } catch (RejectedExecutionException e) {
            store.fail(tenant, id, "EXPORT_BUSY");
            log.warn("cost_export_rejected tenant={} actor={} export={}", tenant, actor, id);
        }
        log.info(
                "cost_export_created tenant={} actor={} export={} snapshot={} rows={} year={}"
                        + " month={} scope={} city={}",
                tenant,
                actor,
                id,
                c.snapshot().id(),
                rows.size(),
                c.filter().year(),
                c.filter().month(),
                c.filter().scope(),
                c.filter().city());
        return status(id);
    }

    private void generate(
            String tenant,
            String actor,
            String id,
            CostDashboardService.Context c,
            List<CostFact> rows) {
        long start = System.nanoTime();
        try {
            if (!store.start(tenant, id)) return;
            String content = csv(c, rows);
            if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(60))
                throw new IllegalStateException("EXPORT_TIMEOUT");
            store.finish(tenant, id, content, rows.size());
            log.info(
                    "cost_export_completed tenant={} actor={} export={} snapshot={} rows={}"
                            + " elapsedMs={}",
                    tenant,
                    actor,
                    id,
                    c.snapshot().id(),
                    rows.size(),
                    (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            store.fail(tenant, id, "EXPORT_FAILED");
            log.warn(
                    "cost_export_failed tenant={} export={} errorType={}",
                    tenant,
                    id,
                    e.getClass().getSimpleName());
        }
    }

    public CostDashboardExportView status(String id) {
        var task = authorized(id);
        return new CostDashboardExportView(
                id,
                task.status(),
                task.error(),
                task.count(),
                "READY".equals(task.status())
                        ? "/analytics/supply/cost-dashboard/exports/" + id + "/download"
                        : null);
    }

    public byte[] download(String id) {
        var task = authorized(id);
        if (!"READY".equals(task.status()) || task.content() == null)
            throw new CostDashboardFailure("COST_EXPORT_NOT_READY", 409, "导出尚未完成");
        log.info(
                "cost_export_download tenant={} actor={} export={} snapshot={} rows={}",
                task.tenant(),
                task.actor(),
                id,
                task.snapshot(),
                task.count());
        return task.content().getBytes(StandardCharsets.UTF_8);
    }

    public void cancel(String id) {
        var task = authorized(id);
        store.cancel(task.tenant(), task.actor(), id);
        log.info(
                "cost_export_cancel tenant={} actor={} export={}", task.tenant(), task.actor(), id);
    }

    private CostExportStore.Task authorized(String id) {
        UUID.fromString(id);
        var a = access.current();
        requireExport(a);
        var task =
                store.find(a.actor().tenantId().toString(), a.actor().principalId().toString(), id);
        if (task == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出任务不存在或无权限", List.of());
        if (task.createdAt().isBefore(clock.instant().minus(Duration.ofHours(24))))
            throw CostDashboardFailure.expired();
        var c = service.context(json.readValue(task.filterJson(), CostDashboardQuery.class));
        requireExportRows(c.access(), service.matching(c));
        if (!task.authorizationHash().equals(fingerprint(c.access().hash(), service.matching(c))))
            throw new AuthorizationDeniedException("导出数据权限已变化，请重新导出");
        return task;
    }

    private static void requireExportRows(CostDashboardAccess.Access a, List<CostFact> rows) {
        if (rows.stream().anyMatch(r -> !a.exportVisible(r)))
            throw new AuthorizationDeniedException("当前筛选超出导出数据范围，请缩小范围后重试");
    }

    private static void requireExport(CostDashboardAccess.Access a) {
        if (!a.exportAllowed()) throw new AuthorizationDeniedException(CostDashboardAccess.EXPORT);
    }

    private static String fingerprint(String auth, List<CostFact> rows) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(auth.getBytes(StandardCharsets.UTF_8));
            rows.stream()
                    .map(CostFact::key)
                    .sorted()
                    .forEach(k -> digest.update(("\n" + k).getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String cell(String value) {
        String v = Objects.toString(value, "");
        String trimmed = v.stripLeading();
        if (!trimmed.isEmpty() && "=+@-".indexOf(trimmed.charAt(0)) >= 0
                || v.startsWith("\t")
                || v.startsWith("\r")) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    public static String csv(CostDashboardService.Context c, List<CostFact> rows) {
        StringBuilder out = new StringBuilder("\ufeff");
        append(
                out,
                "本期",
                c.period().from() + " 至 " + c.period().to(),
                "可比期",
                c.period().previousFrom() + " 至 " + c.period().previousTo());
        append(
                out,
                "匹配笔数",
                String.valueOf(rows.size()),
                "已确认金额",
                CostAnalysis.money(CostAnalysis.amount(rows)));
        append(
                out,
                "单据号",
                "事项",
                "费用类别",
                "城市",
                "费用日期",
                "金额（元）",
                "确认状态",
                "付款状态",
                "费用所属月");
        for (var r : rows)
            append(
                    out,
                    r.documentNo(),
                    r.title(),
                    CostAnalysis.CATEGORIES.get(r.category()),
                    r.cityName(),
                    r.date().toString(),
                    CostAnalysis.money(r.amount()),
                    statusLabel(r.status()),
                    statusLabel(r.payment()),
                    r.costMonth().toString());
        return out.toString();
    }

    private static String statusLabel(String value) {
        return switch (value) {
            case "ALL" -> "全部";
            case "CONFIRMED" -> "已确认";
            case "DRAFT" -> "待确认";
            case "VOID" -> "已作废";
            case "PAID" -> "已付款";
            case "UNPAID" -> "未付款";
            default -> "未记录";
        };
    }

    private static void append(StringBuilder out, String... cells) {
        out.append(
                        Arrays.stream(cells)
                                .map(CostDashboardExportService::cell)
                                .collect(java.util.stream.Collectors.joining(",")))
                .append("\r\n");
    }

    @Scheduled(fixedDelayString = "${rigour.analytics.cost-dashboard.export-cleanup-ms:300000}")
    public void expire() {
        if (enabled) store.expire();
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
