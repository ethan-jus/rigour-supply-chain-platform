package com.rigour.hr.application.port.out;
import com.rigour.tenant.iam.api.v1.model.SupplyReadinessView;
/** 只检查本服务持有的数据，供应用按租户启用前核验。 */
public interface SupplyReadinessStore { SupplyReadinessView inspect(String tenant); }
