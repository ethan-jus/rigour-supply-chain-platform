package com.rigour.integration.api.v1.model;

import java.util.List;

/** 订货宝同步中心待处理问题分组；后端统一分类，Portal 只负责展示和提交动作。 */
public record DhbSyncOpenIssueGroupView(
        /** 分类编码，例如 MANUAL_TRANSFER_INBOUND、MAPPING_REQUIRED。 */ String category,
        /** 来源对象类型。 */ String sourceObjectType,
        /** 错误码。 */ String errorCode,
        /** 分类标题。 */ String title,
        /** 推荐处理方式：MANUAL_RESOLUTION、FIX_MAPPING、FIX_SOURCE_TIME、REPLAY_AFTER_MAPPING、CODE_REPAIR。 */
        String actionType,
        /** 记录行数；同一来源对象可能有多条历史死信或对账差异。 */ long recordCount,
        /** 唯一来源对象数。 */ long uniqueSourceCount,
        /** 处理建议。 */ String handlingAdvice,
        /** 样例和可处理项，按更新时间倒序返回。 */ List<DhbSyncOpenIssueItemView> items) {
    public DhbSyncOpenIssueGroupView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
