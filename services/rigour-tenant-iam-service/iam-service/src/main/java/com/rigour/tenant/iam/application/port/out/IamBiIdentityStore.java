package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.model.IamBiIdentity;
import java.util.UUID;

/** 读取 IAM 自有绑定和策略，不跨域读取 HR/CRM。 */
public interface IamBiIdentityStore {
    IamBiIdentity read(UUID tenantId, UUID userId);
}
