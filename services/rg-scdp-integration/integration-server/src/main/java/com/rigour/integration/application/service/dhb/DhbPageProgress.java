package com.rigour.integration.application.service.dhb;

import java.util.function.Consumer;

/** 页内进度按完成数更新；并发回调串行发布，最多每两秒写一次，末条必报。 */
final class DhbPageProgress {
    private final Consumer<String> publish;
    private final String label;
    private final int page, size;
    private final long before, total;
    private long lastPublished;
    private int completed, changed, duplicates, pending;

    DhbPageProgress(
            Consumer<String> publish, String label, int page, int size, long before, long total) {
        this.publish = publish;
        this.label = label;
        this.page = page;
        this.size = size;
        this.before = before;
        this.total = total;
        emit();
    }

    synchronized void completed(DhbOrderSyncService.ProjectionOutcome outcome) {
        completed++;
        switch (outcome) {
            case DUPLICATE -> duplicates++;
            case REJECTED, REVIEW -> pending++;
            default -> changed++;
        }
        if (completed == size || System.nanoTime() - lastPublished >= 2_000_000_000L) emit();
    }

    private void emit() {
        lastPublished = System.nanoTime();
        publish.accept(
                label
                        + "第 "
                        + page
                        + " 页处理中 "
                        + completed
                        + " / "
                        + size
                        + "；当前窗口已处理 "
                        + (before + completed)
                        + (total >= 0 ? " / " + total : "")
                        + " 条；本页已更新 "
                        + changed
                        + "，未变化 "
                        + duplicates
                        + "，待处理 "
                        + pending);
    }
}
