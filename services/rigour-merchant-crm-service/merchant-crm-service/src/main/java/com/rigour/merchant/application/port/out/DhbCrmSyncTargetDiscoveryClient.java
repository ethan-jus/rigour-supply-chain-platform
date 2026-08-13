package com.rigour.merchant.application.port.out;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** 发现 Integration 中启用的 CRM 主数据同步目标。 */
public interface DhbCrmSyncTargetDiscoveryClient {
    List<SyncTargetView> discover(CallerIdentity serviceCaller);
}
