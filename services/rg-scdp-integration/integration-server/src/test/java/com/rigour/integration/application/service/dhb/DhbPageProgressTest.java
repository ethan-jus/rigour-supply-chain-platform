package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class DhbPageProgressTest {
    @Test
    void reportsCurrentPageBeforeWorkAndSeparatesPendingFromApplied() {
        var stages = new ArrayList<String>();
        var progress = new DhbPageProgress(stages::add, "订单及明细", 2, 3, 100, 350);
        assertThat(stages.getFirst()).contains("第 2 页处理中 0 / 3", "100 / 350");
        progress.completed(DhbOrderSyncService.ProjectionOutcome.CHANGED);
        progress.completed(DhbOrderSyncService.ProjectionOutcome.DUPLICATE);
        progress.completed(DhbOrderSyncService.ProjectionOutcome.REVIEW);
        assertThat(stages.getLast()).contains("3 / 3", "103 / 350", "已更新 1", "未变化 1", "待处理 1");
    }
}
