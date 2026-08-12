package com.rigour.erp.application.port.out;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** 发现启用的订货宝供应链数据连接器，不读取连接器 Secret。 */
public interface DhbSupplySyncTargetDiscoveryClient {
    List<SyncTargetView> discover(CallerIdentity serviceCaller);
}
