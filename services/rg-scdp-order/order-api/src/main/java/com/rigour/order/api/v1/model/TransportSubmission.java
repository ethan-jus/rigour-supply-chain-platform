package com.rigour.order.api.v1.model;

import java.util.List;

/** files 按 upload 顺序通过 multipart 提交，不接受浏览器临时地址或外部对象键。 */
public record TransportSubmission(
        String submissionId,
        TransportRecordModels.Draft draft,
        LogisticsCostCommand advanced,
        List<Upload> uploads,
        List<String> removedIds,
        Long salesOrderId) {
    public TransportSubmission(
            String submissionId,
            TransportRecordModels.Draft draft,
            LogisticsCostCommand advanced,
            List<Upload> uploads,
            List<String> removedIds) {
        this(submissionId, draft, advanced, uploads, removedIds, null);
    }

    public record Upload(String categoryCode, String replacingId) {}
}
