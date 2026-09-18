package com.rigour.integration.application.port.out;

import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureSummary;
import java.util.UUID;

/** 不可变在线证据写入端口；不依赖导入批次或领域投影。 */
public interface FeishuOnlineCaptureStore {
    void save(UUID tenantId, UUID actorId, CaptureSummary summary, String payloadJson);
}
