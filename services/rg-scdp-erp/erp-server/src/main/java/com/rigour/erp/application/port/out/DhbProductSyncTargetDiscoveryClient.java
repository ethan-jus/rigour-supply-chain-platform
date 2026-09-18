package com.rigour.erp.application.port.out;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** 发现 Integration 中启用的 PRODUCT_MASTER_DATA 同步目标。 */
public interface DhbProductSyncTargetDiscoveryClient {
    List<SyncTargetView> discover(CallerIdentity serviceCaller);
}
