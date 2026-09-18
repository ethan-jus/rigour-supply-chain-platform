package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 在线只读对账契约；外部记录与连接配置不通过浏览器返回。 */
public final class FeishuReconciliationModels {
    private FeishuReconciliationModels() { }

    public record SourceView(String id, String name, boolean filtered, List<TableView> tables) {
        public SourceView { tables = List.copyOf(tables); }
    }

    public record TableView(String tableCode, String name, boolean filtered) { }

    public record CaptureCommand(String sourceId) { }

    /** complete 只表示指定表的分页完整，不表示跨表、跨页的原子快照。 */
    public record CaptureSummary(UUID id, String sourceId, String sourceName, String sourceUrl,
                                 Instant startedAt, Instant completedAt, boolean complete,
                                 boolean filtered, String checksum, int recordCount, int pageCount) { }
}
