package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 连接器同步租约的版本化请求和响应模型。 */
public final class DhbConnectorLeaseModels {
    private DhbConnectorLeaseModels() { }

    /** ownerService只用于审计，不参与租约所有权判断。 */
    public record LeaseCommand(String ownerService) { }

    /** token是唯一所有权凭据，续租和释放必须精确携带。 */
    public record LeaseView(UUID connectorId, String token, Instant expiresAt, long ttlSeconds) { }
}
