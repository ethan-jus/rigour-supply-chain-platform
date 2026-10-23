package com.rigour.analytics.application.port.out;

/** 详情读取前由原领域再次校验当前单据权限与版本。 */
public interface CostSourceDetail {
    record State(String status, long revision) {}

    State verify(String source, String category, String id);
}
