package com.rigour.order.application.port.out;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** Order Center调用Integration发现启用订货宝订单同步目标的出站端口。 */
public interface DhbSyncTargetDiscoveryClient {
    List<SyncTargetView> discover(CallerIdentity serviceCaller);
}
