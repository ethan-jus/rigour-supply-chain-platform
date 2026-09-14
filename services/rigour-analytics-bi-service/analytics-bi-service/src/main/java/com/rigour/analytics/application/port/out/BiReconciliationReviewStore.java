package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 显式采集作业的读取端口及 BI 本地证据存储；禁止回写来源和订单。 */
public interface BiReconciliationReviewStore {
    /** 仅由显式 POST 采集事务读取；完整性由保存的采集证据和表范围推导。 */
    record OnlineCapture(BiReconciliationReview.Version version, List<Map<String, Object>> rows,
                         boolean sourceComplete) { }
    Optional<OnlineCapture> onlineCapture(String tenant, String captureId);
    Optional<BiReconciliationReview.Version> version(String tenant, String batchId);
    List<Map<String, Object>> sourceRows(String tenant, String batchId, int limit);
    List<BiReconciliationReview.Fact> businessRows(String tenant, int limit);
    List<BiReconciliationReview.Fact> biRows(String tenant, int limit);
    void save(String tenant, String actor, BiReconciliationReview review);
    Optional<BiReconciliationReview> find(String tenant, String actor, String id);
    List<BiReconciliationReview.History> history(String tenant, String actor);
}
